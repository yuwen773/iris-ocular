package com.csust.eyediagnosis.agent.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 后端签发的短期图片引用。
 *
 * <p>格式：{@code iris-img://<payload>?exp=<unix 秒>&sig=<hex>}，
 * {@code payload} 是目标地址的 base64url（无填充），{@code sig} 是
 * {@code HMAC-SHA256(secret, payload + "|" + exp)}。
 *
 * <p>为什么要有这一层：算法侧的 MCP 工具过去接受「模型给什么 URL 就读什么」，
 * 那个服务同时是内网探测器和公网抓取器。改成只认后端签发的引用之后：
 * <ul>
 *   <li>模型无法伪造——签名密钥只在后端；</li>
 *   <li>有效期短——泄漏的引用很快失效；</li>
 *   <li>目标地址由后端决定——模型不能把工具指向任意主机。</li>
 * </ul>
 *
 * <p>密钥来自环境变量注入的配置；没有配置时签发直接失败（不放行），不生成
 * 「看起来能用、其实验不过」的引用。
 */
@Component
public class ImageReferenceSigner {

    /** 引用协议前缀；算法侧按同一前缀识别。 */
    public static final String SCHEME = "iris-img://";

    /** 有效期上限（秒）：与算法侧的校验上限保持一致。 */
    static final int MAX_TTL_SECONDS = 15 * 60;

    @Value("${eye-diagnosis.agent.image-reference.secret:}")
    private String secret;

    // 初值与 SpEL 默认值一致：单测直接 new ImageReferenceSigner()，不经过 Spring 注入。
    @Value("${eye-diagnosis.agent.image-reference.ttl-seconds:300}")
    private int ttlSeconds = 300;

    /** 是否配置了密钥（只报有没有，不报内容）。 */
    public boolean enabled() {
        return secret != null && !secret.isBlank();
    }

    /**
     * 签发一个短期引用。
     *
     * @param targetUrl 后端决定的目标地址（通常是短时预签名 URL）
     */
    public String sign(String targetUrl) {
        if (!enabled()) {
            throw new IllegalStateException("图片引用密钥未配置（eye-diagnosis.agent.image-reference.secret）");
        }
        if (targetUrl == null
                || !(targetUrl.startsWith("http://") || targetUrl.startsWith("https://"))) {
            throw new IllegalArgumentException("图片引用只支持 http/https 目标");
        }
        int ttl = Math.min(Math.max(ttlSeconds, 1), MAX_TTL_SECONDS);
        long expiresAt = Instant.now().getEpochSecond() + ttl;
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(targetUrl.getBytes(StandardCharsets.UTF_8));
        return SCHEME + payload + "?exp=" + expiresAt + "&sig=" + signPayload(payload, expiresAt);
    }

    /**
     * 对载荷与到期时间签名。
     *
     * <p>与算法侧 {@code mcp_server/image_ref.py} 的签名串完全一致（{@code payload|exp}），
     * 两侧用同一组测试向量锁死：一边改了格式，另一边立刻红。
     */
    String signPayload(String payload, long expiresAt) {
        if (!enabled()) {
            throw new IllegalStateException("图片引用密钥未配置（eye-diagnosis.agent.image-reference.secret）");
        }
        return signature(payload, expiresAt);
    }

    private String signature(String payload, long expiresAt) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((payload + "|" + expiresAt).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("图片引用签名失败", e);
        }
    }
}
