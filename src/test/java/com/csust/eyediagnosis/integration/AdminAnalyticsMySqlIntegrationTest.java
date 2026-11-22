package com.csust.eyediagnosis.integration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.csust.eyediagnosis.mapper.AdminAnalyticsMapper;
import com.csust.eyediagnosis.model.vo.stats.AdminAnalyticsVO;
import com.csust.eyediagnosis.model.vo.stats.HistogramBucketVO;
import com.csust.eyediagnosis.model.vo.stats.ReasonStatVO;
import com.csust.eyediagnosis.model.vo.stats.StatusStatVO;
import com.csust.eyediagnosis.service.AdminAnalyticsService;
import com.csust.eyediagnosis.service.impl.AdminAnalyticsServiceImpl;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分析聚合的真实 SQL 语义。
 *
 * <p>Mockito 只能验证装配，验证不了 {@code GROUP BY}、窗口函数分位、
 * {@code AVG(CASE WHEN ...)} 与 NULL 分组。这里打真 MySQL 8，用合成数据
 * 覆盖 B1 的四条边界：历史复核、量纲未知、两套原因过滤、契约外取值。
 *
 * <p>需要 {@code FLYWAY_TEST_URL}（指向可建库的 MySQL 8 账号）；
 * 未设置时整类跳过，与既有集成测试一致。
 */
class AdminAnalyticsMySqlIntegrationTest {

    @Test
    void 分析读数在真实数据库上的口径() throws Exception {
        String url = System.getenv("FLYWAY_TEST_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "Set FLYWAY_TEST_URL for the analytics SQL gate");
        String username = System.getenv("FLYWAY_TEST_USERNAME");
        String password = System.getenv("FLYWAY_TEST_PASSWORD");
        String schema = "diagnosis_ci_analytics_" + UUID.randomUUID().toString().replace("-", "");

        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
        admin.execute("CREATE DATABASE `" + schema + "`");
        try {
            String databaseUrl = url.replaceFirst("(?<=/)[^/?]+(?=\\?|$)", schema);
            DriverManagerDataSource dataSource = new DriverManagerDataSource(databaseUrl, username, password);
            Flyway.configure().dataSource(dataSource)
                    .locations("classpath:db/migration", "classpath:db/callback")
                    .load().migrate();

            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            seed(jdbc);

            MybatisConfiguration config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(false);
            config.addMapper(AdminAnalyticsMapper.class);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(config);
            SqlSessionTemplate mappers = new SqlSessionTemplate(factory.getObject());

            AdminAnalyticsService service = new AdminAnalyticsServiceImpl();
            ReflectionTestUtils.setField(service, "adminAnalyticsMapper",
                    mappers.getMapper(AdminAnalyticsMapper.class));

            AdminAnalyticsVO vo = service.analytics(30);

            assertWindow(vo);
            assertReview(vo);
            assertRejection(vo);
            assertConfidence(vo);
            assertQuality(vo);
            assertStatuses(vo);
        } finally {
            admin.execute("DROP DATABASE `" + schema + "`");
        }
    }

    /**
     * 九条合成记录。
     *
     * <pre>
     * A  90   正常结论，质量 100
     * B  0.74 量纲未知，质量分 80（越界），多标签原因
     * C 120   越界置信度，拒识 QUALITY_FAIL，待复核
     * D NULL  拒识但原因是 "ok"（契约外），已复核，质量分空
     * E  40   LOW_CONFIDENCE，复核推翻，质量 75
     * F  70   历史状态 APPROVED（契约外状态）
     * G  55   已复核但缺复核时间
     * H  55   模型版本为空
     * I   1   量纲未知，OOD 状态契约外，校准状态为空
     * </pre>
     */
    private static void seed(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO t_user (id, userAccount, userPassword, userName, userRole, isDelete) "
                + "VALUES (7, 'reviewer7', 'x', '复核人甲', 'admin', 0), "
                + "(8, 'reviewer8', 'x', '复核人乙', 'admin', 0)");
        jdbc.update("INSERT INTO t_diagnosis "
                + "(url, userId, result, confidence, aiRejected, abstainReason, modelVersion, "
                + " humanReviewStatus, humanReviewerId, humanReviewedAt, qualityScore, qualityReasons, "
                + " oodStatus, calibrationStatus, createTime, isDelete) VALUES "
                + "('url-a', 1, '正常', 90,   0, NULL,             'm1', 'AI_ONLY',        NULL, NULL,                       100, NULL,            'NOT_EVALUATED', 'NOT_CALIBRATED', NOW(), 0), "
                + "('url-b', 1, '正常', 0.74, 0, NULL,             'm1', 'AI_ONLY',        NULL, NULL,                        80, 'blur,contrast', 'NOT_EVALUATED', 'NOT_CALIBRATED', NOW() - INTERVAL 1 DAY, 0), "
                + "('url-c', 1, NULL,   120,  1, 'QUALITY_FAIL',   'm1', 'AWAITING_REVIEW',NULL, NULL,                      NULL, NULL,            'NOT_EVALUATED', 'NOT_CALIBRATED', NOW() - INTERVAL 2 DAY, 0), "
                + "('url-d', 1, NULL,   NULL, 1, 'ok',             'm1', 'REVIEWED',        7, NOW() - INTERVAL 1 DAY,     NULL, 'weird',         'NOT_EVALUATED', 'NOT_CALIBRATED', NOW() - INTERVAL 5 DAY, 0), "
                + "('url-e', 1, '正常', 40,   0, 'LOW_CONFIDENCE', 'm1', 'OVERRIDDEN',      7, NOW() - INTERVAL 2 DAY,       75, NULL,            'NOT_EVALUATED', 'NOT_CALIBRATED', NOW() - INTERVAL 3 DAY, 0), "
                + "('url-f', 1, '正常', 70,   0, NULL,             'm1', 'APPROVED',     NULL, NULL,                        50, NULL,            'NOT_EVALUATED', 'NOT_CALIBRATED', NOW() - INTERVAL 6 DAY, 0), "
                + "('url-g', 1, '正常', 55,   0, NULL,             'm1', 'REVIEWED',        8, NULL,                        25, NULL,            'NOT_EVALUATED', 'NOT_CALIBRATED', NOW() - INTERVAL 7 DAY, 0), "
                + "('url-h', 1, '正常', 55,   0, NULL,             NULL, 'AI_ONLY',        NULL, NULL,                         0, NULL,            'NOT_EVALUATED', 'NOT_CALIBRATED', NOW() - INTERVAL 8 DAY, 0), "
                + "('url-i', 1, NULL,   1,    0, NULL,             'm1', 'AI_ONLY',        NULL, NULL,                       100, NULL,            'WEIRD',         NULL,             NOW() - INTERVAL 9 DAY, 0)");
    }

    private static void assertWindow(AdminAnalyticsVO vo) {
        assertEquals(30, vo.getDays());
        assertEquals(java.time.LocalDate.now().toString(), vo.getRangeEnd());
        assertEquals("HISTORICAL_ONLY", vo.getReview().getDataStatus());
        assertEquals(1, vo.getReview().getAwaitingTotal(), "只有 C 在等待复核");
    }

    private static void assertReview(AdminAnalyticsVO vo) {
        // A-07/A-08 按当前状态：C、D、E、G 进过队列，其中 D、E、G 已完成
        assertEquals(4, vo.getReview().getQueuedInWindow());
        assertEquals(3, vo.getReview().getCompletedInWindow());
        assertEquals(0.75, vo.getReview().getCompletionRate());
        // A-09 按复核时间：D、E 在窗口内完成，E 被推翻
        assertEquals(2, vo.getReview().getReviewedInWindow());
        assertEquals(1, vo.getReview().getOverriddenInWindow());
        assertEquals(0.5, vo.getReview().getOverrideRate());

        // 端到端时延：D = 4 天（5760 分），E = 1 天（1440 分）
        assertEquals(2, vo.getReview().getLatencySamples());
        assertEquals(3600.0, vo.getReview().getAvgMinutes());
        assertEquals(1440.0, vo.getReview().getP50Minutes());
        assertEquals(5760.0, vo.getReview().getP90Minutes());
        assertEquals(5760.0, vo.getReview().getMaxMinutes());

        assertEquals(1, vo.getReview().getReviewers().size());
        assertEquals(7L, vo.getReview().getReviewers().get(0).getReviewerId());
        assertEquals("复核人甲", vo.getReview().getReviewers().get(0).getReviewerName());
        assertEquals(2, vo.getReview().getReviewers().get(0).getReviewed());
        assertEquals(1, vo.getReview().getReviewers().get(0).getOverridden());
        assertEquals(3600.0, vo.getReview().getReviewers().get(0).getAvgMinutes());

        // A-12：队列里 C(QUALITY_FAIL)、D(ok)、E(LOW_CONFIDENCE)、G(空)
        assertEquals(1, vo.getReview().getUnclassifiedReasonCount());
        assertEquals(1, vo.getReview().getUnknownReasonCount());
        assertEquals(1, findReason(vo.getReview().getQueueReasons(), "LOW_CONFIDENCE").getCount());
        assertEquals(1, findReason(vo.getReview().getQueueReasons(), "QUALITY_FAIL").getCount());
        assertEquals(1, findReason(vo.getReview().getQueueReasons(), "UNCLASSIFIED").getCount());
        assertEquals(1, findReason(vo.getReview().getQueueReasons(), "UNRECORDED").getCount());
        assertEquals(0.25, findReason(vo.getReview().getQueueReasons(), "LOW_CONFIDENCE").getShare());
    }

    private static void assertRejection(AdminAnalyticsVO vo) {
        assertEquals(9, vo.getRejection().getTotal());
        assertEquals(2, vo.getRejection().getRejected(), "只认 aiRejected = 1（C、D）");
        assertEquals(0.2222, vo.getRejection().getRate());

        // A-05：C 的 QUALITY_FAIL 与 D 的 "ok"（契约外）
        assertEquals(1, vo.getRejection().getUnclassifiedReasonCount());
        assertEquals(0, vo.getRejection().getUnknownReasonCount());
        assertEquals(1, findReason(vo.getRejection().getReasons(), "QUALITY_FAIL").getCount());
        assertEquals(1, findReason(vo.getRejection().getReasons(), "UNCLASSIFIED").getCount());
    }

    private static void assertConfidence(AdminAnalyticsVO vo) {
        // 只有 1 < c <= 100 参与：A90 / E40 / F70 / G55 / H55
        assertEquals(5, vo.getModel().getConfidence().getSamples());
        assertEquals(62.0, vo.getModel().getConfidence().getAvg());
        // B 0.74 与 I 1 是量纲未知；C 120 越界；D 空
        assertEquals(2, vo.getDataQuality().getAmbiguousConfidence());
        assertEquals(1, vo.getDataQuality().getConfidenceOutOfRange());
        assertEquals(1, vo.getDataQuality().getConfidenceMissing());

        List<HistogramBucketVO> buckets = vo.getModel().getConfidenceHistogram();
        assertEquals(10, buckets.size(), "固定十桶，横轴连续");
        assertEquals(1, findBucket(buckets, "40–50").getCount());
        assertEquals(2, findBucket(buckets, "50–60").getCount());
        assertEquals(1, findBucket(buckets, "70–80").getCount());
        assertEquals(1, findBucket(buckets, "90–100").getCount());
        assertEquals(0, findBucket(buckets, "0–10").getCount(), "量纲未知的样本不得落桶");

        // 模型版本：m1 八条，版本为空的一条归 UNRECORDED
        assertEquals(2, vo.getModel().getVersions().size());
        var m1 = vo.getModel().getVersions().stream()
                .filter(row -> "m1".equals(row.getModelVersion())).findFirst().orElseThrow();
        assertEquals(8, m1.getCount());
        assertEquals(2, m1.getRejected());
        assertEquals(0.25, m1.getRejectionRate());
        assertEquals(4, m1.getConfidenceSamples());
        assertEquals(63.75, m1.getAvgConfidence());
        assertEquals(2, m1.getAmbiguousConfidence());
        assertTrue(vo.getModel().getVersions().stream()
                .anyMatch(row -> "UNRECORDED".equals(row.getModelVersion())));
    }

    private static void assertQuality(AdminAnalyticsVO vo) {
        assertEquals(5, vo.getModel().getQualityScores().size());
        assertEquals(2, countScore(vo, 100));
        assertEquals(1, countScore(vo, 75));
        assertEquals(1, countScore(vo, 50));
        assertEquals(1, countScore(vo, 25));
        assertEquals(1, countScore(vo, 0));
        assertEquals(2, vo.getModel().getQualityUnknown(), "C、D 质量分为空");
        assertEquals(1, vo.getModel().getQualityOutOfContract(), "B 的 80 不在契约值内");
        assertEquals(1, vo.getDataQuality().getQualityOutOfContract());

        assertEquals(1, findReason(vo.getModel().getQualityReasons(), "blur").getCount());
        assertEquals(1, findReason(vo.getModel().getQualityReasons(), "contrast").getCount());
        assertEquals(1, vo.getModel().getUnclassifiedQualityReasonCount());
    }

    private static void assertStatuses(AdminAnalyticsVO vo) {
        assertEquals(8, findStatus(vo.getModel().getOodStatuses(), "NOT_EVALUATED").getCount());
        assertEquals(1, findStatus(vo.getModel().getOodStatuses(), "WEIRD").getCount());
        assertEquals(1, findStatus(vo.getModel().getCalibrationStatuses(), "UNRECORDED").getCount());

        // 契约外状态原样进数据质量，不被丢弃
        assertEquals(1, vo.getDataQuality().getUnknownOodStatuses().size());
        assertEquals("WEIRD", vo.getDataQuality().getUnknownOodStatuses().get(0).getStatus());
        assertTrue(vo.getDataQuality().getUnknownCalibrationStatuses().isEmpty());
        assertEquals(1, vo.getDataQuality().getUnknownReviewStatuses().size());
        assertEquals("APPROVED", vo.getDataQuality().getUnknownReviewStatuses().get(0).getStatus());

        assertEquals(1, vo.getDataQuality().getReviewedWithoutTime(), "G 是已复核但缺复核时间");
    }

    private static ReasonStatVO findReason(List<ReasonStatVO> rows, String reason) {
        return rows.stream().filter(row -> reason.equals(row.getReason())).findFirst()
                .orElseThrow(() -> new AssertionError("缺少原因行：" + reason));
    }

    private static HistogramBucketVO findBucket(List<HistogramBucketVO> rows, String bucket) {
        return rows.stream().filter(row -> bucket.equals(row.getBucket())).findFirst()
                .orElseThrow(() -> new AssertionError("缺少桶：" + bucket));
    }

    private static StatusStatVO findStatus(List<StatusStatVO> rows, String status) {
        return rows.stream().filter(row -> status.equals(row.getStatus())).findFirst()
                .orElseThrow(() -> new AssertionError("缺少状态行：" + status));
    }

    private static long countScore(AdminAnalyticsVO vo, int score) {
        return vo.getModel().getQualityScores().stream()
                .filter(row -> row.getScore() != null && row.getScore() == score)
                .findFirst().orElseThrow().getCount();
    }
}
