package com.csust.eyediagnosis.newscuration;

import com.csust.eyediagnosis.config.JsonConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsOpenAiCompatibleModelClientTest {

    @Test
    void postsOnlyOnceWhenTheEndpointReturnsAnErrorOrRedirect() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            int count = requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"error\":{\"message\":\"fixture\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Location", "/chat/completions");
            exchange.sendResponseHeaders(count == 1 ? 500 : 307, body.length);
            try (exchange) {
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try (var client = new NewsOpenAiCompatibleModelClient(Map.of("fixture", profile()), key -> "local-test-key",
                ignored -> URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                JsonConfig.createNewsProtocolObjectMapper())) {
            for (int i = 1; i <= 2; i++) {
                assertInstanceOf(NewsCurationModels.UnknownOutcome.class, client.invoke(request()));
                assertEquals(i, requests.get());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void validatesNativeCacheUsageAndClassifiesChargedOrUnknownOutputFailures() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        var payload = new AtomicReference<String>();
        List<String> responses = List.of(
                completion("stop", "{}", true), completion("stop", "", true),
                completion("length", "{}", true), completion("stop", "{}", false),
                completion("stop", "{}", true).replace("\"prompt_cache_hit_tokens\":80", "\"prompt_cache_hit_tokens\":81"),
                "{\"id\":\"provider-id\",\"choices\":[],\"usage\":" + usage() + "}"
        );
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            int index = requests.getAndIncrement();
            payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responses.get(index).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (exchange) {
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        var mapper = JsonConfig.createNewsProtocolObjectMapper();
        try (var client = new NewsOpenAiCompatibleModelClient(Map.of("fixture", profile()), key -> "local-test-key",
                ignored -> URI.create("http://127.0.0.1:" + server.getAddress().getPort()), mapper)) {
            var success = assertInstanceOf(NewsCurationModels.Succeeded.class, client.invoke(request()));
            assertEquals(100, success.promptTokens());
            assertEquals(80, success.cachedPromptTokens());
            assertEquals(68, success.actualCostMicros());
            assertEquals(1, requests.get());
            var outgoing = mapper.readTree(payload.get());
            assertEquals("fixture-model", outgoing.path("model").asText());
            assertEquals("json_object", outgoing.path("response_format").path("type").asText());
            assertEquals(2, outgoing.path("messages").size());
            assertFalse(outgoing.has("tools"));
            assertFalse(outgoing.has("web_search_options"));

            assertEquals("EMPTY_OUTPUT", assertInstanceOf(NewsCurationModels.ChargedFailure.class,
                    client.invoke(request())).errorCode());
            assertEquals("OUTPUT_TRUNCATED", assertInstanceOf(NewsCurationModels.ChargedFailure.class,
                    client.invoke(request())).errorCode());
            assertInstanceOf(NewsCurationModels.UnknownOutcome.class, client.invoke(request()));
            assertEquals("BILLABLE_USAGE_UNKNOWN", assertInstanceOf(NewsCurationModels.UnknownOutcome.class,
                    client.invoke(request())).errorCode());
            assertEquals("EMPTY_OR_AMBIGUOUS_OUTPUT", assertInstanceOf(NewsCurationModels.ChargedFailure.class,
                    client.invoke(request())).errorCode());
            assertEquals(responses.size(), requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void transportSeparatesProviderRejectionFromUncertainFailures() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try (exchange) {
                int attempt = requests.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                switch (attempt) {
                    case 1 -> exchange.sendResponseHeaders(401, -1);
                    case 2 -> exchange.sendResponseHeaders(402, -1);
                    case 3 -> exchange.sendResponseHeaders(429, -1);
                    default -> { /* Disconnect after receiving the complete request. */ }
                }
            }
        });
        server.start();
        try (var client = new NewsOpenAiCompatibleModelClient(Map.of("fixture", profile()), key -> "local-test-key",
                ignored -> URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                JsonConfig.createNewsProtocolObjectMapper())) {
            assertEquals("MODEL_AUTH_REJECTED", assertInstanceOf(NewsCurationModels.DefinitiveFailure.class,
                    client.invoke(request())).errorCode());
            assertEquals("MODEL_BALANCE_INSUFFICIENT", assertInstanceOf(NewsCurationModels.DefinitiveFailure.class,
                    client.invoke(request())).errorCode());
            assertEquals("MODEL_RATE_LIMITED", assertInstanceOf(NewsCurationModels.UnknownOutcome.class,
                    client.invoke(request())).errorCode());
            assertInstanceOf(NewsCurationModels.UnknownOutcome.class, client.invoke(request()));
            assertEquals(4, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingProfileFailsClosedWithoutResolvingAnEndpointOrKey() {
        try (var client = new NewsOpenAiCompatibleModelClient(Map.of(),
                key -> { throw new AssertionError("credential lookup"); },
                profile -> { throw new AssertionError("endpoint lookup"); },
                JsonConfig.createNewsProtocolObjectMapper())) {
            assertEquals("MODEL_CLIENT_UNCONFIGURED", assertInstanceOf(NewsCurationModels.DefinitiveFailure.class,
                    client.invoke(request())).errorCode());
        }
    }

    @Test
    void readTimeoutAfterDispatchIsUnknownAndMakesOneRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try (exchange) {
                requests.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try (var client = new NewsOpenAiCompatibleModelClient(Map.of("fixture", profile(Duration.ofMillis(250))),
                key -> "local-test-key", ignored -> URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                JsonConfig.createNewsProtocolObjectMapper())) {
            assertEquals("MODEL_TRANSPORT_OUTCOME_UNKNOWN", assertInstanceOf(NewsCurationModels.UnknownOutcome.class,
                    client.invoke(request())).errorCode());
            assertEquals(1, requests.get());
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void sparkOfficialSuccessShapeKeepsTheSidAndTreatsTheTokenCapAsTruncation() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> payload = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                int tokens = requests.incrementAndGet() == 1 ? 4 : 10;
                payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = """
                        {"code":0,"message":"Success","sid":"cht000@fixture",
                         "choices":[{"index":0,"message":{"role":"assistant","content":"{}"}}],
                         "usage":{"prompt_tokens":20,"completion_tokens":%d,"total_tokens":%d}}
                        """.formatted(tokens, 20 + tokens).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        var mapper = JsonConfig.createNewsProtocolObjectMapper();
        var spark = new NewsModelProfile(NewsModelProfile.Provider.SPARK, NewsModelProfile.Platform.SPARK,
                URI.create("https://spark-api-open.xf-yun.com"), "/v1/chat/completions", "lite", "FIXTURE_KEY",
                Duration.ofSeconds(2), Duration.ofSeconds(2), 2048, 10, 0,
                NewsModelProfile.Format.PROMPT_JSON, new NewsModelProfile.Capabilities(
                        false, false, NewsModelProfile.UsageFormat.STANDARD, false),
                new NewsModelProfile.PricingVersion("fixture-v1", 1_000_000, 1_000_000, 2_000_000));
        var sparkRequest = new NewsCurationModels.ModelCallRequest("fixture-id", "fixture-key", "SPARK", "lite",
                "wp08b-test", "JSON only", "{}", "fixture-hash", null, Duration.ofSeconds(4),
                "spark", "{}");
        try (var client = new NewsOpenAiCompatibleModelClient(Map.of("spark", spark), key -> "local-test-key",
                ignored -> URI.create("http://127.0.0.1:" + server.getAddress().getPort()), mapper)) {
            var response = assertInstanceOf(NewsCurationModels.Succeeded.class, client.invoke(sparkRequest));
            assertEquals("cht000@fixture", response.providerRequestId());
            assertFalse(mapper.readTree(payload.get()).has("response_format"));
            assertEquals("OUTPUT_TRUNCATED", assertInstanceOf(NewsCurationModels.ChargedFailure.class,
                    client.invoke(sparkRequest)).errorCode());
            assertEquals(2, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void continuouslyTricklingResponseCannotExtendTheDeadline() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try (exchange) {
                requests.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                for (int chunk = 0; chunk < 20; chunk++) {
                    exchange.getResponseBody().write(' ');
                    exchange.getResponseBody().flush();
                    Thread.sleep(30);
                }
                exchange.getResponseBody().write(completion("stop", "{}", true).getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException ignored) {
                // Deadline cancellation closes the response stream.
            }
        });
        server.start();
        try (var client = new NewsOpenAiCompatibleModelClient(Map.of("fixture", profile(Duration.ofMillis(250))),
                key -> "local-test-key", ignored -> URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                JsonConfig.createNewsProtocolObjectMapper())) {
            assertInstanceOf(NewsCurationModels.UnknownOutcome.class, client.invoke(request()));
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void bailianFreeQuotaExhaustionIsDefinitiveButNotAnAuthFailure() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/compatible-mode/v1/chat/completions", exchange -> {
            try (exchange) {
                int attempt = requests.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                String body = switch (attempt) {
                    case 1 -> """
                            {"error":{"code":"AllocationQuota.FreeTierOnly","message":"free quota exhausted"},
                             "request_id":"free-tier-only"}
                            """;
                    case 2 -> "{\"code\":\"Arrearage\",\"message\":\"account in arrears\"}";
                    default -> "{\"error\":{\"code\":\"InvalidApiKey\",\"message\":\"bad key\"}}";
                };
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(403, payload.length);
                exchange.getResponseBody().write(payload);
            }
        });
        server.start();
        try (var client = new NewsOpenAiCompatibleModelClient(Map.of("fixture", bailianProfile()),
                key -> "local-test-key", ignored -> URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                JsonConfig.createNewsProtocolObjectMapper())) {
            // 额度用尽：确定性失败、不重试、不当作鉴权失败（否则一次额度用尽会把整个 scope 熔断）。
            NewsCurationModels.DefinitiveFailure quota = assertInstanceOf(
                    NewsCurationModels.DefinitiveFailure.class, client.invoke(bailianRequest()));
            assertEquals("MODEL_FREE_QUOTA_EXHAUSTED", quota.errorCode());
            assertFalse(quota.authFailure());
            assertEquals(403, quota.httpStatus());

            NewsCurationModels.DefinitiveFailure arrears = assertInstanceOf(
                    NewsCurationModels.DefinitiveFailure.class, client.invoke(bailianRequest()));
            assertEquals("MODEL_BALANCE_INSUFFICIENT", arrears.errorCode());
            assertFalse(arrears.authFailure(), "欠费不是凭据问题，不得触发鉴权熔断");

            NewsCurationModels.DefinitiveFailure auth = assertInstanceOf(
                    NewsCurationModels.DefinitiveFailure.class, client.invoke(bailianRequest()));
            assertEquals("MODEL_AUTH_REJECTED", auth.errorCode());
            assertTrue(auth.authFailure(), "真正的鉴权失败仍要熔断");

            assertEquals(3, requests.get(), "每次 invoke 只发一次请求：额度耗尽不得自动重发");
        } finally {
            server.stop(0);
        }
    }

    private static String usage() {
        return """
                {"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,
                 "prompt_cache_hit_tokens":80,"prompt_cache_miss_tokens":20}
                """;
    }

    private static String completion(String finish, String content, boolean includeUsage) {
        return """
                {"id":"provider-id","object":"chat.completion","created":1700000000,"model":"fixture-model",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"%s"}]%s}
                """.formatted(content, finish, includeUsage ? ",\"usage\":" + usage() : "");
    }

    private static NewsModelProfile bailianProfile() {
        return new NewsModelProfile(NewsModelProfile.Provider.QWEN, NewsModelProfile.Platform.BAILIAN,
                URI.create("https://dashscope.aliyuncs.com"), "/compatible-mode/v1/chat/completions",
                "qwen3.8-max-0902", "FIXTURE_KEY", Duration.ofSeconds(2), Duration.ofSeconds(2),
                8192, 1024, 0, NewsModelProfile.Format.JSON_OBJECT,
                new NewsModelProfile.Capabilities(true, false, NewsModelProfile.UsageFormat.STANDARD, true),
                // 北京地域标准价：输入 12 元 / 输出 36 元 / 缓存命中 1.5 元，每百万 token。
                new NewsModelProfile.PricingVersion("bailian-qwen3.8-max-20261127", 12_000_000, 1_500_000, 36_000_000));
    }

    private static NewsCurationModels.ModelCallRequest bailianRequest() {
        return new NewsCurationModels.ModelCallRequest("fixture-id", "fixture-key", "QWEN", "qwen3.8-max-0902",
                "wp08b-test", "JSON only", "{}", "fixture-hash", null, Duration.ofSeconds(4),
                "fixture", "{}");
    }

    private static NewsModelProfile profile() {
        return profile(Duration.ofSeconds(2));
    }

    private static NewsModelProfile profile(Duration readTimeout) {
        return new NewsModelProfile(NewsModelProfile.Provider.DEEPSEEK, NewsModelProfile.Platform.DEEPSEEK,
                URI.create("https://api.deepseek.com"), "/chat/completions", "fixture-model", "FIXTURE_KEY",
                Duration.ofSeconds(2), readTimeout, 2048, 100, 0,
                NewsModelProfile.Format.JSON_OBJECT, new NewsModelProfile.Capabilities(
                        true, false, NewsModelProfile.UsageFormat.DEEPSEEK_CACHE, false),
                new NewsModelProfile.PricingVersion("fixture-v1", 1_000_000, 100_000, 2_000_000));
    }

    private static NewsCurationModels.ModelCallRequest request() {
        return new NewsCurationModels.ModelCallRequest("fixture-id", "fixture-key", "DEEPSEEK", "fixture-model",
                "wp08b-test", "JSON only", "{}", "fixture-hash", null, Duration.ofSeconds(4),
                "fixture", "{}");
    }
}
