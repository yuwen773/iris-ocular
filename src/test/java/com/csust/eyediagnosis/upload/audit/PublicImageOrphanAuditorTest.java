package com.csust.eyediagnosis.upload.audit;

import com.csust.eyediagnosis.manager.CosManager;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code public/} 前缀的孤儿审计只报告不删除，判定必须宁可少报、不可多报。
 */
class PublicImageOrphanAuditorTest {

    private static Date old() {
        return Date.from(Instant.now().minus(2, ChronoUnit.DAYS));
    }

    private static CosManager cosWith(List<CosManager.CosObjectInfo> objects) {
        CosManager cos = mock(CosManager.class);
        when(cos.listObjects(PublicImageOrphanAuditor.PUBLIC_PREFIX)).thenReturn(objects);
        return cos;
    }

    private static JdbcTemplate jdbcReferencing(String... values) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of(values));
        return jdbc;
    }

    private static PublicImageOrphanAuditor auditor(CosManager cos, JdbcTemplate jdbc) {
        PublicImageOrphanAuditor auditor = new PublicImageOrphanAuditor(cos, jdbc);
        ReflectionTestUtils.setField(auditor, "minAgeMinutes", 60L);
        return auditor;
    }

    @Test
    void referencedObjectsAreNotReported() {
        // 库里存完整 URL、带签名参数；COS 上只有 key——归一化后应当对上
        CosManager cos = cosWith(List.of(
                new CosManager.CosObjectInfo("public/7/eye.png", old())));
        JdbcTemplate jdbc = jdbcReferencing(
                "https://bucket.cos.ap-shanghai.myqcloud.com/public/7/eye.png?sign=abc");

        PublicImageOrphanAuditor.AuditReport report = auditor(cos, jdbc).audit();

        assertEquals(1, report.scanned());
        assertEquals(0, report.unreferenced());
        assertTrue(report.samples().isEmpty());
    }

    @Test
    void unreferencedObjectsAreReportedNotDeleted() {
        CosManager cos = cosWith(List.of(
                new CosManager.CosObjectInfo("public/7/eye.png", old()),
                new CosManager.CosObjectInfo("public/7/orphan.png", old())));
        JdbcTemplate jdbc = jdbcReferencing("public/7/eye.png");

        PublicImageOrphanAuditor.AuditReport report = auditor(cos, jdbc).audit();

        assertEquals(2, report.scanned());
        assertEquals(1, report.unreferenced());
        assertEquals(List.of("public/7/orphan.png"), report.samples());
    }

    @Test
    void objectsInsideTheGraceWindowAreSkipped() {
        // 刚写进 COS、数据库行还没落下来的对象不能被算成孤儿
        CosManager cos = cosWith(List.of(
                new CosManager.CosObjectInfo("public/7/just-uploaded.png", new Date())));
        JdbcTemplate jdbc = jdbcReferencing();

        PublicImageOrphanAuditor.AuditReport report = auditor(cos, jdbc).audit();

        assertEquals(0, report.scanned());
        assertEquals(0, report.unreferenced());
    }

    @Test
    void keyNormalisationStripsHostQueryAndLeadingSlash() {
        assertEquals("public/7/eye.png",
                PublicImageOrphanAuditor.toObjectKey(
                        "https://bucket.cos.ap-shanghai.myqcloud.com/public/7/eye.png?sign=abc"));
        assertEquals("public/7/eye.png", PublicImageOrphanAuditor.toObjectKey("/public/7/eye.png"));
        assertEquals("public/7/眼底.png",
                PublicImageOrphanAuditor.toObjectKey("public/7/%E7%9C%BC%E5%BA%95.png"));
        assertNull(PublicImageOrphanAuditor.toObjectKey("   "));
    }
}
