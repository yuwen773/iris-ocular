package com.csust.eyediagnosis.controlcenter;

import com.csust.eyediagnosis.security.PasswordService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 默认关闭状态（WP-01B 阶段 3 要求）。
 *
 * <p>{@code eye-diagnosis.control-center.enabled} 默认 false：控制中心的端点
 * 不存在、CSRF 过滤器不注册、严格 Origin 分支不生效。全站行为必须与引入
 * 配置中心之前一致——这条回归用例就是为了防止「默认开」或「关了还在拦」。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ControlCenterDisabledAcceptanceTest {

    private static final String PASSWORD = "Passw0rd!2026";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordService passwords;

    private final String suffix = Long.toString(System.nanoTime(), 36);
    private String base;
    private long adminId;
    private HttpClient client;
    private String cookie;

    @BeforeAll
    void setUp() throws Exception {
        base = "http://127.0.0.1:" + port;
        String account = "cc-off-admin-" + suffix;
        jdbc.update("INSERT INTO t_user (userAccount,userPassword,userName,userRole) VALUES (?,?,?,?)",
                account, passwords.encode(PASSWORD), account, "admin");
        adminId = jdbc.queryForObject("SELECT id FROM t_user WHERE userAccount=?", Long.class, account);

        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> login = client.send(HttpRequest.newBuilder(URI.create(base + "/api/user/login"))
                .header("Content-Type", "application/json")
                .header("Origin", base)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"userAccount\":\"" + account + "\",\"userPassword\":\"" + PASSWORD + "\"}",
                        StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
        cookie = login.headers().firstValue("set-cookie").orElse("");
        assertEquals(200, login.statusCode(), login.body());
    }

    @AfterAll
    void tearDown() {
        jdbc.update("DELETE FROM t_user WHERE id = ?", adminId);
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Origin", base)
                .header("Content-Type", "application/json");
        if (!cookie.isBlank()) builder.header("Cookie", cookie.split(";")[0]);
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return client.send(builder.timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void everyControlCenterEndpointIsAbsent() throws Exception {
        // 端点不存在时走的是应用既有的兜底异常处理（HTTP 200 + 50000），
        // 不是控制中心的信封：关键断言是「不是 40100 / 40301 / 40300」。
        for (String path : new String[]{
                "/api/admin/control-center/registry",
                "/api/admin/control-center/active",
                "/api/admin/control-center/csrf"}) {
            HttpResponse<String> reply = send("GET", path, null);
            assertEquals(50000, codeOf(reply.body()), path + " 不应存在，实际: " + reply.body());
        }
    }

    @Test
    void writeIsNotInterceptedByCsrfWhenDisabled() throws Exception {
        // 关闭时没有 CSRF 过滤器：请求不会被我方过滤器拦成 403。
        // 断言「这次请求没写进任何行」，而不是「全库这张表一直为空」——后者把别的
        // 用例的残留也算进来，整套跑时的执行顺序一变就假红（2026-11-24 CI 实测：
        // 单跑本包 66 例全绿，整套里同一条断言 expected 0 but was 1）。
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM t_config_draft", Integer.class);
        HttpResponse<String> reply = send("POST", "/api/admin/control-center/drafts",
                "{\"schemaVersion\":1,\"values\":{}}");
        assertNotEquals(403, reply.statusCode(), reply.body());
        assertEquals(50000, codeOf(reply.body()), reply.body());
        Integer after = jdbc.queryForObject("SELECT COUNT(*) FROM t_config_draft", Integer.class);
        assertEquals(before, after, "关闭时这次 POST 不应写入任何草稿行");
    }

    private int codeOf(String body) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("code").asInt(-1);
        } catch (Exception e) {
            return -1;
        }
    }

    @Test
    void existingEndpointsStillWorkWithTheGlobalOriginFilter() throws Exception {
        // 回归：全局 SameOriginFilter 仍在，普通接口不受控制中心开关影响。
        HttpResponse<String> reply = send("GET", "/api/user/get/login", null);
        assertEquals(200, reply.statusCode(), reply.body());
    }
}
