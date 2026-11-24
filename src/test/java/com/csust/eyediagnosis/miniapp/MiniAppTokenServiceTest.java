package com.csust.eyediagnosis.miniapp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MiniAppTokenServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private MiniAppTokenService tokenService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        WeChatMiniAppProperties properties = new WeChatMiniAppProperties();
        properties.setTokenTtlDays(30);
        tokenService = new MiniAppTokenService(redisTemplate, properties);
    }

    @Test
    void issuedTokenIsStoredHashedAndNeverAsPlaintext() {
        MiniAppTokenService.IssuedToken issued = tokenService.issue(42L);

        assertTrue(issued.token().length() >= 32);
        assertEquals(30L * 24 * 3600, issued.expiresInSeconds());
        // key 是 SHA-256，不含原始令牌；值是可解析的 userId。
        verify(valueOperations).set(org.mockito.ArgumentMatchers.argThat(key ->
                        key.startsWith("mp:token:") && !key.contains(issued.token()) && key.length() == "mp:token:".length() + 64),
                eq("42"), eq(30L * 24 * 3600), eq(TimeUnit.SECONDS));
    }

    @Test
    void twoIssuesProduceDifferentTokens() {
        assertNotEquals(tokenService.issue(1L).token(), tokenService.issue(1L).token());
    }

    @Test
    void resolveReturnsUserIdAndSlidesExpiry() {
        when(valueOperations.get(anyString())).thenReturn("7");

        assertEquals(7L, tokenService.resolve("some-token"));
        verify(redisTemplate).expire(anyString(), eq(30L * 24 * 3600), eq(TimeUnit.SECONDS));
    }

    @Test
    void unknownTokenResolvesToNullWithoutTouchingExpiry() {
        when(valueOperations.get(anyString())).thenReturn(null);

        assertNull(tokenService.resolve("expired-token"));
        verify(redisTemplate, never()).expire(anyString(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void corruptedValueIsDeletedAndTreatedAsInvalid() {
        when(valueOperations.get(anyString())).thenReturn("not-a-number");

        assertNull(tokenService.resolve("broken"));
        verify(redisTemplate).delete(anyString());
    }

    @Test
    void revokeDeletesKey() {
        tokenService.revoke("abc");

        verify(redisTemplate).delete(anyString());
    }

    @Test
    void blankTokenIsIgnored() {
        assertNull(tokenService.resolve(" "));
        tokenService.revoke(null);

        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void parseBearerAcceptsCaseInsensitivePrefixOnly() {
        assertEquals("abc", MiniAppTokenService.parseBearer("Bearer abc"));
        assertEquals("abc", MiniAppTokenService.parseBearer("bearer  abc "));
        assertNull(MiniAppTokenService.parseBearer("Basic abc"));
        assertNull(MiniAppTokenService.parseBearer("Bearer "));
        assertNull(MiniAppTokenService.parseBearer(null));
        assertNull(MiniAppTokenService.parseBearer("Token abc"));
    }
}
