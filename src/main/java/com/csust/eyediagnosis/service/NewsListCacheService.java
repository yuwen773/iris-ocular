package com.csust.eyediagnosis.service;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csust.eyediagnosis.model.vo.news.NewsVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Post-commit Redis cache invalidation and durable epoch fail-closed reader for news lists (WP-04).
 *
 * <p>Every committed mutation on {@code t_news} atomically advances {@code t_news_cache_epoch}
 * inside the write transaction and registers a single coalesced {@code afterCommit()} hook to
 * advance Redis {@code diagnosis:newsListGeneration}. If Redis eviction fails or lags behind the
 * committed DB epoch, reads bypass stale cached pages using the DB epoch and self-heal Redis,
 * guaranteeing withdrawn/deleted articles never remain visible to readers.
 */
@Slf4j
@Service
public class NewsListCacheService {

    public static final String NEWS_LIST_CACHE_PREFIX = "diagnosis:listNewsVOByPage ";
    public static final String NEWS_LIST_CACHE_GENERATION = "diagnosis:newsListGeneration";
    public static final String NEWS_LIST_SCOPE = "news_list";

    private final StringRedisTemplate stringRedisTemplate;
    private final JdbcTemplate jdbc;
    private final AtomicLong localMinEpoch = new AtomicLong(0L);
    private final AtomicLong evictionFailureCount = new AtomicLong(0L);
    private final Object txResourceKey = new Object();

    @Autowired
    public NewsListCacheService(@Autowired(required = false) StringRedisTemplate stringRedisTemplate,
                                @Autowired(required = false) JdbcTemplate jdbc) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.jdbc = jdbc;
    }

    public record CacheReadResult(long epoch, Page<NewsVO> page) {
        public boolean hit() {
            return page != null;
        }
    }

    /**
     * Atomically advances the durable cache epoch inside the caller's active write transaction and
     * schedules a coalesced post-commit Redis invalidation.
     */
    public long bumpEpochInTransaction() {
        long nextEpoch;
        if (jdbc != null) {
            jdbc.update("""
                    INSERT INTO t_news_cache_epoch (scope, epoch, updatedAt)
                    VALUES (?, 1, UTC_TIMESTAMP(6))
                    ON DUPLICATE KEY UPDATE
                        epoch = epoch + 1,
                        updatedAt = UTC_TIMESTAMP(6)
                    """, NEWS_LIST_SCOPE);
            Long stored = jdbc.queryForObject(
                    "SELECT epoch FROM t_news_cache_epoch WHERE scope = ?",
                    Long.class, NEWS_LIST_SCOPE);
            nextEpoch = stored == null ? 1L : stored;
        } else {
            nextEpoch = localMinEpoch.incrementAndGet();
        }
        scheduleEvictionAfterCommit(nextEpoch);
        return nextEpoch;
    }

    /**
     * Schedules Redis cache generation invalidation strictly after transaction commit, coalescing
     * repeated calls within the same transaction into a single invalidation at max epoch.
     */
    public void scheduleEvictionAfterCommit(long targetEpoch) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            PendingEpochHolder existing = (PendingEpochHolder) TransactionSynchronizationManager.getResource(txResourceKey);
            if (existing != null) {
                existing.maxEpoch.accumulateAndGet(targetEpoch, Math::max);
                return;
            }
            PendingEpochHolder created = new PendingEpochHolder(targetEpoch);
            TransactionSynchronizationManager.bindResource(txResourceKey, created);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictRedisBestEffort(created.maxEpoch.get());
                }

                @Override
                public void afterCompletion(int status) {
                    if (TransactionSynchronizationManager.hasResource(txResourceKey)) {
                        TransactionSynchronizationManager.unbindResource(txResourceKey);
                    }
                }
            });
            return;
        }
        evictRedisBestEffort(targetEpoch);
    }

    /**
     * Reads cached page while defending against stale Redis generations when eviction failed or
     * Redis lost keys. Any Redis failure degrades gracefully to a cache miss at DB epoch.
     */
    public CacheReadResult getCachedPage(String queryCondition) {
        long dbEpoch = readAuthoritativeDbEpoch();
        if (stringRedisTemplate == null) {
            return new CacheReadResult(dbEpoch, null);
        }
        try {
            ValueOperations<String, String> ops = stringRedisTemplate.opsForValue();
            long redisEpoch = parseEpoch(ops.get(NEWS_LIST_CACHE_GENERATION));
            long effectiveEpoch = Math.max(dbEpoch, redisEpoch);
            if (dbEpoch > redisEpoch) {
                // Fail-closed: prior eviction failed or Redis was reset; self-heal generation best-effort
                // and compute key against effectiveEpoch (= dbEpoch) so stale cached pages cannot hit.
                ops.set(NEWS_LIST_CACHE_GENERATION, String.valueOf(dbEpoch));
            }
            String redisKey = keyForEpoch(effectiveEpoch, queryCondition);
            String cacheValue = ops.get(redisKey);
            if (StrUtil.isNotBlank(cacheValue)) {
                @SuppressWarnings("unchecked")
                Page<NewsVO> cachePage = JSONUtil.toBean(cacheValue, Page.class);
                return new CacheReadResult(effectiveEpoch, cachePage);
            }
            return new CacheReadResult(effectiveEpoch, null);
        } catch (RuntimeException ex) {
            log.warn("News list cache lookup failed, falling back to MySQL: {}", ex.getMessage());
            return new CacheReadResult(dbEpoch, null);
        }
    }

    /**
     * Stores page only if the durable DB epoch has not advanced since the read began, preventing
     * stale-read cache repopulation across concurrent withdrawals or edits.
     */
    public void putCachedPage(long snapshotEpoch, String queryCondition, Page<NewsVO> page) {
        if (stringRedisTemplate == null || page == null) {
            return;
        }
        long latestDbEpoch = readAuthoritativeDbEpoch();
        if (latestDbEpoch > snapshotEpoch) {
            return;
        }
        try {
            String redisKey = keyForEpoch(snapshotEpoch, queryCondition);
            String toCache = JSONUtil.toJsonStr(page);
            int ttlSeconds = 300 + RandomUtil.randomInt(0, 300);
            stringRedisTemplate.opsForValue().set(redisKey, toCache, ttlSeconds, TimeUnit.SECONDS);
        } catch (RuntimeException ex) {
            log.warn("News list cache write skipped due to Redis error: {}", ex.getMessage());
        }
    }

    public String buildCacheKey(String queryCondition) {
        long dbEpoch = readAuthoritativeDbEpoch();
        long redisEpoch = 0L;
        if (stringRedisTemplate != null) {
            try {
                redisEpoch = parseEpoch(stringRedisTemplate.opsForValue().get(NEWS_LIST_CACHE_GENERATION));
            } catch (RuntimeException ex) {
                log.warn("News list cache key read fell back to DB epoch: {}", ex.getMessage());
            }
        }
        return keyForEpoch(Math.max(dbEpoch, redisEpoch), queryCondition);
    }

    public long readAuthoritativeDbEpoch() {
        long dbEpoch = 0L;
        if (jdbc != null) {
            List<Long> rows = jdbc.query(
                    "SELECT epoch FROM t_news_cache_epoch WHERE scope = ?",
                    (rs, rowNum) -> rs.getLong(1),
                    NEWS_LIST_SCOPE
            );
            if (!rows.isEmpty()) {
                dbEpoch = rows.getFirst();
            }
        }
        return Math.max(dbEpoch, localMinEpoch.get());
    }

    public long getEvictionFailureCount() {
        return evictionFailureCount.get();
    }

    void resetLocalStateForTests() {
        localMinEpoch.set(0L);
        evictionFailureCount.set(0L);
    }

    private void evictRedisBestEffort(long targetEpoch) {
        if (stringRedisTemplate == null) {
            localMinEpoch.accumulateAndGet(targetEpoch, Math::max);
            return;
        }
        try {
            ValueOperations<String, String> ops = stringRedisTemplate.opsForValue();
            Long incremented = ops.increment(NEWS_LIST_CACHE_GENERATION);
            if (incremented == null || incremented < targetEpoch) {
                ops.set(NEWS_LIST_CACHE_GENERATION, String.valueOf(targetEpoch));
            }
        } catch (RuntimeException ex) {
            evictionFailureCount.incrementAndGet();
            localMinEpoch.accumulateAndGet(targetEpoch, Math::max);
            log.error("Post-commit Redis cache eviction failed at epoch={}; reads remain fail-closed via DB epoch: {}",
                    targetEpoch, ex.getMessage());
        }
    }

    private static String keyForEpoch(long epoch, String queryCondition) {
        String raw = epoch + ":" + (queryCondition == null ? "" : queryCondition);
        return NEWS_LIST_CACHE_PREFIX + DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static long parseEpoch(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(raw.trim()));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static final class PendingEpochHolder {
        private final AtomicLong maxEpoch;

        private PendingEpochHolder(long initialEpoch) {
            this.maxEpoch = new AtomicLong(initialEpoch);
        }
    }
}
