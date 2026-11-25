package com.csust.eyediagnosis.service;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csust.eyediagnosis.model.vo.news.NewsVO;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.DigestUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Post-commit Redis cache invalidation and durable epoch fail-closed reader for news lists (WP-04).
 *
 * <p>Every committed mutation on {@code t_news} atomically advances {@code t_news_cache_epoch}
 * inside the write transaction and registers a single coalesced {@code afterCommit()} hook to
 * advance the legacy Redis generation for older readers. Current readers use the committed
 * database epoch alone in a dedicated key namespace.
 */
@Slf4j
@Service
public class NewsListCacheService {

    public static final String NEWS_LIST_CACHE_PREFIX = "diagnosis:listNewsVOByPage:v2:";
    public static final String NEWS_LIST_CACHE_GENERATION = "diagnosis:newsListGeneration";
    public static final String NEWS_LIST_SCOPE = "news_list";
    private static final Duration REDIS_WAIT = Duration.ofMillis(500);

    private final ReactiveStringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    // Lettuce can acquire a shared reactive connection synchronously on its first subscription.
    private final Scheduler redisSubscriptions = Schedulers.newBoundedElastic(2, 32, "news-list-redis");
    private final AtomicLong localMinEpoch = new AtomicLong(0L);
    private final AtomicLong evictionFailureCount = new AtomicLong(0L);
    private final Object txResourceKey = new Object();

    @Autowired
    public NewsListCacheService(@Autowired(required = false) ReactiveStringRedisTemplate redis,
                                @Autowired(required = false) JdbcTemplate jdbc) {
        this.redis = redis;
        this.jdbc = jdbc;
    }

    public record CacheReadResult(long epoch, Page<NewsVO> page, boolean redisAvailable) {
        public boolean hit() {
            return page != null;
        }
    }

    @PreDestroy
    void shutdownRedisSubscriptions() {
        redisSubscriptions.dispose();
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
        if (redis == null) {
            return new CacheReadResult(dbEpoch, null, false);
        }
        try {
            String redisKey = keyForEpoch(dbEpoch, queryCondition);
            String cacheValue = awaitRedis(redis.opsForValue().get(redisKey));
            if (StrUtil.isNotBlank(cacheValue)) {
                @SuppressWarnings("unchecked")
                Page<NewsVO> cachePage = JSONUtil.toBean(cacheValue, Page.class);
                return new CacheReadResult(dbEpoch, cachePage, true);
            }
            return new CacheReadResult(dbEpoch, null, true);
        } catch (RuntimeException ex) {
            log.warn("News list cache lookup failed, falling back to MySQL: {}", ex.getMessage());
            return new CacheReadResult(dbEpoch, null, false);
        }
    }

    /**
     * Stores page only if the durable DB epoch has not advanced since the read began, preventing
     * stale-read cache repopulation across concurrent withdrawals or edits.
     */
    public void putCachedPage(long snapshotEpoch, String queryCondition, Page<NewsVO> page) {
        if (redis == null || page == null) {
            return;
        }
        long latestDbEpoch = readAuthoritativeDbEpoch();
        if (latestDbEpoch != snapshotEpoch) {
            return;
        }
        try {
            String redisKey = keyForEpoch(snapshotEpoch, queryCondition);
            String toCache = JSONUtil.toJsonStr(page);
            int ttlSeconds = 300 + RandomUtil.randomInt(0, 300);
            awaitRedis(redis.opsForValue().set(redisKey, toCache, Duration.ofSeconds(ttlSeconds)));
        } catch (RuntimeException ex) {
            log.warn("News list cache write skipped due to Redis error: {}", ex.getMessage());
        }
    }

    public String buildCacheKey(String queryCondition) {
        return keyForEpoch(readAuthoritativeDbEpoch(), queryCondition);
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
        if (redis == null) {
            localMinEpoch.accumulateAndGet(targetEpoch, Math::max);
            return;
        }
        try {
            var ops = redis.opsForValue();
            Long incremented = awaitRedis(ops.increment(NEWS_LIST_CACHE_GENERATION));
            if (incremented == null || incremented < targetEpoch) {
                awaitRedis(ops.set(NEWS_LIST_CACHE_GENERATION, String.valueOf(targetEpoch)));
            }
        } catch (RuntimeException ex) {
            evictionFailureCount.incrementAndGet();
            localMinEpoch.accumulateAndGet(targetEpoch, Math::max);
            log.error("Post-commit Redis cache eviction failed at epoch={}; reads remain fail-closed via DB epoch: {}",
                    targetEpoch, ex.getMessage());
        }
    }

    private <T> T awaitRedis(Mono<T> operation) {
        return operation.subscribeOn(redisSubscriptions).block(REDIS_WAIT);
    }

    private static String keyForEpoch(long epoch, String queryCondition) {
        String raw = epoch + ":" + (queryCondition == null ? "" : queryCondition);
        return NEWS_LIST_CACHE_PREFIX + DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static final class PendingEpochHolder {
        private final AtomicLong maxEpoch;

        private PendingEpochHolder(long initialEpoch) {
            this.maxEpoch = new AtomicLong(initialEpoch);
        }
    }
}
