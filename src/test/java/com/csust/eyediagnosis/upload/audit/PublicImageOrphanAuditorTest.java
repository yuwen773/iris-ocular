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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code public/} 前缀的孤儿审计只报告不删除，判定必须宁可少报、不可多报。
 *
 * <p>这里刻意用**真实写入形态**的 key（{@code /public/…}，带前导斜杠）：上传原语
 * 拼的是 {@code "/" + prefix + "/" + fileName}。用 {@code public/…} 当输入测出来的
 * 「通过」，在真实 COS 上等价于一个对象都没扫到。
 */
class PublicImageOrphanAuditorTest {

    private static Date old() {
        return Date.from(Instant.now().minus(2, ChronoUnit.DAYS));
    }

    /** {@code /public/} 是真实形态；{@code public/} 留空，验证两种前缀都会被列。 */
    private static CosManager cosWith(List<CosManager.CosObjectInfo> objects) {
        CosManager cos = mock(CosManager.class);
        when(cos.listObjects("/" + PublicImageOrphanAuditor.PUBLIC_PREFIX)).thenReturn(objects);
        when(cos.listObjects(PublicImageOrphanAuditor.PUBLIC_PREFIX)).thenReturn(List.of());
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
        // COS 上的 key 带前导斜杠，库里存的是完整 URL（host 后又一个斜杠）——归一化后应当对上
        CosManager cos = cosWith(List.of(
                new CosManager.CosObjectInfo("/public/7/eye.png", old())));
        JdbcTemplate jdbc = jdbcReferencing(
                "https://bucket.cos.ap-shanghai.myqcloud.com//public/7/eye.png?sign=abc");

        PublicImageOrphanAuditor.AuditReport report = auditor(cos, jdbc).audit();

        assertEquals(1, report.scanned());
        assertEquals(0, report.unreferenced());
        assertTrue(report.samples().isEmpty());
    }

    @Test
    void bothKeyShapesAreListed() {
        // 只列一种前缀 = 一个对象都看不见；审计会静默「通过」
        CosManager cos = mock(CosManager.class);
        when(cos.listObjects("/public/")).thenReturn(List.of());
        when(cos.listObjects("public/")).thenReturn(List.of());

        auditor(cos, jdbcReferencing()).audit();

        verify(cos).listObjects("/public/");
        verify(cos).listObjects("public/");
    }

    @Test
    void unreferencedObjectsAreReportedNotDeleted() {
        CosManager cos = cosWith(List.of(
                new CosManager.CosObjectInfo("/public/7/eye.png", old()),
                new CosManager.CosObjectInfo("/public/7/orphan.png", old())));
        JdbcTemplate jdbc = jdbcReferencing("public/7/eye.png");

        PublicImageOrphanAuditor.AuditReport report = auditor(cos, jdbc).audit();

        assertEquals(2, report.scanned());
        assertEquals(1, report.unreferenced());
        // 报原始 key：人工清理要照着它删
        assertEquals(List.of("/public/7/orphan.png"), report.samples());
    }

    @Test
    void objectsInsideTheGraceWindowAreSkipped() {
        // 刚写进 COS、数据库行还没落下来的对象不能被算成孤儿
        CosManager cos = cosWith(List.of(
                new CosManager.CosObjectInfo("/public/7/just-uploaded.png", new Date())));
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
        assertEquals("public/7/eye.png",
                PublicImageOrphanAuditor.toObjectKey(
                        "https://bucket.cos.ap-shanghai.myqcloud.com//public/7/eye.png"));
        assertEquals("public/7/眼底.png",
                PublicImageOrphanAuditor.toObjectKey("public/7/%E7%9C%BC%E5%BA%95.png"));
        assertNull(PublicImageOrphanAuditor.toObjectKey("   "));
    }
}
