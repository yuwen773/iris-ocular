package com.csust.eyediagnosis.security;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 控制中心的 Session 绑定 CSRF Token。
 *
 * <p>Token 在会话内稳定存在：管理员先取 {@code GET /admin/control-center/csrf}，
 * 之后每个状态变更请求把同一个值放进 {@code X-CSRF-Token}。服务端只与当前
 * Session 里的值做常量时间比较，不依赖 Cookie 是否可被脚本读取。
 *
 * <p>会话失效（登出、过期、封禁导致 Session 被清理）时 token 随之失效；
 * 不跨会话复用，也不落库。
 */
@Component
public class CsrfTokenService {

    /** Session 属性名；不暴露给客户端。 */
    static final String SESSION_ATTRIBUTE = "iris.controlCenter.csrfToken";

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    /**
     * 返回当前会话的 token；没有则生成一个。
     *
     * <p>同一会话内保持稳定，避免并发请求各取一次 token 后互相覆盖。
     */
    public String issue(HttpSession session) {
        if (session == null) {
            throw new IllegalArgumentException("session is required");
        }
        synchronized (session) {
            Object existing = session.getAttribute(SESSION_ATTRIBUTE);
            if (existing instanceof String token && !token.isBlank()) {
                return token;
            }
            byte[] bytes = new byte[TOKEN_BYTES];
            random.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            session.setAttribute(SESSION_ATTRIBUTE, token);
            return token;
        }
    }

    /**
     * 会话内是否已签发 token（不生成新 token、不创建会话）。
     *
     * <p>供过滤器区分"从未取过 token"与"token 不匹配"两种失败原因。
     */
    public boolean hasToken(HttpSession session) {
        if (session == null) {
            return false;
        }
        Object existing = session.getAttribute(SESSION_ATTRIBUTE);
        return existing instanceof String token && !token.isBlank();
    }

    /**
     * 校验请求携带的 token 是否属于该会话。
     *
     * <p>使用 {@link MessageDigest#isEqual} 做常量时间比较；会话或 token 缺失一律失败。
     */
    public boolean matches(HttpSession session, String provided) {
        if (session == null || provided == null || provided.isBlank()) {
            return false;
        }
        Object expected = session.getAttribute(SESSION_ATTRIBUTE);
        if (!(expected instanceof String token) || token.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                provided.trim().getBytes(StandardCharsets.UTF_8));
    }

    /** 显式清理（登出或需要强制换 token 时调用）。 */
    public void clear(HttpSession session) {
        if (session != null) {
            session.removeAttribute(SESSION_ATTRIBUTE);
        }
    }
}
