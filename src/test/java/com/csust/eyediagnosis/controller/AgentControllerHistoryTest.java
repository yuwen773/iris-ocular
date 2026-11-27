package com.csust.eyediagnosis.controller;

import com.csust.eyediagnosis.agent.config.AgentConstants;
import com.csust.eyediagnosis.agent.queue.SessionMessageQueue;
import com.csust.eyediagnosis.agent.service.ChatHistoryService;
import com.csust.eyediagnosis.agent.service.ChatMessageWriter;
import com.csust.eyediagnosis.config.JsonConfig;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.exception.GlobalExceptionHandler;
import com.csust.eyediagnosis.mapper.AgentMessageMapper;
import com.csust.eyediagnosis.mapper.AgentSessionMapper;
import com.csust.eyediagnosis.model.entity.AgentMessage;
import com.csust.eyediagnosis.model.entity.AgentSession;
import com.csust.eyediagnosis.model.entity.User;
import com.csust.eyediagnosis.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentControllerHistoryTest {

    private AgentMessageMapper messageMapper;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        UserService userService = mock(UserService.class);
        User user = new User();
        user.setId(7L);
        when(userService.getLoginUser(any(HttpServletRequest.class))).thenReturn(user);

        AgentSessionMapper sessionMapper = mock(AgentSessionMapper.class);
        AgentSession session = new AgentSession();
        session.setSessionId("thread-a");
        session.setUserId(7L);
        when(sessionMapper.selectBySessionId("thread-a")).thenReturn(session);
        messageMapper = mock(AgentMessageMapper.class);
        ChatMessageWriter messageWriter = new ChatMessageWriter(sessionMapper, messageMapper,
                new AgentConstants(), new com.csust.eyediagnosis.common.InsertRaceGuard(null));
        ChatHistoryService historyService = new ChatHistoryService(sessionMapper, messageMapper,
                new ObjectMapper(), mock(SessionMessageQueue.class), messageWriter);

        AgentController controller = new AgentController();
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "chatHistoryService", historyService);
        ReflectionTestUtils.setField(controller, "agentConstants", new AgentConstants());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new JsonConfig().jacksonObjectMapper(Jackson2ObjectMapperBuilder.json())))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void historyRejectsNonpositivePageSizesWithoutQueryingMessages(int limit) throws Exception {
        mockMvc.perform(get("/agent/history/thread-a").param("limit", String.valueOf(limit)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAMS_ERROR.getCode()));

        verifyNoInteractions(messageMapper);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void sessionsRejectNonpositivePageSizes(int limit) throws Exception {
        mockMvc.perform(get("/agent/sessions").param("limit", String.valueOf(limit)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAMS_ERROR.getCode()));
    }

    @ParameterizedTest
    @CsvSource({"40,40", "200,100"})
    void historyKeepsValidPageSizesAndCapsLargeRequests(int requested, int effective) throws Exception {
        mockMvc.perform(get("/agent/history/thread-a").param("limit", String.valueOf(requested)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.messages").isEmpty())
                .andExpect(jsonPath("$.data.hasMore").value(false));

        verify(messageMapper).selectHistoryPage("thread-a", null, null, effective + 1);
    }

    @Test
    void historySerializesCursorsWithoutLosingLongIdPrecision() throws Exception {
        AgentMessage oldest = new AgentMessage();
        oldest.setId(2_120_565_650_562_129_921L);
        oldest.setRole("ASSISTANT");
        AgentMessage newest = new AgentMessage();
        newest.setId(oldest.getId() + 1);
        newest.setRole("ASSISTANT");
        when(messageMapper.selectHistoryPage("thread-a", null, null, 2)).thenReturn(List.of(newest, oldest));

        mockMvc.perform(get("/agent/history/thread-a").param("limit", "1"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.messages[0].id").value(newest.getId().toString()))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.nextCursor").value(newest.getId().toString()))
                .andExpect(jsonPath("$.data.hasNewer").value(false))
                .andExpect(jsonPath("$.data.newerCursor").value(newest.getId().toString()));

        when(messageMapper.selectHistoryCursor("thread-a", oldest.getId())).thenReturn(oldest.getId());
        when(messageMapper.selectHistoryPage("thread-a", null, oldest.getId(), 2)).thenReturn(List.of(newest));
        when(messageMapper.selectHistoryPage("thread-a", newest.getId(), null, 1)).thenReturn(List.of(oldest));
        mockMvc.perform(get("/agent/history/thread-a").param("afterId", oldest.getId().toString()).param("limit", "1"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.messages[0].id").value(newest.getId().toString()))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.hasNewer").value(false));
    }

    @Test
    void historyRejectsBothCursorsAndChecksOwnerBeforeQueryingMessages() throws Exception {
        mockMvc.perform(get("/agent/history/thread-a").param("beforeId", "30").param("afterId", "10"))
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAMS_ERROR.getCode()));
        mockMvc.perform(get("/agent/history/thread-not-owned").param("beforeId", "30"))
                .andExpect(jsonPath("$.code").value(ErrorCode.NO_AUTH_ERROR.getCode()));

        verifyNoInteractions(messageMapper);
    }
}
