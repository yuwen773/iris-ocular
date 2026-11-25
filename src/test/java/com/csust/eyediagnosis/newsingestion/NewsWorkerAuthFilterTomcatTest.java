package com.csust.eyediagnosis.newsingestion;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.valves.RemoteIpValve;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link NewsWorkerAuthFilter} 在**真实 Tomcat** 里的端到端行为。
 *
 * <p>{@link NewsForwardedPeerSemanticsTest} 只回答「容器把 {@code getRemoteAddr()} 改成谁」；
 * 这里回答「过滤器拿着这个值会放行还是拒绝」。两者必须一起成立，否则白名单的正确性只是
 * 单元测试里的假设：过滤器直接调用 {@code request.getRemoteAddr()}，而这个值在挂了
 * {@code RemoteIpValve} 之后由 {@code X-Forwarded-For} 决定。
 *
 * <p>所以本类刻意不用 mock 请求、不用 MockMvc：过滤器按 Tomcat 的 {@code FilterDef} 注册，
 * 请求走真实 HTTP 连接，8585 断言的是真实容器里真实发生的事。
 */
class NewsWorkerAuthFilterTomcatTest {

    /** 32 位以上可见 ASCII；不用 fixture_ / placeholder 前缀以免与占位凭据检查混淆。 */
    private static final String WORKER_KEY = "probe-worker-key-0123456789ABCDEFGHIJ";

    private static final String LEASE_PATH = "/internal/news/v1/leases/claim";

    /** 所有用例的 socket 对端恒为本机。 */
    private static final String SOCKET_PEER = "127.0.0.1";

    /** 攻击者声称的客户端地址，同时用作白名单条目。 */
    private static final String CLAIMED_CLIENT = "203.0.113.9";

    @Test
    void directAllowlistedWorkerPassesTheFilter() throws Exception {
        try (Probe probe = Probe.start(null, List.of(SOCKET_PEER), false)) {
            HttpResult result = probe.call(WORKER_KEY, null, null);

            assertEquals(200, result.status(), result.body());
            assertEquals("PASS|" + SOCKET_PEER + "|false", result.body(),
                    "直连请求的有效地址就是 socket 对端，且明文连接下 isSecure() 为 false");
        }
    }

    @Test
    void missingWorkerKeyIsRejectedWithUnauthenticated() throws Exception {
        try (Probe probe = Probe.start(null, List.of(SOCKET_PEER), false)) {
            HttpResult result = probe.call(null, null, null);

            assertEquals(401, result.status());
            assertEquals("{\"code\":\"UNAUTHENTICATED\"}", result.body());
        }
    }

    @Test
    void forwardingHeaderIsRejectedEvenWhenTheRewrittenAddressIsAllowlisted() throws Exception {
        // 先证明「这个攻击是真的」：反代只追加一跳、不清洗 X-Forwarded-For 时，
        // 客户端自带的最右一跳会成为 getRemoteAddr()——它正好可以被写进白名单。
        try (Probe open = Probe.start(trustingLocalProxy(), List.of(), false)) {
            HttpResult rewritten = open.call(WORKER_KEY, CLAIMED_CLIENT, null);
            assertEquals(200, rewritten.status(), rewritten.body());
            assertEquals("PASS|" + CLAIMED_CLIENT + "|false", rewritten.body(),
                    "白名单为空时可以直接观察到有效地址被改写成了攻击者声称的地址");
        }

        // 同一个请求，配上把该地址列入白名单的配置：必须 fail closed。
        try (Probe guarded = Probe.start(trustingLocalProxy(), List.of(CLAIMED_CLIENT), false)) {
            HttpResult blocked = guarded.call(WORKER_KEY, CLAIMED_CLIENT, null);

            assertEquals(403, blocked.status(),
                    "带转发头的请求不得用代理转述的客户端地址去比对白名单");
            assertEquals("{\"code\":\"FORBIDDEN\"}", blocked.body());
        }
    }

    @Test
    void untrustedPeerForwardingHeaderIsStillRejected() throws Exception {
        // 对端不可信 ⇒ Tomcat 忽略转发头，有效地址仍是白名单内的 127.0.0.1。
        // 但请求带着转发头这件事本身就足以拒绝：这里断言的是「不看地址像不像」。
        try (Probe probe = Probe.start(valveFor("192\\.168\\.1\\.1"), List.of(SOCKET_PEER), false)) {
            HttpResult result = probe.call(WORKER_KEY, CLAIMED_CLIENT, null);

            assertEquals(403, result.status(), result.body());
        }
    }

    @Test
    void directPeerOutsideTheAllowlistIsRejected() throws Exception {
        try (Probe probe = Probe.start(null, List.of("203.0.113.5"), false)) {
            HttpResult result = probe.call(WORKER_KEY, null, null);

            assertEquals(403, result.status(), result.body());
        }
    }

    @Test
    void httpsRequirementIsEnforcedAgainstTheContainerSeenScheme() throws Exception {
        // 明文直连：容器看到的就是 http，门禁必须拒绝。
        try (Probe plaintext = Probe.start(null, List.of(), true)) {
            HttpResult blocked = plaintext.call(WORKER_KEY, null, null);
            assertEquals(403, blocked.status(), "require-https 下明文直连必须被拒绝");
        }

        // 可信反代声称 https：native 转发处理把 isSecure() 置真，门禁放行。
        // 这正是启动基线要求显式配置 internal-proxies 的原因——这段信任由运维而非客户端决定。
        try (Probe viaProxy = Probe.start(trustingLocalProxy(), List.of(), true)) {
            HttpResult allowed = viaProxy.call(WORKER_KEY, CLAIMED_CLIENT, "https");
            assertEquals(200, allowed.status(), allowed.body());
            assertEquals("PASS|" + CLAIMED_CLIENT + "|true", allowed.body(),
                    "可信反代的 X-Forwarded-Proto: https 在真实容器里确实把 isSecure() 置真");
        }
    }

    private static RemoteIpValve trustingLocalProxy() {
        return valveFor("127\\.0\\.0\\.1");
    }

    private static RemoteIpValve valveFor(String internalProxies) {
        RemoteIpValve valve = new RemoteIpValve();
        valve.setInternalProxies(internalProxies);
        valve.setTrustedProxies(internalProxies);
        valve.setRequestAttributesEnabled(true);
        return valve;
    }

    private record HttpResult(int status, String body) {
    }

    /**
     * 一个只注册了 Worker 过滤器和租约路由的真实 Tomcat 实例。
     *
     * @param valve             {@code RemoteIpValve}；null 表示不做转发处理（Spring Boot 里是
     *                          {@code server.forward-headers-strategy=none} 或未启用）
     * @param allowedAddresses  {@code eye-diagnosis.news-ingestion.allowed-remote-addresses}
     * @param requireHttps      {@code eye-diagnosis.news-ingestion.require-https}
     */
    private static final class Probe implements AutoCloseable {

        private final Tomcat tomcat;
        private final Path baseDir;
        private final int port;

        private Probe(Tomcat tomcat, Path baseDir, int port) {
            this.tomcat = tomcat;
            this.baseDir = baseDir;
            this.port = port;
        }

        static Probe start(RemoteIpValve valve, List<String> allowedAddresses, boolean requireHttps)
                throws Exception {
            NewsIngestionProperties properties = properties(allowedAddresses, requireHttps);
            Path baseDir = Files.createTempDirectory("tomcat-worker-auth");
            Tomcat tomcat = new Tomcat();
            tomcat.setBaseDir(baseDir.toString());
            tomcat.setPort(0);
            Connector connector = tomcat.getConnector();
            if (valve != null) {
                // Spring Boot 把 RemoteIpValve 挂在 Engine pipeline 上，这里保持一致。
                tomcat.getEngine().getPipeline().addValve(valve);
            }
            Context ctx = tomcat.addContext("", baseDir.toString());
            Tomcat.addServlet(ctx, "lease", new LeaseServlet());
            ctx.addServletMappingDecoded(LEASE_PATH, "lease");

            // 走 Tomcat 自己的过滤器注册路径，而不是在 servlet 里手工调用 doFilter：
            // 这样 OncePerRequestFilter 的初始化、URL 映射与真实部署一致。
            FilterDef filterDef = new FilterDef();
            filterDef.setFilterName("newsWorkerAuth");
            filterDef.setFilter(new NewsWorkerAuthFilter(properties));
            ctx.addFilterDef(filterDef);
            FilterMap filterMap = new FilterMap();
            filterMap.setFilterName("newsWorkerAuth");
            filterMap.addURLPattern("/*");
            ctx.addFilterMap(filterMap);

            tomcat.start();
            return new Probe(tomcat, baseDir, connector.getLocalPort());
        }

        HttpResult call(String workerKey, String forwardedFor, String forwardedProto) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + LEASE_PATH));
            if (workerKey != null) {
                builder.header(NewsWorkerAuthFilter.WORKER_KEY_HEADER, workerKey);
            }
            if (forwardedFor != null) {
                builder.header("X-Forwarded-For", forwardedFor);
            }
            if (forwardedProto != null) {
                builder.header("X-Forwarded-Proto", forwardedProto);
            }
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new HttpResult(response.statusCode(), response.body());
        }

        @Override
        public void close() throws Exception {
            try {
                tomcat.stop();
                tomcat.destroy();
            } finally {
                deleteRecursively(baseDir);
            }
        }

        private static NewsIngestionProperties properties(List<String> allowedAddresses, boolean requireHttps) {
            NewsIngestionProperties properties = new NewsIngestionProperties();
            properties.setEnabled(true);
            properties.setRequireHttps(requireHttps);
            properties.setAllowedRemoteAddresses(new ArrayList<>(allowedAddresses));
            NewsIngestionProperties.WorkerConfig worker = new NewsIngestionProperties.WorkerConfig();
            worker.setKey(WORKER_KEY);
            properties.getWorkers().put("probe-worker", worker);
            return properties;
        }

        private static void deleteRecursively(Path path) throws IOException {
            if (path == null || !Files.exists(path)) {
                return;
            }
            try (var paths = Files.walk(path)) {
                for (Path entry : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(entry);
                }
            }
        }
    }

    /** 租约路由的替身：把「过滤器放行之后」的容器视图回显出来。 */
    private static final class LeaseServlet extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("PASS|" + request.getRemoteAddr() + "|" + request.isSecure());
        }
    }

}
