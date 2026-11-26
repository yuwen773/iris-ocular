package com.csust.eyediagnosis.newscuration;

import com.csust.eyediagnosis.newscuration.NewsCurationModels.ChargedFailure;
import com.csust.eyediagnosis.newscuration.NewsCurationModels.DefinitiveFailure;
import com.csust.eyediagnosis.newscuration.NewsCurationModels.ModelCallOutcome;
import com.csust.eyediagnosis.newscuration.NewsCurationModels.ModelCallRequest;
import com.csust.eyediagnosis.newscuration.NewsCurationModels.Succeeded;
import com.csust.eyediagnosis.newscuration.NewsCurationModels.UnknownOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Dedicated news model instances using Spring AI Chat Completions and a single-attempt transport. */
@Component
public class NewsOpenAiCompatibleModelClient implements NewsCurationModelClient, AutoCloseable {

    private static final int MAX_RESPONSE_BYTES = 262_144;
    private static final NewsCurationModelClient DISABLED = new DisabledNewsCurationModelClient();
    private final ObjectMapper mapper;
    private final Map<String, Channel> channels = new LinkedHashMap<>();
    private final ThreadLocal<WireResponse> wire = new ThreadLocal<>();

    @Autowired
    public NewsOpenAiCompatibleModelClient(NewsCurationProperties properties,
                                           @Qualifier("newsProtocolObjectMapper") ObjectMapper mapper) {
        this(properties.isEnabled() ? properties.getModelProfiles() : Map.of(), System::getenv,
                NewsModelProfile::baseUrl, mapper);
    }

    // The endpoint override is package-private and exercised only by the loopback HTTP integration test.
    NewsOpenAiCompatibleModelClient(Map<String, NewsModelProfile> profiles, Function<String, String> secrets,
                                    Function<NewsModelProfile, URI> endpoint, ObjectMapper mapper) {
        this.mapper = mapper;
        if (!profiles.isEmpty() && Boolean.getBoolean("jdk.httpclient.enableAllMethodRetry")) {
            throw new IllegalStateException("JDK all-method retry conflicts with news model single-attempt transport");
        }
        try {
            profiles.forEach((key, profile) -> {
                if (!NewsModelProfile.identifier(key, 64)) {
                    throw new IllegalArgumentException("Invalid news model profile name");
                }
                String token = secrets.apply(profile.secretRef());
                if (token == null || token.isBlank() || token.length() > 1024
                        || !token.chars().allMatch(character -> character >= 33 && character <= 126)) {
                    throw new IllegalStateException("News model credential is missing or invalid for profile " + key);
                }
                channels.put(key, openChannel(profile, endpoint.apply(profile), token));
            });
        } catch (RuntimeException ex) {
            close();
            throw ex;
        }
    }

    private Channel openChannel(NewsModelProfile profile, URI endpoint, String token) {
        HttpClient transport = HttpClient.newBuilder().connectTimeout(profile.connectTimeout())
                .proxy(ProxySelector.of(null)).followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1).build();
        try {
            var factory = new JdkClientHttpRequestFactory(transport);
            factory.setReadTimeout(profile.readTimeout());
            var rest = RestClient.builder().requestFactory(factory)
                    .requestInterceptor((request, body, execution) -> capture(profile, execution.execute(request, body)));
            OpenAiApi api = OpenAiApi.builder().baseUrl(endpoint.resolve("/").toString())
                    .completionsPath(profile.completionsPath()).apiKey(token).restClientBuilder(rest).build();
            var model = OpenAiChatModel.builder().openAiApi(api)
                    .defaultOptions(options(profile, null)).observationRegistry(ObservationRegistry.NOOP)
                    .toolExecutionEligibilityPredicate((request, response) -> false)
                    .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
            return new Channel(profile, model, transport);
        } catch (RuntimeException ex) {
            transport.shutdownNow();
            throw ex;
        }
    }

    private static OpenAiChatOptions options(NewsModelProfile profile, String schema) {
        var builder = OpenAiChatOptions.builder().model(profile.modelId()).maxTokens(profile.maxOutputTokens())
                .temperature(profile.temperature()).N(1).internalToolExecutionEnabled(false);
        if (profile.responseFormat() == NewsModelProfile.Format.JSON_OBJECT) {
            builder.responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_OBJECT, null));
        } else if (profile.responseFormat() == NewsModelProfile.Format.JSON_SCHEMA && schema != null) {
            builder.responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_SCHEMA, schema));
        }
        if (profile.providerCapabilities().disableThinking() && profile.servingPlatform() == NewsModelProfile.Platform.DEEPSEEK) {
            builder.extraBody(Map.of("thinking", Map.of("type", "disabled")));
        } else if (profile.providerCapabilities().disableThinking() && profile.servingPlatform() == NewsModelProfile.Platform.BAILIAN) {
            builder.extraBody(Map.of("enable_thinking", false));
        }
        return builder.build();
    }

    @Override
    public ModelCallOutcome invoke(ModelCallRequest request) {
        Channel channel = request.profileKey() == null ? null : channels.get(request.profileKey());
        if (channel == null) {
            return DISABLED.invoke(request);
        }
        NewsModelProfile profile = channel.profile();
        if (!profile.provider().name().equals(request.provider()) || !profile.modelId().equals(request.modelName())
                || profile.responseFormat() == NewsModelProfile.Format.JSON_SCHEMA && request.outputSchema() == null) {
            return new DefinitiveFailure("MODEL_PROFILE_MISMATCH", null, null, false, 0);
        }
        long started = System.nanoTime();
        try {
            ChatResponse response = channel.model().call(new Prompt(
                    List.of(new SystemMessage(request.systemPrompt()), new UserMessage(request.userPrompt())),
                    options(profile, request.outputSchema())));
            return classify(profile, response, elapsed(started));
        } catch (RuntimeException ex) {
            return classify(profile, null, elapsed(started));
        } finally {
            wire.remove();
        }
    }

    private ModelCallOutcome classify(NewsModelProfile profile, ChatResponse response, int latency) {
        WireResponse received = wire.get();
        if (received == null || received.status() != 200) {
            return classifyError(profile, received, latency);
        }
        JsonNode raw = received.body();
        if (profile.servingPlatform() == NewsModelProfile.Platform.SPARK && raw != null && raw.has("code")
                && (!raw.path("code").isIntegralNumber() || raw.path("code").longValue() != 0)) {
            return new UnknownOutcome("SPARK_RESPONSE_OUTCOME_UNKNOWN", 200, safeId(raw.path("sid").textValue()), latency);
        }
        JsonNode usage = raw == null ? null : raw.get("usage");
        Integer prompt = usage == null ? null : nonnegativeInt(usage.get("prompt_tokens"));
        Integer output = usage == null ? null : nonnegativeInt(usage.get("completion_tokens"));
        Integer total = usage == null ? null : nonnegativeInt(usage.get("total_tokens"));
        String requestId = raw == null ? null : safeId(raw.path("id").asText(raw.path("sid").textValue()));
        if (prompt == null || prompt < 1 || output == null || total == null || (long) prompt + output != total
                || prompt > profile.maxInputTokens() || output > profile.maxOutputTokens()) {
            return new UnknownOutcome("USAGE_UNKNOWN_OR_OUTSIDE_ENVELOPE", 200, requestId, latency);
        }
        Integer cached = cachedTokens(profile, usage, prompt);
        if (cached == null) {
            return new UnknownOutcome("BILLABLE_USAGE_UNKNOWN", 200, requestId, latency);
        }
        long cost = profile.pricingVersion().cost(prompt, cached, output);
        String error = null;
        String content = null;
        if (response == null || response.getResults() == null || response.getResults().size() != 1
                || response.getResult().getOutput() == null) {
            error = "EMPTY_OR_AMBIGUOUS_OUTPUT";
        } else {
            var result = response.getResult();
            content = result.getOutput().getText();
            String finish = result.getMetadata().getFinishReason();
            if (result.getOutput().hasToolCalls() || !"stop".equalsIgnoreCase(finish)) {
                error = "length".equalsIgnoreCase(finish) ? "OUTPUT_TRUNCATED" : "UNSUPPORTED_FINISH_REASON";
            } else if (content == null || content.isBlank()) {
                error = "EMPTY_OUTPUT";
            }
        }
        Succeeded measured = new Succeeded(error == null ? content : null, prompt, output, cost, 200, requestId, latency, cached);
        return error == null ? measured : new ChargedFailure(error, measured);
    }

    private static Integer cachedTokens(NewsModelProfile profile, JsonNode usage, int prompt) {
        if (profile.providerCapabilities().usageFormat() == NewsModelProfile.UsageFormat.DEEPSEEK_CACHE) {
            Integer hit = nonnegativeInt(usage.get("prompt_cache_hit_tokens"));
            Integer miss = nonnegativeInt(usage.get("prompt_cache_miss_tokens"));
            return hit != null && miss != null && (long) hit + miss == prompt ? hit : null;
        }
        JsonNode cached = usage.path("prompt_tokens_details").get("cached_tokens");
        if (cached == null && profile.pricingVersion().inputPerMillion() == profile.pricingVersion().cachedInputPerMillion()) {
            return 0;
        }
        Integer count = nonnegativeInt(cached);
        return count != null && count <= prompt ? count : null;
    }

    private ModelCallOutcome classifyError(NewsModelProfile profile, WireResponse received, int latency) {
        if (received == null) {
            return new UnknownOutcome("MODEL_TRANSPORT_OUTCOME_UNKNOWN", null, null, latency);
        }
        int status = received.status();
        if (status == 401 || status == 403) {
            if (status == 403 && profile.servingPlatform() == NewsModelProfile.Platform.BAILIAN
                    && received.body() != null && ("Arrearage".equals(received.body().path("error").path("code").asText())
                    || "Arrearage".equals(received.body().path("code").asText()))) {
                return new DefinitiveFailure("MODEL_BALANCE_INSUFFICIENT", status, null, false, latency);
            }
            return new DefinitiveFailure("MODEL_AUTH_REJECTED", status, null, true, latency);
        }
        if (status == 402 && profile.servingPlatform() == NewsModelProfile.Platform.DEEPSEEK) {
            return new DefinitiveFailure("MODEL_BALANCE_INSUFFICIENT", status, null, false, latency);
        }
        if (status == 429) {
            return new UnknownOutcome("MODEL_RATE_LIMITED", status, null, latency);
        }
        return new UnknownOutcome("MODEL_RESPONSE_OUTCOME_UNKNOWN", status, null, latency);
    }

    private ClientHttpResponse capture(NewsModelProfile profile, ClientHttpResponse response) throws IOException {
        try {
            int status = response.getStatusCode().value();
            wire.set(new WireResponse(status, null));
            if (status != 200 && !(status == 403 && profile.servingPlatform() == NewsModelProfile.Platform.BAILIAN)) {
                throw new IOException("News model endpoint rejected the request");
            }
            byte[] bytes = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw new IOException("News model response exceeded size limit");
            }
            JsonNode body = mapper.readTree(bytes);
            wire.set(new WireResponse(status, body));
            if (status != 200) {
                throw new IOException("News model endpoint rejected the request");
            }
            if (body == null || !body.path("choices").isArray() || body.path("choices").isEmpty()) {
                throw new IOException("News model response has no choices");
            }
            if (profile.servingPlatform() == NewsModelProfile.Platform.SPARK
                    && body.has("code") && body.path("code").isIntegralNumber() && body.path("code").intValue() == 0
                    && body.path("choices").size() == 1 && body.path("choices").get(0) instanceof ObjectNode choice
                    && !choice.hasNonNull("finish_reason")) {
                Integer tokens = nonnegativeInt(body.path("usage").get("completion_tokens"));
                choice.put("finish_reason", tokens != null && tokens < profile.maxOutputTokens() ? "stop" : "length");
                bytes = mapper.writeValueAsBytes(body);
            }
            return new BufferedResponse(response, bytes);
        } catch (IOException | RuntimeException ex) {
            try {
                response.getBody().close();
            } catch (IOException ignored) {
                // The request has already failed and must not be retried.
            } finally {
                response.close();
            }
            throw ex;
        }
    }

    private static Integer nonnegativeInt(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt() && node.intValue() >= 0
                ? node.intValue() : null;
    }

    private static String safeId(String id) {
        return id != null && id.length() <= 128 && id.matches("[A-Za-z0-9][A-Za-z0-9@._-]*") ? id : null;
    }

    private static int elapsed(long started) {
        return (int) Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    @Override
    public void close() {
        channels.values().forEach(channel -> channel.transport().shutdownNow());
    }

    private record Channel(NewsModelProfile profile, OpenAiChatModel model, HttpClient transport) {
    }

    private record WireResponse(int status, JsonNode body) {
    }

    private record BufferedResponse(ClientHttpResponse original, byte[] bytes) implements ClientHttpResponse {
        @Override public HttpStatusCode getStatusCode() throws IOException { return original.getStatusCode(); }
        @Override public String getStatusText() throws IOException { return original.getStatusText(); }
        @Override public HttpHeaders getHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.putAll(original.getHeaders());
            headers.setContentLength(bytes.length);
            return headers;
        }
        @Override public InputStream getBody() { return new ByteArrayInputStream(bytes); }
        @Override public void close() { original.close(); }
    }
}
