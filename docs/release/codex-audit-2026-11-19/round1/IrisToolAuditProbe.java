package com.csust.eyediagnosis.agent;

import com.csust.eyediagnosis.agent.config.AgentConstants;
import com.csust.eyediagnosis.agent.interceptor.TextMcpModelInterceptor;
import com.csust.eyediagnosis.agent.service.ChatHistoryService;
import com.csust.eyediagnosis.agent.service.ToolAuditService;
import com.csust.eyediagnosis.agent.tools.DocumentSearchTool;
import com.csust.eyediagnosis.agent.tools.EyeImageAnalyzerTool;
import com.csust.eyediagnosis.model.dto.agent.MessageDTO;
import com.csust.eyediagnosis.model.dto.agent.MessageType;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.Mockito.*;

class IrisToolAuditProbe {
    public static void main(String[] args) {
        check("unavailable", null, "failed");
        VectorStore success = mock(VectorStore.class);
        when(success.similaritySearch(org.mockito.ArgumentMatchers.any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of());
        check("no-match", success, "succeeded");
        VectorStore failing = mock(VectorStore.class);
        when(failing.similaritySearch(org.mockito.ArgumentMatchers.any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenThrow(new IllegalStateException("synthetic transport failure"));
        check("retrieval-failure", failing, "failed");
        checkVision("blank", "", "failed");
        checkVision("malformed", "Synthetic provider could not process the image.", "succeeded");
        checkVision("empty-json", "{}", "succeeded");
        checkTimeoutAndCancellationTransitions();
    }

    static void check(String scenario, VectorStore store, String expected) {
        DocumentSearchTool actual = new DocumentSearchTool();
        ReflectionTestUtils.setField(actual, "vectorStore", store);
        ReflectionTestUtils.setField(actual, "searchTopK", 5);
        ReflectionTestUtils.setField(actual, "finalTopK", 3);
        checkAgent(scenario, actual, mock(EyeImageAnalyzerTool.class), new FixtureModel("searchDocuments", "{\"query\":\"fixture\"}"), expected);
    }

    static void checkVision(String scenario, String visionText, String expected) {
        var cos = mock(com.csust.eyediagnosis.manager.CosManager.class);
        var mapper = mock(com.csust.eyediagnosis.mapper.UploadTaskMapper.class);
        var key = "agent-chat/42/synthetic.jpg";
        var task = new com.csust.eyediagnosis.model.entity.UploadTask();
        task.setUserId(42L);
        task.setObjectKey(key);
        task.setStatus(2);
        when(mapper.selectByObjectKey(42L, key)).thenReturn(task);
        var object = new com.qcloud.cos.model.COSObject();
        object.setObjectContent(new com.qcloud.cos.model.COSObjectInputStream(
            new java.io.ByteArrayInputStream(new byte[]{1, 2, 3}),
            new org.apache.http.client.methods.HttpGet("http://localhost/fixture")));
        when(cos.getObject(key)).thenReturn(object);
        var actual = new EyeImageAnalyzerTool(cos, mapper);
        ChatModel provider = prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage(visionText))));
        ReflectionTestUtils.setField(actual, "visionChatClient",
                org.springframework.ai.chat.client.ChatClient.builder(provider).build());
        checkAgent(scenario, new DocumentSearchTool(), actual,
                new FixtureModel("analyzeEyeImage", "{\"imageId\":\"" + key + "\"}"), expected);
    }

    static void checkAgent(String scenario, DocumentSearchTool actual, EyeImageAnalyzerTool image,
                           FixtureModel fake, String expected) {
        var constants = new AgentConstants();
        constants.setMaxIterations(5);
        constants.setModelCallLimit(5);
        var mcp = mock(TextMcpModelInterceptor.class);
        when(mcp.resolveMcpCallbacks(any(), any())).thenReturn(new ToolCallback[0]);
        var history = mock(ChatHistoryService.class);
        var audit = mock(ToolAuditService.class);
        when(audit.digestKey()).thenReturn(new byte[]{1, 2, 3});
        var agent = new DiagnosisAgent();
        ReflectionTestUtils.setField(agent, "chatModel", fake);
        ReflectionTestUtils.setField(agent, "textMcpModelInterceptor", mcp);
        ReflectionTestUtils.setField(agent, "chatHistoryService", history);
        ReflectionTestUtils.setField(agent, "agentConstants", constants);
        ReflectionTestUtils.setField(agent, "eyeImageAnalyzerTool", image);
        ReflectionTestUtils.setField(agent, "documentSearchTool", actual);
        ReflectionTestUtils.setField(agent, "toolAuditService", audit);
        List<MessageDTO> result = agent.stream("synthetic query", null, scenario, 42L, null, null)
                .filter(x -> x.getMessageType() == MessageType.TOOL_RESULT)
                .collectList().block(Duration.ofSeconds(25));
        var states = result.stream().map(MessageDTO::getToolStatus).toList();
        var row = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(history).saveToolResultMessage(eq(scenario), eq(42L), row.capture());
        System.out.println("PROBE " + scenario + " states=" + states + " row=" + row.getValue()
                + " modelToolMessage=" + fake.toolText + " auditRecords=" + mockingDetails(audit).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("record")).map(i -> i.getArgument(0).toString()).toList());
        if (!states.equals(List.of("running", expected)) || !row.getValue().contains(expected) || fake.toolText == null
                || result.stream().anyMatch(x -> x.getResources() != null && !x.getResources().isEmpty())) {
            throw new AssertionError("unexpected tool contract for " + scenario);
        }
    }

    static void checkTimeoutAndCancellationTransitions() {
        var audit = new ArrayList<ToolStepSink.ToolAuditRecord>();
        var sink = new ToolStepSink("test", 42L, audit::add);
        var events = new ArrayList<String>();
        var stream = sink.events().asFlux().doOnNext(d -> events.add(d.getToolStatus() + "/" + d.getErrorCategory()));
        var subscription = stream.subscribe();
        sink.started("timeout", "searchDocuments", "", "");
        sink.finished("timeout", "searchDocuments", false, ToolStepContract.ErrorCategory.TIMEOUT);
        sink.started("cancel", "searchDocuments", "", "");
        subscription.dispose();
        sink.closePending();
        System.out.println("PROBE timeout-and-cancel events=" + events + " audit=" + audit);
        if (!events.contains("failed/TIMEOUT") || events.stream().anyMatch(s -> s.startsWith("cancelled"))
                || audit.stream().noneMatch(x -> x.status() == ToolStepContract.Status.CANCELLED)) {
            throw new AssertionError("expected reproduction did not occur");
        }
    }

    static final class FixtureModel implements ChatModel {
        private final AtomicInteger calls = new AtomicInteger();
        private String toolText;
        private final String toolName;
        private final String arguments;
        FixtureModel(String toolName, String arguments) {
            this.toolName = toolName;
            this.arguments = arguments;
        }
        public ChatResponse call(Prompt prompt) {
            prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast).flatMap(m -> m.getResponses().stream())
                    .forEach(r -> toolText = r.responseData());
            if (calls.incrementAndGet() == 1) {
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("probe-1", "function", toolName, arguments))).build())));
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("fixture complete"))));
        }
        public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(call(prompt)); }
    }
}

