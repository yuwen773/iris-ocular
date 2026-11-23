package com.csust.eyediagnosis.agent.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.csust.eyediagnosis.agent.ToolStepContract;
import com.csust.eyediagnosis.agent.manager.MCPManager;
import com.csust.eyediagnosis.agent.service.AgentImageReferenceResolver;
import com.csust.eyediagnosis.agent.exception.McpOverloadException;
import com.csust.eyediagnosis.agent.exception.McpTimeoutException;
import com.csust.eyediagnosis.model.dto.mcp.*;
import com.csust.eyediagnosis.model.entity.McpServer;
import com.csust.eyediagnosis.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
public class McpToolCallback implements ToolCallback {

    private final MCPManager mcpManager;
    private final McpServer mcpServer;
    private final McpTool mcpTool;

    /**
     * 图片引用翻译器；为 null 时不做翻译（工具拿到的仍是模型给的原始参数）。
     */
    private final AgentImageReferenceResolver imageReferenceResolver;

    /** 当前会话用户；图片引用必须按它的归属签发。 */
    private final Long userId;

    public McpToolCallback(MCPManager mcpManager, McpServer entity, McpTool tool,
                           AgentImageReferenceResolver imageReferenceResolver, Long userId) {
        this.mcpManager = mcpManager;
        this.mcpServer = entity;
        this.mcpTool = tool;
        this.imageReferenceResolver = imageReferenceResolver;
        this.userId = userId;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        String inputSchemaJson;
        try {
            inputSchemaJson = JsonUtils.toJson(mcpTool.getInputSchema());
        } catch (Exception e) {
            log.warn("Failed to serialize input schema", e);
            inputSchemaJson = "{}";
        }
        return ToolDefinition.builder()
            .name(mcpTool.getName())
            .description(mcpTool.getDescription())
            .inputSchema(inputSchemaJson)
            .build();
    }

    @Override
    public String call(String functionInput) {
        Map<String, Object> params = parseInput(functionInput);
        params = translateImageReferences(params);

        McpServerCallToolRequest request = new McpServerCallToolRequest();
        request.setMcpServerCode(mcpServer.getMcpServerCode());
        request.setToolName(mcpTool.getName());
        request.setToolParams(params);

        McpServerCallToolResponse response;
        try {
            response = mcpManager.callTool(request, mcpServer);
        } catch (java.util.concurrent.CancellationException e) {
            throw new IllegalStateException(
                    ToolStepContract.tagError(ToolStepContract.ErrorCategory.CANCELLED,
                            "MCP 工具调用已取消"), e);
        } catch (McpTimeoutException e) {
            // 失败类别必须活到拦截器：框架会把这里逃出的异常转成错误响应字符串，
            // 异常类型随之丢失。标记进文本，观察者才能把「超时」和普通失败分开。
            throw new IllegalStateException(
                    ToolStepContract.tagError(ToolStepContract.ErrorCategory.TIMEOUT,
                            "MCP 工具调用超时，请稍后重试"), e);
        } catch (McpOverloadException e) {
            throw new IllegalStateException(
                    ToolStepContract.tagError(ToolStepContract.ErrorCategory.TOOL_EXCEPTION,
                            "MCP 服务繁忙，请稍后重试"), e);
        }
        // MCP 的 isError 是一等信号：把它当普通文本返回，观察者会记 success，
        // 界面显示「完成」，模型也会把「401 Unauthorized」当正文依据。
        if (response != null && Boolean.TRUE.equals(response.getIsError())) {
            throw new IllegalStateException(
                    ToolStepContract.tagError(ToolStepContract.ErrorCategory.TOOL_EXCEPTION,
                            "MCP 工具返回错误：" + formatResponse(response)));
        }
        return formatResponse(response);
    }

    /**
     * 把参数里的服务端取图路径换成后端签发的短期引用。
     *
     * <p>算法侧的图片工具只接受签发过的引用；模型手里只有服务端路径，翻译
     * 放在这一跳完成，模型与算法侧都不需要知道对象存储地址。
     */
    private Map<String, Object> translateImageReferences(Map<String, Object> params) {
        if (imageReferenceResolver == null || params == null || params.isEmpty()) {
            return params;
        }
        Map<String, Object> translated = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String text) {
                translated.put(entry.getKey(), imageReferenceResolver.toReference(text, userId));
            } else {
                translated.put(entry.getKey(), value);
            }
        }
        return translated;
    }

    private Map<String, Object> parseInput(String functionInput) {
        if (functionInput == null || functionInput.isBlank()) {
            return Map.of();
        }
        try {
            return JsonUtils.fromJson(functionInput, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse mcp input: {}", functionInput);
            return Map.of();
        }
    }

    private String formatResponse(McpServerCallToolResponse response) {
        if (response.getContent() == null || response.getContent().isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder();
        for (McpServerCallToolResponse.Content c : response.getContent()) {
            if (c instanceof McpServerCallToolResponse.TextContent textContent) {
                sb.append(textContent.getText());
            }
        }
        return sb.toString();
    }
}
