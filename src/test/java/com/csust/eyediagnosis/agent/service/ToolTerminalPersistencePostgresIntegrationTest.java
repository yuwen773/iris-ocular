package com.csust.eyediagnosis.agent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.csust.eyediagnosis.agent.ToolStepContract;
import com.csust.eyediagnosis.integration.LegacyGateSupport;
import com.csust.eyediagnosis.agent.ToolStepSink;
import com.csust.eyediagnosis.agent.config.AgentConstants;
import com.csust.eyediagnosis.agent.queue.SessionMessageQueue;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.mapper.AgentMessageMapper;
import com.csust.eyediagnosis.mapper.AgentSessionMapper;
import com.csust.eyediagnosis.mapper.AgentToolAuditMapper;
import com.csust.eyediagnosis.model.dto.agent.ChatHistoryVO;
import com.csust.eyediagnosis.model.dto.agent.MessageDTO;
import com.csust.eyediagnosis.model.dto.agent.MessageVO;
import com.csust.eyediagnosis.utils.JsonUtils;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 PostgreSQL、真实 mapper、Spring 事务代理、队列、sink 与历史。只用合成数据。
 *
 * <p>故障注入原先用 MySQL 的 {@code SIGNAL SQLSTATE} 触发器；迁移后改用
 * {@link LegacyGateSupport#createFailTriggerWhen} 生成 plpgsql 版本，条件仍是
 * 「只让 {@code subType = 'tool-result'} 的插入失败」——断言一字未改。
 *
 * <p>库由 {@code BUSINESS_TEST_URL} 指定（库名必须叫 {@code business_test}）。
 */
class ToolTerminalPersistencePostgresIntegrationTest {
    @Test
    void failedCommitIsVisibleAndHistoryRecoversTheOriginalTerminalAfterDisconnect() throws Exception {
        String url = LegacyGateSupport.assumeIsolated(System.getenv("BUSINESS_TEST_URL"), "business_test");
        javax.sql.DataSource dataSource = LegacyGateSupport.dataSource(url);
        LegacyGateSupport.ensureSchema(url, dataSource, List.of(), () -> {
            throw new IllegalStateException("BUSINESS_TEST_URL must point at PostgreSQL");
        });
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM t_agent_message");
        jdbc.update("DELETE FROM t_agent_tool_audit");
        jdbc.update("DELETE FROM t_agent_session");
        SessionMessageQueue queue = new SessionMessageQueue(2);
        try {
            MybatisConfiguration config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(false);
            config.addMapper(AgentSessionMapper.class);
            config.addMapper(AgentMessageMapper.class);
            config.addMapper(AgentToolAuditMapper.class);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(config);
            SqlSessionTemplate mappers = new SqlSessionTemplate(factory.getObject());
            var sessions = mappers.getMapper(AgentSessionMapper.class);
            var messages = mappers.getMapper(AgentMessageMapper.class);
            ChatMessageWriter target = new ChatMessageWriter(sessions, messages, new AgentConstants(), new com.csust.eyediagnosis.common.InsertRaceGuard(null));
            ProxyFactory proxy = new ProxyFactory(target);
            proxy.setProxyTargetClass(true);
            proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                    new AnnotationTransactionAttributeSource()));
            ChatMessageWriter writer = (ChatMessageWriter) proxy.getProxy();
            ChatHistoryService history = new ChatHistoryService(sessions, messages,
                    org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build(), queue, writer);
            ToolAuditService audit = new ToolAuditService(mappers.getMapper(AgentToolAuditMapper.class), sessions);
            ReflectionTestUtils.setField(audit, "enabled", true);

            writer.claimOrValidateSession("failure", 42L, "synthetic");
            var frames = new ArrayList<MessageDTO>();
            AtomicReference<Throwable> streamError = new AtomicReference<>();
            ToolStepSink sink = sink("failure", history, audit);
            sink.events().asFlux().subscribe(frames::add, streamError::set);
            sink.started("call-failed", "searchDocuments", "digest", "query");
            LegacyGateSupport.createFailTriggerWhen(jdbc, "fail_terminal", "INSERT", "t_agent_message",
                    "subType", "tool-result", "synthetic terminal write failure");
            sink.finished("call-failed", "searchDocuments", false, ToolStepContract.ErrorCategory.TIMEOUT);
            assertNotNull(streamError.get(), "failed commit must reach SSE as an error");
            assertEquals(1, frames.size(), "only RUNNING, no uncommitted terminal frame");
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_agent_message", Integer.class));
            // 恢复失败时历史仍然要能读：读得到多少给多少，但必须**标出它不完整**，
            // 不能把陈旧历史冒充完整历史，更不能让整个会话 500。
            var incomplete = history.loadHistory("failure", null, null, 100);
            assertTrue(incomplete.isUncommittedTerminal(), "recovery failed: history must be marked incomplete");
            assertEquals(0, incomplete.getMessages().size());
            assertEquals("TIMEOUT", jdbc.queryForObject("SELECT errorCategory FROM t_agent_tool_audit "
                    + "WHERE sessionId='failure' AND toolStatus='failed'", String.class));
            LegacyGateSupport.dropFailTrigger(jdbc, "fail_terminal", "t_agent_message");
            var recovered = history.loadHistory("failure", null, null, 100);
            assertEquals(1, recovered.getMessages().size());
            assertTrue(recovered.getMessages().getFirst().getContent().contains("TIMEOUT"));
            sink.finished("call-failed", "searchDocuments", true, ToolStepContract.ErrorCategory.NONE);
            sink.closePending();
            assertEquals(1, history.loadHistory("failure", null, null, 100).getMessages().size(), "late success cannot replace TIMEOUT");

            for (ToolStepContract.ErrorCategory category : new ToolStepContract.ErrorCategory[]{
                    ToolStepContract.ErrorCategory.TIMEOUT, ToolStepContract.ErrorCategory.CANCELLED,
                    ToolStepContract.ErrorCategory.TOOL_EXCEPTION, ToolStepContract.ErrorCategory.INVALID_OUTPUT}) {
                String session = category.name();
                writer.claimOrValidateSession(session, 42L, "synthetic");
                ToolStepSink disconnected = sink(session, history, audit);
                disconnected.events().asFlux().subscribe().dispose();
                disconnected.started("disconnected", "searchDocuments", "digest", "query");
                disconnected.finished("disconnected", "searchDocuments", false, category);
                disconnected.closePending();
                var replay = history.loadHistory(session, null, null, 100).getMessages();
                assertEquals(1, replay.size());
                assertTrue(replay.getFirst().getContent().contains(category.name()));
            }
            writer.claimOrValidateSession("cancel", 42L, "synthetic");
            ToolStepSink cancelled = sink("cancel", history, audit);
            cancelled.events().asFlux().subscribe().dispose();
            cancelled.started("late", "searchDocuments", "digest", "query");
            cancelled.closePending();
            cancelled.finished("late", "searchDocuments", true, ToolStepContract.ErrorCategory.NONE);
            assertTrue(history.loadHistory("cancel", null, null, 100).getMessages().getFirst().getContent().contains("cancelled"));
            writer.claimOrValidateSession("success", 42L, "synthetic");
            ToolStepSink success = sink("success", history, audit);
            var successfulFrames = new ArrayList<MessageDTO>();
            success.events().asFlux().subscribe(successfulFrames::add);
            success.started("positive", "searchDocuments", "digest", "query");
            success.finished("positive", "searchDocuments", true, ToolStepContract.ErrorCategory.NONE);
            assertEquals("succeeded", successfulFrames.getLast().getToolStatus());
            assertTrue(history.loadHistory("success", null, null, 100).getMessages().getFirst().getContent().contains("succeeded"));

            writer.claimOrValidateSession("ack-loss", 42L, "synthetic");
            long stableId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
            AtomicInteger attempts = new AtomicInteger();
            assertThrows(IllegalStateException.class, () -> queue.submitRecoverableAndWait("ack-loss", "fixed", () -> {
                writer.writeAssistantMessage("ack-loss", "synthetic committed result", MessagePart.TOOL_RESULT, stableId);
                if (attempts.getAndIncrement() == 0) throw new IllegalStateException("synthetic acknowledgement loss");
                return null;
            }));
            assertEquals(1, history.loadHistory("ack-loss", null, null, 100).getMessages().size());
            assertEquals(1, jdbc.queryForObject("SELECT messageCount FROM t_agent_session WHERE sessionId='ack-loss'", Integer.class));
            assertEquals(2, attempts.get(), "one initial attempt and one explicit read recovery");

            verifyPoisonedSessionSurvives(history, writer, jdbc);

            verifyBudgetTurn(history, audit, writer, jdbc);

            verifyVariantHistory(history, writer, jdbc);
            verifyHistoryPagination(history, writer, jdbc);

            verifyLegacyTurnAdoption(history, writer);
        } finally {
            queue.shutdown();
            jdbc.update("DELETE FROM t_agent_message");
            jdbc.update("DELETE FROM t_agent_tool_audit");
            jdbc.update("DELETE FROM t_agent_session");
        }
    }

    /**
     * 一个永久失败的写不能毒死整个会话。
     *
     * <p>真实路径：MySQL 触发器让 tool-result 插入必然失败 → 初次写失败 →
     * 两次历史读取各触发一次恢复重试，三次失败后该写被淘汰并记账 →
     * 之后的消息照常落库 → 历史读得到后来的消息，
     * 并且**明确标出**有一条没提交成功。
     */
    private static void verifyPoisonedSessionSurvives(ChatHistoryService history, ChatMessageWriter writer,
                                                       JdbcTemplate jdbc) {
        writer.claimOrValidateSession("poisoned", 42L, "synthetic");
        LegacyGateSupport.createFailTriggerWhen(jdbc, "fail_poison", "INSERT", "t_agent_message",
                "subType", "tool-result", "synthetic permanent failure");
        try {
            assertThrows(RuntimeException.class,
                    () -> history.saveAssistantMessage("poisoned", "{\"output\":\"poison\"}", MessagePart.TOOL_RESULT),
                    "a failing terminal write must surface to the caller");
            // 两次读取各触发一次恢复重试：第三次失败后这个键被淘汰，不再堵队列。
            history.loadHistory("poisoned", null, null, 100);
            history.loadHistory("poisoned", null, null, 100);
            // 之后的写照常落库：失败的那一条被淘汰，不再挡住后面的消息。
            history.saveAssistantMessage("poisoned", "later message", MessagePart.ANSWER);
            var after = history.loadHistory("poisoned", null, null, 100);
            assertTrue(after.getMessages().stream().anyMatch(message -> "later message".equals(message.getContent())),
                    "a permanently failing write must not block later messages");
            assertTrue(after.isUncommittedTerminal(), "the dropped write must be reported as uncommitted");
        } finally {
            LegacyGateSupport.dropFailTrigger(jdbc, "fail_poison", "t_agent_message");
        }
    }

    private static void verifyBudgetTurn(ChatHistoryService history, ToolAuditService audit,
                                         ChatMessageWriter writer, JdbcTemplate jdbc) {
        writer.claimOrValidateSession("budget-preamble", 42L, "synthetic");
        AtomicInteger calls = new AtomicInteger();
        org.springframework.ai.chat.model.ChatModel model = new org.springframework.ai.chat.model.ChatModel() {
            @Override public org.springframework.ai.chat.model.ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
                throw new UnsupportedOperationException();
            }
            @Override public reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse> stream(
                    org.springframework.ai.chat.prompt.Prompt prompt) {
                calls.incrementAndGet();
                var preamble = new org.springframework.ai.chat.messages.AssistantMessage("我先查询资料。");
                var toolCall = org.springframework.ai.chat.messages.AssistantMessage.builder().content("")
                        .toolCalls(java.util.List.of(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                                "budget-tool", "function", "searchDocuments", "{\"query\":\"synthetic\"}"))).build();
                return reactor.core.publisher.Flux.just(preamble, toolCall)
                        .map(message -> new org.springframework.ai.chat.model.ChatResponse(java.util.List.of(
                                new org.springframework.ai.chat.model.Generation(message))));
            }
        };
        var mcp = org.mockito.Mockito.mock(com.csust.eyediagnosis.agent.interceptor.TextMcpModelInterceptor.class);
        org.mockito.Mockito.when(mcp.resolveMcpCallbacks(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new org.springframework.ai.tool.ToolCallback[0]);
        var documents = org.mockito.Mockito.mock(com.csust.eyediagnosis.agent.tools.DocumentSearchTool.class);
        org.mockito.Mockito.when(documents.searchDocuments(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn("[]");
        var constants = new AgentConstants();
        constants.setModelCallLimit(1);
        constants.setMaxIterations(10);
        var agent = new com.csust.eyediagnosis.agent.DiagnosisAgent();
        ReflectionTestUtils.setField(agent, "chatModel", model);
        ReflectionTestUtils.setField(agent, "chatHistoryService", history);
        ReflectionTestUtils.setField(agent, "toolAuditService", audit);
        ReflectionTestUtils.setField(agent, "textMcpModelInterceptor", mcp);
        ReflectionTestUtils.setField(agent, "agentConstants", constants);
        ReflectionTestUtils.setField(agent, "documentSearchTool", documents);
        ReflectionTestUtils.setField(agent, "eyeImageAnalyzerTool",
                org.mockito.Mockito.mock(com.csust.eyediagnosis.agent.tools.EyeImageAnalyzerTool.class));
        var events = agent.stream("synthetic", null, "budget-preamble", 42L, null, null)
                .collectList().block(java.time.Duration.ofSeconds(30));
        assertNotNull(events);
        assertEquals(1, calls.get());
        var terminal = events.stream().filter(event -> "MODEL_BUDGET_EXHAUSTED".equals(event.getFinishReason()))
                .findFirst().orElseThrow();
        var replay = history.loadHistory("budget-preamble", null, null, 100).getMessages();
        var notice = replay.stream().filter(message -> "turn-end".equals(message.getSubType())).findFirst().orElseThrow();
        assertEquals(terminal.getContent(), notice.getContent());
        assertEquals(terminal.getFinishReason(), notice.getFinishReason());
        assertTrue(replay.stream().anyMatch(message -> "我先查询资料。".equals(message.getContent())));
        assertTrue(jdbc.queryForObject("SELECT content FROM t_agent_message WHERE sessionId='budget-preamble' "
                + "AND subType='turn-end'", String.class).contains("MODEL_BUDGET_EXHAUSTED"));
    }

    private static void verifyHistoryPagination(ChatHistoryService history, ChatMessageWriter writer,
                                                JdbcTemplate jdbc) {
        String session = "history-pagination";
        String otherSession = "history-pagination-other";
        long first = 2_120_565_650_562_129_921L;
        writer.claimOrValidateSession(session, 42L, "synthetic");
        writer.claimOrValidateSession(otherSession, 42L, "synthetic");
        for (long id = first; id < first + 81; id++) {
            writer.writeUserMessage(session, 42L, "synthetic", null, id);
        }
        writer.writeUserMessage(otherSession, 42L, "different session", null, first + 100);

        var latest = history.loadHistory(session, null, null, 40);
        assertPageIds(latest, first + 41, first + 81);
        assertTrue(latest.isHasMore());
        assertEquals(first + 41, latest.getNextCursor());
        assertFalse(latest.isHasNewer());
        assertEquals(first + 80, latest.getNewerCursor());

        writer.writeUserMessage(session, 42L, "appended", null, first + 81);
        var middle = history.loadHistory(session, latest.getNextCursor(), null, 40);
        assertPageIds(middle, first + 1, first + 41);
        assertTrue(middle.isHasNewer());
        assertEquals(first + 40, middle.getNewerCursor());
        assertEquals(middle, history.loadHistory(session, latest.getNextCursor(), null, 40));

        var oldest = history.loadHistory(session, middle.getNextCursor(), null, 40);
        assertPageIds(oldest, first, first + 1);
        assertFalse(oldest.isHasMore());
        assertNull(oldest.getNextCursor());

        var forward = history.loadHistory(session, null, middle.getNewerCursor(), 40);
        assertPageIds(forward, first + 41, first + 81);
        assertTrue(forward.isHasNewer());
        assertEquals(first + 80, forward.getNewerCursor());
        var appended = history.loadHistory(session, null, forward.getNewerCursor(), 40);
        assertPageIds(appended, first + 81, first + 82);
        assertFalse(appended.isHasNewer());
        assertTrue(history.loadHistory(session, null, appended.getNewerCursor(), 40).getMessages().isEmpty());
        assertFalse(history.loadHistory(session, latest.getNextCursor(), null, 41).isHasMore(),
                "an exact full page with no earlier rows has no next cursor");

        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), assertThrows(BusinessException.class,
                () -> history.loadHistory(otherSession, latest.getNextCursor(), null, 40)).getCode());
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), assertThrows(BusinessException.class,
                () -> history.loadHistory(session, null, first + 200, 40)).getCode());
        jdbc.update("DELETE FROM t_agent_message WHERE id = ? AND sessionId = ?", latest.getNextCursor(), session);
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), assertThrows(BusinessException.class,
                () -> history.loadHistory(session, latest.getNextCursor(), null, 40)).getCode());
    }

    private static void assertPageIds(ChatHistoryVO page, long start, long end) {
        assertEquals(LongStream.range(start, end).boxed().toList(),
                page.getMessages().stream().map(MessageVO::getId).toList());
    }

    /**
     * 多版本回答的真实 SQL 语义（Mockito 测不到 JOIN 与 GROUP BY 的行为）。
     *
     * <p>版本过滤必须在 SQL 里：先取页再在内存过滤会让未显示的旧版本占满
     * LIMIT，翻页出现空页。这里直接打真库，验证三种状态：未选择时全部可见、
     * 选中后只剩该版本、版本列表按生成顺序返回。
     */
    private static void verifyVariantHistory(ChatHistoryService history, ChatMessageWriter writer,
                                             JdbcTemplate jdbc) {
        writer.claimOrValidateSession("variants", 42L, "synthetic");
        long userRow = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        writer.writeUserMessage("variants", 42L, "同一问题的原文", null, userRow);
        writer.writeAssistantMessage("variants", "第一版回答", MessagePart.ANSWER,
                com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), userRow, "v1");
        writer.writeAssistantMessage("variants", "第二版回答", MessagePart.ANSWER,
                com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), userRow, "v2");
        writer.writeAssistantMessage("variants", "第二版推理", MessagePart.REASONING,
                com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), userRow, "v2");

        // 还没选中：两版都返回，用户行带上按生成顺序排列的版本列表
        var all = history.loadHistory("variants", null, null, 100).getMessages();
        assertTrue(all.stream().anyMatch(m -> "第一版回答".equals(m.getContent())));
        assertTrue(all.stream().anyMatch(m -> "第二版回答".equals(m.getContent())));
        var userBefore = all.stream().filter(m -> "USER".equals(m.getRole())).findFirst().orElseThrow();
        assertEquals(java.util.List.of("v1", "v2"), userBefore.getVariantIds());
        assertNull(userBefore.getSelectedVariantId());

        // 选中 v2：SQL 过滤掉 v1 的行，选中状态回到用户行
        history.selectVariant("variants", 42L, userRow, "v2");
        var selected = history.loadHistory("variants", null, null, 100).getMessages();
        assertTrue(selected.stream().noneMatch(m -> "第一版回答".equals(m.getContent())),
                "未选中的版本不能出现在历史里");
        assertTrue(selected.stream().anyMatch(m -> "第二版推理".equals(m.getContent())),
                "同一版本组的所有行一起保留");
        var userAfter = selected.stream().filter(m -> "USER".equals(m.getRole())).findFirst().orElseThrow();
        assertEquals("v2", userAfter.getSelectedVariantId());

        // 游标分页与版本过滤一致：小页也不被隐藏版本占位
        var firstPage = history.loadHistory("variants", null, null, 2).getMessages();
        assertEquals(2, firstPage.size());
        assertTrue(firstPage.stream().noneMatch(m -> "第一版回答".equals(m.getContent())));

        // 版本列表与原文回放走服务层（含归属校验）
        assertEquals(new ChatHistoryService.TurnVariants(java.util.List.of("v1", "v2"), "v2"),
                history.variants("variants", 42L, userRow));
        assertEquals("同一问题的原文", history.loadUserTurn("variants", 42L, userRow).text());

        // 选一个不存在的版本必须被拒绝，而不是写进一个假状态
        assertThrows(com.csust.eyediagnosis.exception.BusinessException.class,
                () -> history.selectVariant("variants", 42L, userRow, "v9"));

        // 会话统计与「用户看得到的行」一致：隐藏的 v1 行不计入
        assertEquals(selected.size(), jdbc.queryForObject(
                "SELECT messageCount FROM t_agent_session WHERE sessionId='variants'", Integer.class),
                "messageCount 必须按可见口径重算，而不是每次重新生成都累加");
        assertEquals("第二版推理", jdbc.queryForObject(
                "SELECT lastMessage FROM t_agent_session WHERE sessionId='variants'", String.class),
                "lastMessage 跟随可见的最后一行，不被隐藏版本覆盖");
    }

    /**
     * 升级前的回答（replyToId/variantId 为 NULL）在重新生成前被归入一个旧版本：
     * 否则新版本写入后旧行永远匹配不上选中状态——隐藏不掉，切换器还少算一版。
     */
    private static void verifyLegacyTurnAdoption(ChatHistoryService history, ChatMessageWriter writer) {
        writer.claimOrValidateSession("legacy", 42L, "synthetic");
        long userRow = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        writer.writeUserMessage("legacy", 42L, "升级前的问题", null, userRow);
        // 旧口径：不带 replyToId / variantId
        writer.writeAssistantMessage("legacy", "升级前的回答", MessagePart.ANSWER,
                com.baomidou.mybatisplus.core.toolkit.IdWorker.getId());
        // 另一个回合，不能被一并收养
        long nextUser = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        writer.writeUserMessage("legacy", 42L, "下一轮问题", null, nextUser);
        writer.writeAssistantMessage("legacy", "下一轮的回答", MessagePart.ANSWER,
                com.baomidou.mybatisplus.core.toolkit.IdWorker.getId());

        assertEquals("legacy-" + userRow, history.adoptLegacyAnswers("legacy", 42L, userRow));
        assertEquals(java.util.List.of("legacy-" + userRow),
                history.variants("legacy", 42L, userRow).variantIds());
        assertTrue(history.variants("legacy", 42L, nextUser).variantIds().isEmpty(),
                "只收养这一轮的回答，下一轮的旧行原样保留");

        // 新版本写入后选中它：旧回答隐藏，版本列表两版
        writer.writeAssistantMessage("legacy", "重新生成的回答", MessagePart.ANSWER,
                com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), userRow, "v2");
        history.selectVariant("legacy", 42L, userRow, "v2");
        var page = history.loadHistory("legacy", null, null, 100).getMessages();
        assertTrue(page.stream().noneMatch(m -> "升级前的回答".equals(m.getContent())),
                "收养后旧回答可以被隐藏");
        assertTrue(page.stream().anyMatch(m -> "重新生成的回答".equals(m.getContent())));
        assertEquals(java.util.List.of("legacy-" + userRow, "v2"),
                history.variants("legacy", 42L, userRow).variantIds());
    }

    private static ToolStepSink sink(String session, ChatHistoryService history, ToolAuditService audit) {
        return new ToolStepSink(session, 42L, audit::record, dto -> history.saveAssistantMessage(session,
                JsonUtils.toJson(Map.of("toolCallId", dto.getToolCallId(), "name", dto.getToolName(),
                        "status", dto.getToolStatus(), "errorCategory", dto.getErrorCategory(), "output", dto.getContent())),
                MessagePart.TOOL_RESULT));
    }
}
