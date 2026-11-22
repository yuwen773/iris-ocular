package com.csust.eyediagnosis.agent.service;

/**
 * 回答侧 / 附件消息在 {@code t_agent_message.subType} 列里的取值——**落库词汇表**。
 *
 * <p>这是「写进数据库、由历史回放读回来的」那一套词，和 SSE 推送用的
 * {@link com.csust.eyediagnosis.model.dto.agent.MessageType} 不是同一套：
 * 例如中断确认卡落库是 {@code mcp-confirm}，推送事件是 {@code tool-confirm}；
 * 最终回答落库是 {@code text}，推送类型是 {@code assistant}。读入写出共用本枚举，
 * 历史回放（{@code ChatHistoryService#toMessageVO}）与前端
 * {@code web/src/lib/agentStream.ts} 都按这些字符串分支。
 *
 * <p>改动任何 {@code wire} 值都会让已存在的历史行读不出来，也会静默改变前端渲染。
 */
public enum MessagePart {
    /** 模型推理过程；只有开启 {@code agent.expose-reasoning} 时才落库。 */
    REASONING("reasoning"),
    /** 工具调用请求；正文是脱敏后的工具名/参数白名单 JSON。 */
    TOOL_CALL("tool-call"),
    /** 工具执行终态；正文是 status/output/resources 负载。 */
    TOOL_RESULT("tool-result"),
    /** 工具中断确认卡；正文是固定白名单标签。 */
    TOOL_CONFIRM("mcp-confirm"),
    /** 最终回答正文。 */
    ANSWER("text"),
    /** 回合终止说明（空回答 / 预算耗尽）；正文是 output + finishReason 负载。 */
    TURN_END("turn-end"),
    /** 带附件的用户消息；正文是 text + images 信封，身份只由这个 subType 授予。 */
    USER_ATTACHMENTS("user-attachments-v1");

    private final String wire;

    MessagePart(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
