package com.csust.eyediagnosis.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csust.eyediagnosis.model.vo.news.NewsVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * L1 tests for {@link NewsListCacheService}: post-commit Redis invalidation, intra-tx coalescing,
 * eviction-failure resilience, stale-generation bypass, and concurrent write safety.
 */
class NewsListCacheServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private JdbcTemplate jdbc;
    private NewsListCacheService cacheService;
    private AtomicLong dbEpoch;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        jdbc = mock(JdbcTemplate.class);
        dbEpoch = new AtomicLong(0L);

        when(jdbc.update(anyString(), eq(NewsListCacheService.NEWS_LIST_SCOPE))).thenAnswer(inv -> {
            dbEpoch.incrementAndGet();
            return 1;
        });
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(NewsListCacheService.NEWS_LIST_SCOPE)))
                .thenAnswer(inv -> dbEpoch.get());
        when(jdbc.query(anyString(), any(RowMapper.class), eq(NewsListCacheService.NEWS_LIST_SCOPE)))
                .thenAnswer(inv -> List.of(dbEpoch.get()));

        cacheService = new NewsListCacheService(redis, jdbc);
    }

    @AfterEach
    void cleanSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            for (TransactionSynchronization sync : List.copyOf(TransactionSynchronizationManager.getSynchronizations())) {
                sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void afterCommitEvictsRedisOnlyOnCommitAndNeverOnRollback() {
        TransactionSynchronizationManager.initSynchronization();
        long epoch1 = cacheService.bumpEpochInTransaction();
        assertEquals(1L, epoch1);
        verifyNoInteractions(valueOps);

        // Simulate rollback: afterCommit must NOT run.
        for (TransactionSynchronization sync : List.copyOf(TransactionSynchronizationManager.getSynchronizations())) {
            sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        TransactionSynchronizationManager.clearSynchronization();
        verifyNoInteractions(valueOps);

        // Simulate committed transaction: afterCommit invalidates Redis generation.
        TransactionSynchronizationManager.initSynchronization();
        when(valueOps.increment(NewsListCacheService.NEWS_LIST_CACHE_GENERATION)).thenReturn(2L);
        long epoch2 = cacheService.bumpEpochInTransaction();
        assertEquals(2L, epoch2);
        verifyNoInteractions(valueOps);

        for (TransactionSynchronization sync : List.copyOf(TransactionSynchronizationManager.getSynchronizations())) {
            sync.afterCommit();
            sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
        TransactionSynchronizationManager.clearSynchronization();
        verify(valueOps, times(1)).increment(NewsListCacheService.NEWS_LIST_CACHE_GENERATION);
    }

    @Test
    void coalescesMultipleEpochBumpsWithinSingleTransactionIntoOneRedisEviction() {
        when(valueOps.increment(NewsListCacheService.NEWS_LIST_CACHE_GENERATION)).thenReturn(1L);

        TransactionSynchronizationManager.initSynchronization();
        assertEquals(1L, cacheService.bumpEpochInTransaction());
        assertEquals(2L, cacheService.bumpEpochInTransaction());
        assertEquals(3L, cacheService.bumpEpochInTransaction());

        verifyNoInteractions(valueOps);

        for (TransactionSynchronization sync : List.copyOf(TransactionSynchronizationManager.getSynchronizations())) {
            sync.afterCommit();
            sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
        TransactionSynchronizationManager.clearSynchronization();

        verify(valueOps, times(1)).increment(NewsListCacheService.NEWS_LIST_CACHE_GENERATION);
        verify(valueOps, times(1)).set(NewsListCacheService.NEWS_LIST_CACHE_GENERATION, "3");
    }

    @Test
    void evictionFailureDoesNotThrowAndNextReadIgnoresAheadRedisGeneration() {
        when(valueOps.increment(NewsListCacheService.NEWS_LIST_CACHE_GENERATION))
                .thenThrow(new RedisConnectionFailureException("redis down during eviction"));

        dbEpoch.set(5L);
        assertDoesNotThrow(() -> cacheService.scheduleEvictionAfterCommit(5L));
        assertEquals(1L, cacheService.getEvictionFailureCount());

        // A legacy Redis generation ahead of MySQL must not authorize cached pages.
        String staleKey = NewsListCacheService.NEWS_LIST_CACHE_PREFIX
                + DigestUtils.md5DigestAsHex("50:q=visible".getBytes(StandardCharsets.UTF_8));
        String freshKey = NewsListCacheService.NEWS_LIST_CACHE_PREFIX
                + DigestUtils.md5DigestAsHex("5:q=visible".getBytes(StandardCharsets.UTF_8));
        when(valueOps.get(NewsListCacheService.NEWS_LIST_CACHE_GENERATION)).thenReturn("50");
        when(valueOps.get(staleKey)).thenReturn("{\"records\":[{\"id\":1}]}");
        when(valueOps.get(freshKey)).thenReturn(null);

        NewsListCacheService.CacheReadResult result = cacheService.getCachedPage("q=visible");
        assertFalse(result.hit());
        assertNull(result.page());
        assertEquals(5L, result.epoch());
        verify(valueOps, never()).get(NewsListCacheService.NEWS_LIST_CACHE_GENERATION);
        verify(valueOps, never()).get(staleKey);
    }

    @Test
    void redisOutageDuringReadAndWriteFallsBackToDatabaseWithoutError() {
        dbEpoch.set(2L);
        when(valueOps.get(anyString())).thenThrow(new RedisConnectionFailureException("read outage"));
        doAnswer(inv -> {
            throw new RedisConnectionFailureException("write outage");
        }).when(valueOps).set(anyString(), anyString(), anyLong(), any());

        NewsListCacheService.CacheReadResult read = assertDoesNotThrow(() -> cacheService.getCachedPage("q=outage"));
        assertFalse(read.hit());
        assertEquals(2L, read.epoch());
        assertDoesNotThrow(() -> cacheService.putCachedPage(2L, "q=outage", new Page<>()));
    }

    @Test
    void skipsCacheWriteWhenDbEpochAdvancesConcurrentlyDuringRead() {
        dbEpoch.set(3L);
        cacheService.putCachedPage(2L, "q=race", new Page<>());
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any());
    }
}
