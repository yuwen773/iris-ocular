package com.csust.eyediagnosis.agent.service;

import com.csust.eyediagnosis.model.dto.agent.MessageType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MessagePart} 的 {@code wire} 值写进 {@code t_agent_message.subType}，
 * 之后由历史回放（{@code ChatHistoryService#toMessageVO}）和前端
 * {@code web/src/lib/agentStream.ts} 的 {@code mapHistoryMessage} 按字符串分支。
 * 这些断言把跨进程契约钉住：改一个字母，既有历史行就再也读不出原来的语义。
 */
class MessagePartTest {

    @Test
    void persistedVocabularyIsPinned() {
        Map<MessagePart, String> expected = Map.of(
                MessagePart.REASONING, "reasoning",
                MessagePart.TOOL_CALL, "tool-call",
                MessagePart.TOOL_RESULT, "tool-result",
                MessagePart.TOOL_CONFIRM, "mcp-confirm",
                MessagePart.ANSWER, "text",
                MessagePart.TURN_END, "turn-end",
                MessagePart.USER_ATTACHMENTS, "user-attachments-v1");
        assertEquals(MessagePart.values().length, expected.size(), "每个落库部件都要有断言");
        expected.forEach((part, wire) -> assertEquals(wire, part.wire(), part.name()));
    }

    @Test
    void wireValuesAreDistinct() {
        Map<String, MessagePart> seen = new HashMap<>();
        for (MessagePart part : MessagePart.values()) {
            MessagePart previous = seen.put(part.wire(), part);
            assertTrue(previous == null, part.wire() + " 同时属于 " + previous + " 与 " + part);
        }
    }

    /**
     * 落库词汇表和 SSE 事件词汇表是两套：确认卡落库 {@code mcp-confirm}、
     * 推送 {@code tool-confirm}；回答落库 {@code text}、推送 {@code assistant}。
     * 有人「顺手统一」时这条会红。
     */
    @Test
    void persistedVocabularyIsNotTheSseVocabulary() {
        assertNotEquals(MessageType.TOOL_CONFIRM.getValue(), MessagePart.TOOL_CONFIRM.wire());
        assertNotEquals(MessageType.ASSISTANT.getValue(), MessagePart.ANSWER.wire());
        assertEquals(MessageType.TOOL_RESULT.getValue(), MessagePart.TOOL_RESULT.wire(),
                "工具终态在两套词汇表里恰好同名，可以共用字面值");
    }
}
