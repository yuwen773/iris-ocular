package com.csust.eyediagnosis.miniapp;

import com.csust.eyediagnosis.constant.MiniAppConstant;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * 小程序登录令牌：签发、校验（滑动续期）、吊销。
 *
 * <p>令牌以 Redis 为唯一真源，key 是令牌的 SHA-256，而不是令牌本身——Redis 转储、
 * 监控导出或误开的 keys 命令都不会直接吐出可用的凭据。
 */
@Service
public class MiniAppTokenService {

    private static final String KEY_PREFIX = "mp:token:";
    private static final int TOKEN_BYTES = 32;

    private final StringRedisTemplate redisTemplate;
    private final WeChatMiniAppProperties properties;
    private final SecureRandom random = new SecureRandom();

    public MiniAppTokenService(StringRedisTemplate redisTemplate, WeChatMiniAppProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public IssuedToken issue(Long userId) {
        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        long ttlSeconds = properties.tokenTtlSeconds();
        redisTemplate.opsForValue().set(cacheKey(token), String.valueOf(userId), ttlSeconds, TimeUnit.SECONDS);
        return new IssuedToken(token, ttlSeconds);
    }

    /**
     * 校验令牌并滑动续期。
     *
     * @return 令牌有效时返回 t_user.id，否则 {@code null}
     */
    public Long resolve(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String key = cacheKey(token);
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return null;
        }
        try {
            long userId = Long.parseLong(value);
            redisTemplate.expire(key, properties.tokenTtlSeconds(), TimeUnit.SECONDS);
            return userId;
        } catch (NumberFormatException e) {
            // 值被写坏时按无效令牌处理，顺手删掉，避免每次都走到这里。
            redisTemplate.delete(key);
            return null;
        }
    }

    public void revoke(String token) {
        if (token != null && !token.isBlank()) {
            redisTemplate.delete(cacheKey(token));
        }
    }

    /**
     * 从 {@code Authorization} 头里取出令牌，不是 Bearer 形式时返回 {@code null}。
     */
    public static String parseBearer(String authorizationHeader) {
        if (authorizationHeader == null) {
            return null;
        }
        String header = authorizationHeader.trim();
        if (header.length() <= MiniAppConstant.BEARER_PREFIX.length()) {
            return null;
        }
        if (!header.regionMatches(true, 0, MiniAppConstant.BEARER_PREFIX, 0,
                MiniAppConstant.BEARER_PREFIX.length())) {
            return null;
        }
        String token = header.substring(MiniAppConstant.BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private String cacheKey(String token) {
        return KEY_PREFIX + sha256Hex(token);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public record IssuedToken(String token, long expiresInSeconds) {
    }
}
