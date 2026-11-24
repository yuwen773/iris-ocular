package com.csust.eyediagnosis.controlcenter;

import com.csust.eyediagnosis.security.PasswordService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发布审批的 HTTP 验收（WP-01C）。
 *
 * <p>与 {@link ControlCenterPublishIntegrationTest} 的分工：那边验证事务与并发，
 * 这边验证<b>装配</b>——发布人白名单、CSRF、认证与动作权限的顺序，以及一条完整
 * 的「草稿 → 申请 → 批准 → 生效 → 回滚」在真实过滤器链下走得通。
 *
 * <p>白名单里只放一个固定 userId 的管理员；另一个管理员虽然同样是 ADMIN，
 * 也不得批准或回滚（40301）。这正是「不扩展现有 @AuthCheck 语义」的落点。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "eye-diagnosis.control-center.enabled=true",
                "eye-diagnosis.control-center.publisher-user-ids=9300001"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ControlCenterPublishAcceptanceTest {

    private static final String PASSWORD = "Passw0rd!2026";
    private static final long PUBLISHER_ID = 9_300_001L;
    private static final long ADMIN_ID = 9_300_002L;
    private static final long USER_ID = 9_300_003L;

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordService passwords;

    private final String suffix = Long.toString(System.nanoTime(), 36);

    private String base;
    private Client publisher;
    private Client admin;
    private Client user;

    @BeforeAll
    void setUp() throws Exception {
        base = "http://127.0.0.1:" + port;
        insertUser(PUBLISHER_ID, "cc-pub-" + suffix, "admin");
        insertUser(ADMIN_ID, "cc-adm-" + suffix, "admin");
        insertUser(USER_ID, "cc-usr-" + suffix, "user");
        publisher = new Client(base);
        admin = new Client(base);
        user = new Client(base);
        assertTrue(publisher.login("cc-pub-" + suffix), "发布人登录应成功");
        assertTrue(admin.login("cc-adm-" + suffix), "管理员登录应成功");
        assertTrue(user.login("cc-usr-" + suffix), "普通用户登录应成功");
    }

    @BeforeEach
    void resetConfigState() {
        jdbc.update("DELETE FROM t_config_publish_request");
        jdbc.update("DELETE FROM t_config_audit_event");
        jdbc.update("DELETE FROM t_config_request");
        jdbc.update("DELETE FROM t_config_release");
        jdbc.update("DELETE FROM t_config_draft");
        jdbc.update("DELETE FROM t_config_active_pointer");
        jdbc.update("INSERT INTO t_config_active_pointer (id, releaseId) VALUES (1, NULL)");
    }

    @AfterAll
    void tearDown() {
        resetConfigState();
        jdbc.update("DELETE FROM t_user WHERE id IN (?,?,?)", PUBLISHER_ID, ADMIN_ID, USER_ID);
    }

    // ---------------------------------------------------------------- 白名单

    @Test
    void adminOutsideThePublisherWhitelistCannotApprove() throws Exception {
        long approvalId = stagePendingApproval();

        Reply reply = admin.write("POST",
                "/api/admin/control-center/publish-requests/" + approvalId + "/approve",
                "{\"requestId\":\"http-approve-denied\"}", csrf(admin));

        assertEquals(40301, reply.code(), "非白名单管理员必须 40301，实际: " + reply.body());
        assertEquals(0, releaseRows(), "被拒绝的批准不得产生 release");
        assertEquals(null, activeReleaseId());
        assertEquals("PENDING", approvalStatus(approvalId));
        assertEquals(1, deniedAudits("PUBLISH"), "越权尝试必须留痕");
    }

    @Test
    void nonAdminCannotPublishEvenWithACsrfToken() throws Exception {
        // 普通用户连 token 端点都拿不到（@AuthCheck admin）：CSRF 这一层就不放行。
        Reply token = user.get("/api/admin/control-center/csrf");
        assertTrue(token.code() != 0, "非管理员不得取得 CSRF token: " + token.body());

        // 因此带了也只会是空值：写端点必须在 CSRF 层就被拒，且不产生任何审批。
        long revision = stageValidatedDraft(admin);
        Reply reply = user.write("POST", "/api/admin/control-center/drafts/1/publish-requests",
                "{\"revision\":" + revision + ",\"requestId\":\"http-request-denied\"}", "");

        assertEquals(403, reply.status(), reply.body());
        assertTrue(reply.body().contains("CSRF"), reply.body());
        assertEquals(0, approvalRows(), "非管理员不得创建审批单");
    }

    @Test
    void approvalRequiresCsrf() throws Exception {
        long approvalId = stagePendingApproval();

        // 全新会话：从未取过 token，"缺失"与"不匹配"必须能区分开。
        // 复用 publisher 会话会让它带着上一步拿到的 token，那是 CSRF_MISMATCH 场景。
        Client fresh = new Client(base);
        assertTrue(fresh.login("cc-pub-" + suffix));
        Reply reply = fresh.postJson(
                "/api/admin/control-center/publish-requests/" + approvalId + "/approve",
                "{\"requestId\":\"http-approve-no-csrf\"}", base);

        assertEquals(403, reply.status(), reply.body());
        assertTrue(reply.body().contains("CSRF_MISSING"), reply.body());
        assertEquals(0, releaseRows());
    }

    // ---------------------------------------------------------------- 全链路

    @Test
    void publisherApprovesAndRollsBackOverHttp() throws Exception {
        long revision = stageValidatedDraft(admin);
        String publisherCsrf = csrf(publisher);

        Reply requested = admin.write("POST", "/api/admin/control-center/drafts/1/publish-requests",
                "{\"revision\":" + revision + ",\"requestId\":\"http-request-1\"}", csrf(admin));
        assertEquals(0, requested.code(), requested.body());
        long approvalId = requested.json().path("data").path("id").asLong();
        assertEquals("PENDING", requested.json().path("data").path("status").asText());

        Reply approved = publisher.write("POST",
                "/api/admin/control-center/publish-requests/" + approvalId + "/approve",
                "{\"requestId\":\"http-approve-1\"}", publisherCsrf);
        assertEquals(0, approved.code(), approved.body());
        assertEquals(1, approved.json().path("data").path("releaseVersion").asInt());
        // release id 是自增主键，多次运行之间不连续：从响应里取，不能假定等于 1。
        long release1Id = approved.json().path("data").path("id").asLong();

        Reply active = publisher.get("/api/admin/control-center/active");
        assertEquals("RELEASE", active.json().path("data").path("source").asText());
        assertEquals("Iris 验收 A", active.json()
                .path("data").path("values").path("brand.siteName").asText());

        // 第二版：草稿改动 → 校验 → 申请 → 批准。
        Reply updated = admin.write("PUT", "/api/admin/control-center/drafts/1",
                "{\"schemaVersion\":1,\"expectedRevision\":" + revision
                        + ",\"values\":{\"brand.siteName\":\"Iris 验收 B\"}}", csrf(admin));
        assertEquals(0, updated.code(), updated.body());
        long revision2 = updated.json().path("data").path("revision").asLong();
        admin.write("POST", "/api/admin/control-center/drafts/1/validate",
                "{\"expectedRevision\":" + revision2 + "}", csrf(admin));
        Reply requested2 = admin.write("POST", "/api/admin/control-center/drafts/1/publish-requests",
                "{\"revision\":" + revision2 + ",\"expectedActiveReleaseId\":" + release1Id + ","
                        + "\"requestId\":\"http-request-2\"}", csrf(admin));
        assertEquals(0, requested2.code(), requested2.body());
        Reply approved2 = publisher.write("POST",
                "/api/admin/control-center/publish-requests/"
                        + requested2.json().path("data").path("id").asLong() + "/approve",
                "{\"requestId\":\"http-approve-2\"}", publisherCsrf);
        assertEquals(0, approved2.code(), approved2.body());
        assertEquals(2, approved2.json().path("data").path("releaseVersion").asInt());
        long release2Id = approved2.json().path("data").path("id").asLong();

        // 回滚到第一版：产生第 3 个 release，而不是把 v2 改回去。
        Reply rolledBack = publisher.write("POST",
                "/api/admin/control-center/releases/" + release1Id + "/rollback",
                "{\"expectedActiveReleaseId\":" + release2Id
                        + ",\"requestId\":\"http-rollback-1\"}", publisherCsrf);
        assertEquals(0, rolledBack.code(), rolledBack.body());
        assertEquals(3, rolledBack.json().path("data").path("releaseVersion").asInt());
        assertEquals(release1Id, rolledBack.json().path("data").path("rollbackFromReleaseId").asLong());
        assertEquals("Iris 验收 A", rolledBack.json()
                .path("data").path("values").path("brand.siteName").asText());
        long release3Id = rolledBack.json().path("data").path("id").asLong();

        Reply history = publisher.get("/api/admin/control-center/releases?page=1&pageSize=10");
        assertEquals(3, history.json().path("data").path("total").asInt());
        assertEquals(3, releaseRows());
        assertEquals(release3Id, activeReleaseId());
    }

    @Test
    void publishRequiresAValidatedDraftOverHttp() throws Exception {
        Reply created = admin.write("POST", "/api/admin/control-center/drafts",
                "{\"schemaVersion\":1,\"values\":{\"brand.siteName\":\"Iris 未校验\"}}", csrf(admin));
        long revision = created.json().path("data").path("revision").asLong();

        Reply requested = admin.write("POST", "/api/admin/control-center/drafts/1/publish-requests",
                "{\"revision\":" + revision + ",\"requestId\":\"http-request-unvalidated\"}", csrf(admin));

        assertEquals(40901, requested.code(), requested.body());
        assertEquals(0, approvalRows());
    }

    @Test
    void unknownApprovalIsReportedAs404OverHttp() throws Exception {
        Reply reply = publisher.write("POST",
                "/api/admin/control-center/publish-requests/999999/approve",
                "{\"requestId\":\"http-approve-missing\"}", csrf(publisher));
        assertEquals(40400, reply.code(), reply.body());
    }

    // ---------------------------------------------------------------- 辅助

    /** 建草稿 + 校验，返回 revision（用给定会话执行，以便验证权限差异）。 */
    private long stageValidatedDraft(Client session) throws Exception {
        String sessionCsrf = csrf(session);
        Reply created = session.write("POST", "/api/admin/control-center/drafts",
                "{\"schemaVersion\":1,\"values\":{\"brand.siteName\":\"Iris 验收 A\"}}", sessionCsrf);
        assertEquals(0, created.code(), created.body());
        long revision = created.json().path("data").path("revision").asLong();
        Reply validated = session.write("POST", "/api/admin/control-center/drafts/1/validate",
                "{\"expectedRevision\":" + revision + "}", sessionCsrf);
        assertEquals(0, validated.code(), validated.body());
        return revision;
    }

    /** 建一条 PENDING 审批，返回其 id。 */
    private long stagePendingApproval() throws Exception {
        long revision = stageValidatedDraft(admin);
        Reply requested = admin.write("POST", "/api/admin/control-center/drafts/1/publish-requests",
                "{\"revision\":" + revision + ",\"requestId\":\"http-request-pending\"}", csrf(admin));
        assertEquals(0, requested.code(), requested.body());
        return requested.json().path("data").path("id").asLong();
    }

    private String csrf(Client session) {
        return session.get("/api/admin/control-center/csrf")
                .json().path("data").path("token").asText();
    }

    private void insertUser(long id, String account, String role) {
        jdbc.update("INSERT INTO t_user (id,userAccount,userPassword,userName,userRole) VALUES (?,?,?,?,?)",
                id, account, passwords.encode(PASSWORD), account, role);
    }

    private int approvalRows() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_config_publish_request", Integer.class);
        return count == null ? 0 : count;
    }

    private int releaseRows() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM t_config_release", Integer.class);
        return count == null ? 0 : count;
    }

    private int deniedAudits(String action) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_config_audit_event WHERE action = ? AND result = 'DENIED'",
                Integer.class, action);
        return count == null ? 0 : count;
    }

    private String approvalStatus(long id) {
        return jdbc.queryForObject(
                "SELECT status FROM t_config_publish_request WHERE id = ?", String.class, id);
    }

    private Long activeReleaseId() {
        return jdbc.queryForObject(
                "SELECT releaseId FROM t_config_active_pointer WHERE id = 1", Long.class);
    }

    private record Reply(HttpResponse<String> raw, String body) {
        int status() {
            return raw.statusCode();
        }

        int code() {
            try {
                return new ObjectMapper().readTree(body).path("code").asInt(-1);
            } catch (Exception e) {
                return -1;
            }
        }

        JsonNode json() {
            try {
                return new ObjectMapper().readTree(body);
            } catch (Exception e) {
                throw new IllegalStateException("响应不是 JSON: " + body, e);
            }
        }
    }

    /** 带 Cookie 的最小浏览器（与 ControlCenterAcceptanceTest 同一套约定）。 */
    private static final class Client {

        private final HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        private final String base;

        Client(String base) {
            this.base = base;
        }

        boolean login(String account) throws Exception {
            Reply reply = postJson("/api/user/login",
                    "{\"userAccount\":\"" + account + "\",\"userPassword\":\"" + PASSWORD + "\"}",
                    base);
            return reply.code() == 0;
        }

        Reply get(String path) {
            return send(HttpRequest.newBuilder(URI.create(base + path))
                    .header("Origin", base).GET());
        }

        Reply postJson(String path, String body, String origin) {
            return send(HttpRequest.newBuilder(URI.create(base + path))
                    .header("Content-Type", "application/json")
                    .header("Origin", origin)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
        }

        Reply write(String method, String path, String body, String csrf) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                    .header("Content-Type", "application/json")
                    .header("Origin", base)
                    .header("X-CSRF-Token", csrf);
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            return send(builder);
        }

        private Reply send(HttpRequest.Builder builder) {
            try {
                HttpResponse<String> response = client.send(
                        builder.timeout(Duration.ofSeconds(20)).build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                return new Reply(response, response.body());
            } catch (Exception e) {
                throw new IllegalStateException("HTTP 调用失败: " + e.getMessage(), e);
            }
        }
    }
}
