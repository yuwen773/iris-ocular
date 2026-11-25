package com.csust.eyediagnosis.newsingestion;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Locale;

/**
 * Worker 通道的「对端是谁」判定。
 *
 * <p>本类解决一个具体歧义：{@code request.getRemoteAddr()} 在不同转发配置下含义不同。
 * 实测（真实 Tomcat 10.1，见 {@code NewsForwardedPeerSemanticsTest}）：
 *
 * <ul>
 *   <li>未启用转发处理：它就是 socket 对端，任何请求头都改不了它；</li>
 *   <li>启用 native 转发处理且对端属于可信代理：它被改写成 {@code X-Forwarded-For} 的
 *       最右一跳——也就是**代理转述的客户端地址**，取值取决于反代是否覆盖了该头；</li>
 *   <li>启用 native 转发处理但对端不可信：Tomcat 完全忽略转发头，它仍是真实对端。</li>
 * </ul>
 *
 * <p>关键点是第二种情况下**原始对端在应用侧不可得**（Tomcat 不暴露被改写前的地址）。
 * 因此凡带转发头的请求，都不能当作「直连对端」来比对 IP 白名单——那是把可伪造的
 * 客户端地址当成物理边界。调用方据此 fail closed。
 */
public final class NewsWorkerPeerTrust {

    /** Tomcat 转发处理的标记：请求被可信代理改写过时为 {@code "true"}。 */
    public static final String TOMCAT_FORWARDED_ATTRIBUTE = "org.apache.tomcat.request.forwarded";

    /**
     * 出现即视为「非直连」的转发头。真正的 Worker 不会带这些头；
     * 反代带的头说明这条请求的对端不是 Worker 本身。
     */
    private static final List<String> FORWARDING_HEADERS = List.of(
            "forwarded",
            "x-forwarded-for",
            "x-forwarded-by",
            "x-forwarded-host",
            "x-forwarded-port",
            "x-forwarded-proto",
            "x-forwarded-server",
            "x-real-ip");

    private NewsWorkerPeerTrust() {
    }

    /**
     * @param address                {@code request.getRemoteAddr()} 的当前取值（可能是转发改写后的地址）
     * @param forwardingHeaderPresent 是否出现转发头或 Tomcat 的转发处理标记
     */
    public record Peer(String address, boolean forwardingHeaderPresent) {

        /** 是否可以当作「直连对端」来比对白名单。 */
        public boolean directPeerKnown() {
            return !forwardingHeaderPresent;
        }
    }

    public static Peer resolve(HttpServletRequest request) {
        boolean forwardingHeaderPresent = request.getAttribute(TOMCAT_FORWARDED_ATTRIBUTE) != null;
        if (!forwardingHeaderPresent) {
            for (String header : FORWARDING_HEADERS) {
                String value = request.getHeader(header);
                if (value != null && !value.isBlank()) {
                    forwardingHeaderPresent = true;
                    break;
                }
            }
        }
        return new Peer(request.getRemoteAddr(), forwardingHeaderPresent);
    }

    /** 供日志与错误信息使用：把头部名归一化，避免把攻击者提供的头值写进日志。 */
    public static String describeForwardingHeaders(HttpServletRequest request) {
        for (String header : FORWARDING_HEADERS) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank()) {
                return header.toLowerCase(Locale.ROOT);
            }
        }
        return request.getAttribute(TOMCAT_FORWARDED_ATTRIBUTE) != null ? "tomcat-forwarded" : "none";
    }
}
