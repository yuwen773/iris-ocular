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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置中心 HTTP 安全矩阵与基础 API 验收（WP-01B，阶段 0 + 阶段 2）。
 *
 * <p>跑真实 {@code EyeDiagnosisApplication}：真实 Tomcat、真实 context-path
 * {@code /api}、真实过滤器链、真实 Spring Session、真实 AOP。目的不是重复单测
 * 的逻辑，而是验证这些<b>装配</b>在真实请求里成立：
 * <ol>
 *   <li>{@code /api/admin/control-center/**} 的路径匹配（context-path 剥离）；</li>
 *   <li>执行顺序：Origin → CSRF → 认证 → 动作权限；</li>
 *   <li>写端点必须有 CSRF token，且 token 端点不可缓存；</li>
 *   <li>读环境（active/registry/draft）与草稿写的正常路径。</li>
 * </ol>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "eye-diagnosis.control-center.enabled=true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ControlCenterAcceptanceTest {

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
    private long userId;
    private Client admin;
    private Client user;

    @BeforeAll
    void setUp() throws Exception {
        base = "http://127.0.0.1:" + port;
        adminId = insertUser("cc-admin-" + suffix, "admin");
        userId = insertUser("cc-user-" + suffix, "user");
        admin = new Client(base);
        user = new Client(base);
        assertTrue(admin.login("cc-admin-" + suffix), "管理员登录应成功");
        assertTrue(user.login("cc-user-" + suffix), "普通用户登录应成功");
    }

    /** 每个用例从空配置状态开始：用例之间不能互相看见对方的草稿与审计。 */
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
        jdbc.update("DELETE FROM t_config_publish_request");
        jdbc.update("DELETE FROM t_config_audit_event");
        jdbc.update("DELETE FROM t_config_request");
        jdbc.update("DELETE FROM t_config_release");
        jdbc.update("DELETE FROM t_config_draft");
        jdbc.update("DELETE FROM t_config_active_pointer");
        jdbc.update("INSERT INTO t_config_active_pointer (id, releaseId) VALUES (1, NULL)");
        jdbc.update("DELETE FROM t_user WHERE id IN (?,?)", adminId, userId);
    }

    // ---------------------------------------------------------------- 读取

    @Test
    void anonymousCannotReadAdminConfig() {
        Client anonymous = new Client(base);
        Reply reply = anonymous.get("/api/admin/control-center/active");
        assertEquals(40100, reply.code(), "未登录必须 40100，实际: " + reply.body());
    }

    @Test
    void nonAdminIsRejectedWithConfigPermissionDenied() {
        Reply reply = user.get("/api/admin/control-center/active");
        assertEquals(40301, reply.code(), "非管理员必须是 40301（不是 40101），实际: " + reply.body());
    }

    @Test
    void adminReadsRegistryAndDefaults() {
        Reply registry = admin.get("/api/admin/control-center/registry");
        assertEquals(0, registry.code());
        assertEquals(6, registry.json().path("data").path("keys").size());

        Reply active = admin.get("/api/admin/control-center/active");
        assertEquals(0, active.code());
        assertEquals("DEFAULT", active.json().path("data").path("source").asText());
        assertEquals("Iris", active.json().path("data").path("values").path("brand.siteName").asText());
    }

    // ---------------------------------------------------------------- 安全顺序

    @Test
    void crossOriginWriteIsRejectedBeforeAnythingElse() {
        Client evil = admin.fork();
        Reply reply = evil.postJson("/api/admin/control-center/drafts",
                "{\"schemaVersion\":1,\"values\":{}}", "http://evil.example.com");
        assertEquals(403, reply.status(), "跨站写请求必须是 HTTP 403，实际: " + reply.body());
        assertTrue(reply.body().contains("CROSS_ORIGIN_BLOCKED"));
        assertEquals(0, draftRows(), "被拒绝的请求不得产生任何草稿写入");
    }

    @Test
    void originNullIsRejectedEvenWithAValidReferer() {
        Client evil = admin.fork();
        Reply reply = evil.postJsonWithHeaders("/api/admin/control-center/drafts",
                "{\"schemaVersion\":1,\"values\":{}}",
                Map.of("Origin", "null", "Referer", base + "/admin/control-center"));
        assertEquals(403, reply.status());
        assertTrue(reply.body().contains("ORIGIN_NULL"), "必须给出 ORIGIN_NULL 原因码: " + reply.body());
    }

    @Test
    void writeWithoutCsrfTokenIsRejectedAfterOriginPasses() throws Exception {
        // 新会话，从未取过 token：失败原因必须是「缺失」，而不是「不匹配」。
        Client fresh = new Client(base);
        assertTrue(fresh.login("cc-admin-" + suffix));
        Reply reply = fresh.postJson("/api/admin/control-center/drafts",
                "{\"schemaVersion\":1,\"values\":{}}", base);
        assertEquals(403, reply.status());
        assertTrue(reply.body().contains("CSRF_MISSING"), "同源但无 token 必须 CSRF_MISSING: " + reply.body());
        assertEquals(0, draftRows());

        // 已签发过 token 的会话带错值：必须是「不匹配」。
        String csrf = admin.get("/api/admin/control-center/csrf").json().path("data").path("token").asText();
        Reply wrong = admin.write("POST", "/api/admin/control-center/drafts",
                "{\"schemaVersion\":1,\"values\":{}}", csrf + "tampered");
        assertEquals(403, wrong.status());
        assertTrue(wrong.body().contains("CSRF_MISMATCH"), "错误 token 必须 CSRF_MISMATCH: " + wrong.body());
        assertEquals(0, draftRows());

        // 被拒绝的敏感操作必须留痕，且能追到操作人（会话已登录）。
        Long recordedActor = jdbc.queryForObject(
                "SELECT actorId FROM t_config_audit_event WHERE action = 'CSRF_REJECTED' "
                        + "AND reasonCode = 'CSRF_MISMATCH' ORDER BY id DESC LIMIT 1", Long.class);
        assertEquals(adminId, recordedActor, "CSRF 拒绝审计必须记录真实操作人");
    }

    @Test
    void originIsCheckedBeforeCsrf() {
        // 跨站 + 无 token：必须先报 Origin，而不是 CSRF——顺序错了会掩盖真实的攻击面。
        Client evil = admin.fork();
        Reply reply = evil.postJson("/api/admin/control-center/drafts",
                "{\"schemaVersion\":1,\"values\":{}}", "http://evil.example.com");
        assertEquals(403, reply.status());
        assertTrue(reply.body().contains("CROSS_ORIGIN_BLOCKED"));
        assertFalse(reply.body().contains("CSRF"));
    }

    // ---------------------------------------------------------------- CSRF 端点

    @Test
    void csrfTokenEndpointIsNotCacheableAndRequiresAdmin() {
        Reply reply = admin.get("/api/admin/control-center/csrf");
        assertEquals(0, reply.code());
        assertEquals("no-store", reply.header("Cache-Control"),
                "token 响应绝不能被任何缓存留存");
        assertNotNull(reply.json().path("data").path("token").asText());

        Reply denied = user.get("/api/admin/control-center/csrf");
        assertEquals(40101, denied.code(), "非管理员取 token 必须被拒（@AuthCheck admin）");
    }

    // ---------------------------------------------------------------- 草稿写

    @Test
    void adminCanCreateUpdateAndValidateADraft() throws Exception {
        Reply token = admin.get("/api/admin/control-center/csrf");
        String csrf = token.json().path("data").path("token").asText();

        Reply created = admin.write("POST", "/api/admin/control-center/drafts",
                "{\"schemaVersion\":1,\"values\":{\"brand.siteName\":\"Iris 验收\"}}", csrf);
        assertEquals(0, created.code(), created.body());
        assertEquals(1, created.json().path("data").path("revision").asInt());
        assertEquals("Iris 验收", created.json().path("data").path("values").path("brand.siteName").asText());

        Reply updated = admin.write("PUT", "/api/admin/control-center/drafts/1",
                "{\"schemaVersion\":1,\"expectedRevision\":1,\"values\":{\"brand.siteName\":\"Iris 验收 2\"}}", csrf);
        assertEquals(0, updated.code(), updated.body());
        assertEquals(2, updated.json().path("data").path("revision").asInt());

        Reply validated = admin.write("POST", "/api/admin/control-center/drafts/1/validate",
                "{\"expectedRevision\":2}", csrf);
        assertEquals(0, validated.code(), validated.body());
        assertTrue(validated.json().path("data").path("valid").asBoolean());
        assertEquals("VALIDATED", readDraftStatus());
    }

    @Test
    void staleRevisionOverHttpReturns40900Envelope() throws Exception {
        String csrf = admin.get("/api/admin/control-center/csrf").json().path("data").path("token").asText();
        admin.write("POST", "/api/admin/control-center/drafts",
                "{\"schemaVersion\":1,\"values\":{\"brand.siteName\":\"Iris\"}}", csrf);

        Reply stale = admin.write("PUT", "/api/admin/control-center/drafts/1",
                "{\"schemaVersion\":1,\"expectedRevision\":99,\"values\":{\"brand.siteName\":\"Stale\"}}", csrf);

        assertEquals(200, stale.status(), "业务错误走 200 + 非零 code 的信封约定, body=" + stale.body());
        assertEquals(40900, stale.code(), stale.body());
        String storedPayload = jdbc.queryForObject(
                "SELECT payload FROM t_config_draft WHERE id = 1", String.class);
        // 键名里带点：JSON 路径必须写成 $."brand.siteName"，否则会被当成两级嵌套。
        assertEquals("Iris", jdbc.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.\"brand.siteName\"')) FROM t_config_draft WHERE id = 1",
                String.class), "草稿内容被过期请求改动了, 实际 payload=" + storedPayload);
    }

    @Test
    void writesAreAudited() {
        String csrf = admin.get("/api/admin/control-center/csrf").json().path("data").path("token").asText();
        admin.write("POST", "/api/admin/control-center/drafts",
                "{\"schemaVersion\":1,\"values\":{\"brand.siteName\":\"Iris 审计\"}}", csrf);

        Integer audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_config_audit_event WHERE action LIKE 'DRAFT_%'", Integer.class);
        assertNotNull(audits);
        assertTrue(audits > 0, "草稿操作必须留下审计");
        Integer leaks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_config_audit_event WHERE payload LIKE '%Passw0rd%' "
                        + "OR payload LIKE '%csrf%' OR payload LIKE '%session%'", Integer.class);
        assertEquals(0, leaks, "审计不得包含口令 / CSRF token / Session");
    }

    // -------------------------------------------------- 路径变体（矩阵参数）

    /**
     * 矩阵参数不得成为安全控制的盲区。
     *
     * <p>过滤器若按 {@code getRequestURI()} 的原始串做前缀匹配，而 Tomcat/Spring 在路由前会
     * 把 {@code ;name=value}（含被编码的 {@code %3B}）当作路径参数剥掉，就会出现「MVC 命中了
     * 写方法、过滤器却判定不是控制中心路径」的组合。此时 Origin 严格模式与 CSRF 校验整体失效：
     * 同源但无 token 的写请求会被直接执行。
     *
     * <p>因此每个变体都验证三件事，缺一不可：
     * <ol>
     *   <li><b>MVC 确实能命中</b>：带合法 Origin 与合法 token 时写请求成功——否则本条变体
     *       根本没被路由，下面的拒绝就是空的；</li>
     *   <li><b>CSRF 先于控制器执行</b>：同源、合法会话、无 token 必须 403 + {@code CSRF_MISSING}；</li>
     *   <li><b>严格 Origin 先于控制器执行</b>：跨站请求必须 403 + {@code CROSS_ORIGIN_BLOCKED}。</li>
     * </ol>
     * 后两条都必须伴随「数据库没有新增行」。
     */
    @Test
    void matrixParameterVariantsCannotBypassSecurityControls() throws Exception {
        java.util.List<String> violations = new java.util.ArrayList<>();
        String token = admin.get("/api/admin/control-center/csrf")
                .json().path("data").path("token").asText();

        for (Variant variant : PATH_VARIANTS) {
            String path = variant.path();

            // 1) 先确定这条变体到底有没有被 MVC 路由到写方法。Routed=false 的变体
            //    必须真的写不进去——否则「没有路由」这个前提本身就错了。
            jdbc.update("DELETE FROM t_config_draft");
            Reply allowed = admin.write("POST", path, BYPASS_BODY, token);
            boolean routed = allowed.code() == 0;
            if (variant.routed() != routed) {
                violations.add(path + " 路由性与预期不符（期望 routed=" + variant.routed()
                        + "，实际 code=" + allowed.code() + "）: " + allowed.body());
                continue;
            }
            if (!routed) {
                if (draftRows() != 0) {
                    violations.add(path + " 声称未路由，却写入了草稿");
                }
                continue;
            }
            if (draftRows() != 1) {
                violations.add(path + " 合法请求未落库，无法作为路由证明");
                continue;
            }

            // 2) 同源 + 合法会话 + 无 token：CSRF 必须先于控制器执行。
            jdbc.update("DELETE FROM t_config_draft");
            Client fresh = new Client(base);
            assertTrue(fresh.login("cc-admin-" + suffix));
            Reply noToken = fresh.postJson(path, BYPASS_BODY, base);
            if (noToken.status() != 403 || !noToken.body().contains("CSRF_MISSING")) {
                violations.add(path + " 同源无 token 未被 CSRF 拦下: "
                        + noToken.status() + " " + noToken.body());
            }
            if (draftRows() != 0) {
                violations.add(path + " 无 token 的请求写入了草稿");
            }

            // 3) 跨站：严格 Origin 必须先于控制器执行。reasonCode 的存在说明走的是
            //    严格分支，而不是全站沿用的旧版 host-only 比较。
            jdbc.update("DELETE FROM t_config_draft");
            Reply crossSite = admin.fork().postJson(path, BYPASS_BODY, "http://evil.example.com");
            if (crossSite.status() != 403 || !crossSite.body().contains("CROSS_ORIGIN_BLOCKED")) {
                violations.add(path + " 跨站写未被 Origin 拦下: "
                        + crossSite.status() + " " + crossSite.body());
            }
            if (!crossSite.body().contains(REASON_CODE_FIELD)) {
                violations.add(path + " 走的是旧版 host-only 分支，严格 Origin 未生效: "
                        + crossSite.body());
            }
            if (draftRows() != 0) {
                violations.add(path + " 跨站请求写入了草稿");
            }
        }

        assertTrue(violations.isEmpty(), "路径变体绕过安全控制:\n  " + String.join("\n  ", violations));
    }

    private static final String REASON_CODE_FIELD = "\"reasonCode\"";

    private static final String BYPASS_BODY =
            "{\"schemaVersion\":1,\"values\":{\"brand.siteName\":\"bypass\"}}";

    /**
     * 矩阵参数变体。
     *
     * <p>{@code routed} 是 2026-11-24 在真实 Tomcat + 真实 context-path 上实测的结果：
     * 字面 {@code ;} 会被 Tomcat/MVC 当作路径参数剥掉后再匹配，因此能路由；而
     * {@code %3B} 不会被解码参与匹配，MVC 直接 {@code NoHandlerFoundException}。
     * 保留后者是为了把它当**探针**：哪天真被路由了，这条用例会立刻红。
     */
    private record Variant(String path, boolean routed) {
    }

    private static final java.util.List<Variant> PATH_VARIANTS = java.util.List.of(
            new Variant("/api/admin/control-center;test=1/drafts", true),
            new Variant("/api/admin/control-center%3Btest=1/drafts", false),
            new Variant("/api/admin/control-center/drafts;test=1", true));

    // ---------------------------------------------------------------- 辅助

    private long insertUser(String account, String role) {
        jdbc.update("INSERT INTO t_user (userAccount,userPassword,userName,userRole) VALUES (?,?,?,?)",
                account, passwords.encode(PASSWORD), account, role);
        return jdbc.queryForObject("SELECT id FROM t_user WHERE userAccount=?", Long.class, account);
    }

    private int draftRows() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM t_config_draft", Integer.class);
        return count == null ? 0 : count;
    }

    private String readDraftStatus() {
        return jdbc.queryForObject("SELECT status FROM t_config_draft WHERE id = 1", String.class);
    }

    private record Reply(HttpResponse<String> raw, String body) {
        int status() {
            return raw.statusCode();
        }

        int code() {
            try {
                JsonNode node = new ObjectMapper().readTree(body);
                return node.path("code").asInt(-1);
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

        String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }
    }

    /** 带 Cookie 的最小浏览器。 */
    private static final class Client {

        private final HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        private final String base;

        Client(String base) {
            this.base = base;
        }

        /**
         * 一个全新的会话，用于伪造 Origin。
         *
         * <p>不需要共享 Cookie：Origin 校验发生在认证之前，跨站请求即便
         * 没有会话也必须先被过滤器拦下——这正是要验证的顺序。
         */
        Client fork() {
            return new Client(base);
        }

        boolean login(String account) throws Exception {
            Reply reply = postJson("/api/user/login",
                    "{\"userAccount\":\"" + account + "\",\"userPassword\":\"" + PASSWORD + "\"}", base);
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

        Reply postJsonWithHeaders(String path, String body, Map<String, String> headers) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            headers.forEach(builder::header);
            return send(builder);
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
