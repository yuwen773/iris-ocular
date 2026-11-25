package com.csust.eyediagnosis.newsingestion;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.valves.RemoteIpValve;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 Tomcat（10.1.46，即本项目 {@code tomcat-embed-core} 的实际版本）下的转发头语义实测。
 *
 * <p>这里刻意不用 mock：Worker 的 IP 白名单与 HTTPS 门禁都建立在
 * {@code request.getRemoteAddr()} / {@code request.isSecure()} 上，
 * 「native 策略下这两个值到底是谁」必须由真实容器回答，而不是靠注释里的假设。
 *
 * <p>测试里的 HTTP 客户端一律从 127.0.0.1 连入，因此 socket 对端恒为 127.0.0.1；
 * 需要模拟「可信反代」时把 {@code internal-proxies} 收窄到只匹配 127.0.0.1，
 * 需要模拟「非可信对端」时让它匹配别的地址。
 */
class NewsForwardedPeerSemanticsTest {

    /**
     * @param remoteAddr        {@code request.getRemoteAddr()}
     * @param secure            {@code request.isSecure()}
     * @param forwardedAttribute {@code org.apache.tomcat.request.forwarded}（未启用转发处理时为 null）
     * @param accessLogRemoteAddr {@code org.apache.catalina.AccessLog.RemoteAddr}
     * @param tomcatRemoteAddr  {@code org.apache.tomcat.remoteAddr}
     */
    private record Observation(String remoteAddr,
                               boolean secure,
                               String forwardedAttribute,
                               String accessLogRemoteAddr,
                               String tomcatRemoteAddr) {
    }

    /** 本地 socket 对端：所有用例都从这里发起请求。 */
    private static final String SOCKET_PEER = "127.0.0.1";

    /** 只信任本机这一台反代。 */
    private static final String TRUST_LOCAL_PROXY = "127\\.0\\.0\\.1";

    /** 信任「内网网段」——Tomcat 默认 internal-proxies 的典型危险写法。 */
    private static final String TRUST_PRIVATE_RANGE = "10\\.0\\.0\\.0/8|127\\.0\\.0\\.1";

    @Test
    void directConnectionKeepsSocketPeerAndIgnoresForgedForwardedHeaders() throws Exception {
        Observation observation = observe(null, null, "203.0.113.9", "https");

        assertEquals(SOCKET_PEER, observation.remoteAddr(),
                "没有转发处理时 getRemoteAddr() 必须是真实 socket 对端");
        assertFalse(observation.secure(),
                "X-Forwarded-Proto: https 不得在没有转发处理时把 isSecure() 置真");
        assertNull(observation.forwardedAttribute(),
                "未启用转发处理时不应出现 Tomcat 的转发标记");
    }

    @Test
    void nativeForwardingTakesRightMostUntrustedEntryAsRemoteAddress() throws Exception {
        Observation single = observe(TRUST_LOCAL_PROXY, TRUST_LOCAL_PROXY, "203.0.113.9", null);
        assertEquals("203.0.113.9", single.remoteAddr(),
                "可信反代转发的 X-Forwarded-For 最右一跳会成为 getRemoteAddr()");

        Observation chained = observe(TRUST_LOCAL_PROXY, TRUST_LOCAL_PROXY,
                "10.0.0.5, 203.0.113.9", null);
        assertEquals("203.0.113.9", chained.remoteAddr(),
                "多级链路下取最右一跳（不是最左一跳）");

        Observation multiHop = observe(TRUST_LOCAL_PROXY, TRUST_LOCAL_PROXY,
                "203.0.113.9, 198.51.100.7", null);
        assertEquals("198.51.100.7", multiHop.remoteAddr(),
                "多级代理链下最右一跳仍是对端转述的客户端地址");
    }

    @Test
    void attackerBehindTrustedProxyFullyControlsRemoteAddress() throws Exception {
        // 反代只是「加一跳」而没有清洗客户端自带的 X-Forwarded-For：
        // 客户端写的最右一跳就是最终生效的 getRemoteAddr()。
        Observation forged = observe(TRUST_LOCAL_PROXY, TRUST_LOCAL_PROXY,
                "10.0.0.5, 203.0.113.9", null);

        assertEquals("203.0.113.9", forged.remoteAddr(),
                "攻击者自带的 X-Forwarded-For 会直接成为 getRemoteAddr()，白名单不能信任这个值");
        assertFalse(forged.secure(), "该请求没有可信的 proto 头，不应被判定为 HTTPS");
    }

    @Test
    void nativeForwardingHonoursForwardedProtoOnlyForTrustedProxy() throws Exception {
        Observation https = observe(TRUST_LOCAL_PROXY, TRUST_LOCAL_PROXY, "203.0.113.9", "https");
        assertEquals("203.0.113.9", https.remoteAddr());
        assertTrue(https.secure(), "可信反代的 X-Forwarded-Proto: https 应当生效");
        assertEquals("true", https.forwardedAttribute());

        Observation http = observe(TRUST_LOCAL_PROXY, TRUST_LOCAL_PROXY, "203.0.113.9", "http");
        assertFalse(http.secure(), "X-Forwarded-Proto: http 不得置真 isSecure()");
    }

    @Test
    void untrustedPeerCannotForgeForwardedProto() throws Exception {
        // 对端不在 internal-proxies 里：Tomcat 完全忽略转发头。
        Observation observation = observe("192\\.168\\.1\\.1", "192\\.168\\.1\\.1", "203.0.113.9", "https");

        assertEquals(SOCKET_PEER, observation.remoteAddr(),
                "非可信对端的转发头必须被忽略，getRemoteAddr() 保持真实对端");
        assertFalse(observation.secure(),
                "非可信对端的 X-Forwarded-Proto: https 不得把 isSecure() 置真");
        assertNull(observation.forwardedAttribute(), "未发生转发改写时不应有转发标记");
    }

    @Test
    void broadInternalProxyRangeLetsSameSegmentPeerForgeHttps() throws Exception {
        // 反代所在网段被整体信任（抄默认值时的典型写法）：同网段任意主机都能自称 https。
        Observation observation = observe(TRUST_PRIVATE_RANGE, TRUST_PRIVATE_RANGE,
                "203.0.113.9", "https");

        assertEquals("203.0.113.9", observation.remoteAddr());
        assertTrue(observation.secure(),
                "被 internal-proxies 覆盖的对端可以伪造 isSecure()，这正是启动基线要拦的组合");
    }

    @Test
    void originalSocketPeerIsNotRecoverableAfterForwardingRewrite() throws Exception {
        Observation observation = observe(TRUST_LOCAL_PROXY, TRUST_LOCAL_PROXY, "203.0.113.9", "https");

        assertEquals("203.0.113.9", observation.remoteAddr());
        assertEquals("203.0.113.9", observation.accessLogRemoteAddr(),
                "AccessLog.RemoteAddr 是改写后的地址，不是原始对端");
        assertEquals("203.0.113.9", observation.tomcatRemoteAddr(),
                "org.apache.tomcat.remoteAddr 在本版本同样是改写后的地址");
        assertFalse(SOCKET_PEER.equals(observation.tomcatRemoteAddr()),
                "原始 socket 对端在应用侧不可得——这正是白名单必须 fail closed 的原因");
    }

    /**
     * @param internalProxies Tomcat 的 internal-proxies 正则；null 表示不挂转发阀门
     * @param trustedProxies  Tomcat 的 trusted-proxies 正则；null 表示不设置
     */
    private Observation observe(String internalProxies, String trustedProxies,
                                String forwardedFor, String forwardedProto) throws Exception {
        Path base = Files.createTempDirectory("tomcat-peer");
        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(base.toString());
        tomcat.setPort(0);
        Connector connector = tomcat.getConnector();
        if (internalProxies != null) {
            RemoteIpValve valve = new RemoteIpValve();
            valve.setInternalProxies(internalProxies);
            if (trustedProxies != null) {
                valve.setTrustedProxies(trustedProxies);
            }
            valve.setRequestAttributesEnabled(true);
            // Spring Boot 把 RemoteIpValve 挂在 Engine pipeline 上，这里保持一致。
            tomcat.getEngine().getPipeline().addValve(valve);
        }
        Context ctx = tomcat.addContext("", base.toString());
        Tomcat.addServlet(ctx, "echo", new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write(request.getRemoteAddr() + "|" + request.isSecure() + "|"
                        + attribute(request, "org.apache.tomcat.request.forwarded") + "|"
                        + attribute(request, "org.apache.catalina.AccessLog.RemoteAddr") + "|"
                        + attribute(request, "org.apache.tomcat.remoteAddr"));
            }
        });
        ctx.addServletMappingDecoded("/echo", "echo");
        tomcat.start();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + connector.getLocalPort() + "/echo"));
            if (forwardedFor != null) {
                builder.header("X-Forwarded-For", forwardedFor);
            }
            if (forwardedProto != null) {
                builder.header("X-Forwarded-Proto", forwardedProto);
            }
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String[] parts = response.body().split("\\|", -1);
            return new Observation(parts[0], Boolean.parseBoolean(parts[1]),
                    nullable(parts[2]), nullable(parts[3]), nullable(parts[4]));
        } finally {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    /** 把 servlet 输出里的占位符还原成 null。 */
    private static String nullable(String value) {
        return "-".equals(value) ? null : value;
    }

    private static String attribute(HttpServletRequest request, String name) {
        Object value = request.getAttribute(name);
        return value == null ? "-" : String.valueOf(value);
    }
}
