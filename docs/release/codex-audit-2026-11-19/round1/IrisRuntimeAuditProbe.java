package com.csust.eyediagnosis.agent;

import com.csust.eyediagnosis.agent.config.AgentConstants;
import com.csust.eyediagnosis.agent.exception.McpTimeoutException;
import com.csust.eyediagnosis.agent.manager.MCPManager;
import com.csust.eyediagnosis.agent.mcp.McpToolCallback;
import com.csust.eyediagnosis.agent.service.ToolAuditService;
import com.csust.eyediagnosis.agent.tools.*;
import com.csust.eyediagnosis.model.dto.mcp.*;
import com.csust.eyediagnosis.model.entity.McpServer;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import static org.mockito.Mockito.*;

class IrisRuntimeAuditProbe {
    public static void main(String[] args) throws Exception {
        run("structured-error", 1, 5, 1, new McpServerCallToolResponse(true, List.of(new McpServerCallToolResponse.TextContent("synthetic error"))), false);
        run("transport-timeout", 1, 5, 1, null, true);
        run("budget-limit", 1, 5, 2, new McpServerCallToolResponse(false, List.of(new McpServerCallToolResponse.TextContent("fixture ok"))), false);
        run("model-limit-one", 8, 1, 99, new McpServerCallToolResponse(false, List.of(new McpServerCallToolResponse.TextContent("fixture ok"))), false);
        run("model-limit-default", 10, 15, 99, new McpServerCallToolResponse(false, List.of(new McpServerCallToolResponse.TextContent("fixture ok"))), false);
    }

    static void run(String scenario, int toolLimit, int modelLimit, int rounds, McpServerCallToolResponse result, boolean timeout) throws Exception {
        var manager = mock(MCPManager.class);
        var providerCalls = new AtomicInteger();
        when(manager.callTool(any(), any())).thenAnswer(call -> {
            providerCalls.incrementAndGet();
            if (timeout) throw new McpTimeoutException("synthetic timeout");
            return result;
        });
        var server = new McpServer();
        server.setMcpServerCode("fixture");
        var tool = new McpTool();
        tool.setName("fixture_tool");
        tool.setDescription("synthetic fixture");
        tool.setInputSchema(new InputSchema());
        var callback = new McpToolCallback(manager, server, tool);
        var configuration = new AgentConstants();
        configuration.setMaxIterations(toolLimit);
        configuration.setModelCallLimit(modelLimit);
        var observed = new ArrayList<ToolStepSink.ToolAuditRecord>();
        var sink = new ToolStepSink(scenario, 42L, observed::add);
        var modelCalls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) {
                int i = modelCalls.incrementAndGet();
                AssistantMessage msg = i <= rounds
                    ? AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("call-" + i,"function","fixture_tool","{}"))).build()
                    : new AssistantMessage("synthetic finish");
                return new ChatResponse(List.of(new Generation(msg)));
            }
            public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(call(prompt)); }
        };
        var audit = mock(ToolAuditService.class);
        when(audit.digestKey()).thenReturn(new byte[]{1,2,3});
        var agent = new DiagnosisAgent();
        ReflectionTestUtils.setField(agent, "chatModel", model);
        ReflectionTestUtils.setField(agent, "agentConstants", configuration);
        ReflectionTestUtils.setField(agent, "toolAuditService", audit);
        ReflectionTestUtils.setField(agent, "eyeImageAnalyzerTool", mock(EyeImageAnalyzerTool.class));
        ReflectionTestUtils.setField(agent, "documentSearchTool", new DocumentSearchTool());
        agent.buildAgent(new ToolCallback[]{callback}, 42L, sink).call("synthetic fixture");
        System.out.println("PROBE " + scenario + " modelCalls=" + modelCalls + " providerCalls=" + providerCalls
            + " terminal=" + observed.stream().filter(x -> x.status().terminal()).map(x -> x.status() + "/" + x.errorCategory()).toList());
        if (scenario.startsWith("model-limit-")) {
            var mcp = mock(com.csust.eyediagnosis.agent.interceptor.TextMcpModelInterceptor.class);
            when(mcp.resolveMcpCallbacks(any(), any())).thenReturn(new ToolCallback[]{callback});
            ReflectionTestUtils.setField(agent, "textMcpModelInterceptor", mcp);
            ReflectionTestUtils.setField(agent, "chatHistoryService",
                    mock(com.csust.eyediagnosis.agent.service.ChatHistoryService.class));
            modelCalls.set(0);
            providerCalls.set(0);
            var frames = agent.stream("synthetic", null, "fixture", 42L, null, null)
                    .collectList().block(java.time.Duration.ofSeconds(30));
            System.out.println("PROBE cap-stream completed=true modelCalls=" + modelCalls
                + " providerCalls=" + providerCalls + " frames=" + frames.stream()
                    .map(f -> f.getMessageType() + "/" + f.getToolStatus() + "/" + f.getContent()).toList());
        }
    }
}

