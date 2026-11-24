package com.csust.eyediagnosis.miniapp;

import com.csust.eyediagnosis.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WeChatCode2SessionClientTest {

    private WeChatMiniAppProperties properties;

    @BeforeEach
    void setUp() {
        properties = new WeChatMiniAppProperties();
        properties.setAppid("wx-app");
        properties.setSecret("secret");
    }

    /** 每个用例一套独立的 RestClient.Builder + MockRestServiceServer，避免互相消耗期望。 */
    private WeChatCode2SessionClient newClient(MockRestServiceServer[] serverHolder) {
        RestClient.Builder builder = RestClient.builder();
        serverHolder[0] = MockRestServiceServer.bindTo(builder).build();
        return new WeChatCode2SessionClient(builder.build(), properties);
    }

    private WeChatCode2SessionClient expectWeChatReply(MockRestServiceServer[] holder, String body) {
        WeChatCode2SessionClient client = newClient(holder);
        holder[0].expect(requestTo(containsString("/sns/jscode2session")))
                .andExpect(method(GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        return client;
    }

    @Test
    void exchangesCodeForOpenId() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        WeChatCode2SessionClient client = newClient(holder);
        holder[0].expect(requestTo(containsString("js_code=the-code")))
                .andExpect(method(GET))
                .andRespond(withSuccess(
                        "{\"openid\":\"o-1\",\"session_key\":\"sk\",\"unionid\":\"u-1\"}",
                        MediaType.APPLICATION_JSON));

        WeChatCode2SessionClient.WeChatSession session = client.exchange("the-code");

        assertEquals("o-1", session.openid());
        assertEquals("u-1", session.unionid());
        holder[0].verify();
    }

    @Test
    void successfulLoginGeneratesNoTokenBytes() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        WeChatCode2SessionClient client = expectWeChatReply(holder, "{\"openid\":\"o-2\"}");

        WeChatCode2SessionClient.WeChatSession session = client.exchange("code");

        assertEquals("o-2", session.openid());
        assertEquals(null, session.unionid());
    }

    @Test
    void invalidCodeMapsToParamsError() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        WeChatCode2SessionClient client = expectWeChatReply(holder,
                "{\"errcode\":40029,\"errmsg\":\"invalid code\"}");

        assertEquals(40000, assertThrows(BusinessException.class, () -> client.exchange("bad")).getCode());
    }

    @Test
    void reusedCodeMapsToParamsError() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        WeChatCode2SessionClient client = expectWeChatReply(holder,
                "{\"errcode\":40163,\"errmsg\":\"code been used\"}");

        assertEquals(40000, assertThrows(BusinessException.class, () -> client.exchange("used")).getCode());
    }

    @Test
    void rateLimitMapsToForbidden() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        WeChatCode2SessionClient client = expectWeChatReply(holder,
                "{\"errcode\":45011,\"errmsg\":\"freq limit\"}");

        assertEquals(40300, assertThrows(BusinessException.class, () -> client.exchange("code")).getCode());
    }

    @Test
    void riskyAccountMapsToForbidden() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        WeChatCode2SessionClient client = expectWeChatReply(holder,
                "{\"errcode\":40226,\"errmsg\":\"risky user\"}");

        assertEquals(40300, assertThrows(BusinessException.class, () -> client.exchange("code")).getCode());
    }

    @Test
    void unknownErrorCodeMapsToSystemError() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        WeChatCode2SessionClient client = expectWeChatReply(holder,
                "{\"errcode\":-1,\"errmsg\":\"system busy\"}");

        assertEquals(50000, assertThrows(BusinessException.class, () -> client.exchange("code")).getCode());
    }

    @Test
    void httpFailureMapsToSystemError() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        WeChatCode2SessionClient client = newClient(holder);
        holder[0].expect(requestTo(containsString("/sns/jscode2session"))).andRespond(withServerError());

        assertEquals(50000, assertThrows(BusinessException.class, () -> client.exchange("code")).getCode());
    }

    @Test
    void missingConfigurationFailsFast() {
        WeChatCode2SessionClient unconfiguredClient =
                new WeChatCode2SessionClient(RestClient.create(), new WeChatMiniAppProperties());

        BusinessException error = assertThrows(BusinessException.class, () -> unconfiguredClient.exchange("code"));
        assertEquals(50000, error.getCode());
        assertEquals("小程序登录未配置", error.getMessage());
    }
}
