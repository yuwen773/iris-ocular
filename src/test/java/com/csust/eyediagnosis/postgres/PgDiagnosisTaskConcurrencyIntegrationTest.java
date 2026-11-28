package com.csust.eyediagnosis.postgres;

import com.csust.eyediagnosis.diagnosistask.DiagnosisTaskService;
import com.csust.eyediagnosis.diagnosistask.DiagnosisTaskStorage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 诊断任务的幂等与并发语义（真实 PostgreSQL）。
 *
 * <p>临床路径上「同一个请求被点了两次」必须只产生一个任务：数据库唯一约束是
 * 最后一道闸，而不是应用层的先查后插。MySQL 版本靠 Unique 索引 + DuplicateKey；
 * PostgreSQL 上同一路径依赖 UNIQUE(userId, idempotencyKey) 与 ON CONFLICT 语义，
 * 这里用两个真并发线程把那条路径走一遍。
 */
class PgDiagnosisTaskConcurrencyIntegrationTest {

    private static JdbcTemplate jdbc() {
        Assumptions.assumeTrue(System.getenv("PG_IT_URL") != null, "PG_IT_URL not set");
        DriverManagerDataSource ds = new DriverManagerDataSource(
                System.getenv("PG_IT_URL"),
                envOrDefault("PG_IT_APP_USER", "iris_app"),
                System.getenv("PG_IT_APP_PASSWORD"));
        return new JdbcTemplate(ds);
    }

    @Test
    void simultaneousIdenticalRequestsCreateExactlyOneTask() throws Exception {
        JdbcTemplate jdbc = jdbc();
        long userId = 990001L;
        String key = UUID.randomUUID().toString();
        byte[] image = png();

        DiagnosisTaskStorage storage = mock(DiagnosisTaskStorage.class);
        CountDownLatch bothUploaded = new CountDownLatch(2);
        when(storage.upload(any(), any(), any())).thenAnswer(invocation -> {
            bothUploaded.countDown();
            if (!bothUploaded.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("upload barrier timeout");
            }
            return "v1";
        });
        DiagnosisTaskService service = new DiagnosisTaskService(jdbc, storage);

        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> service.create(userId, key, file(image)).taskId());
            var second = pool.submit(() -> service.create(userId, key, file(image)).taskId());
            String a = first.get(30, TimeUnit.SECONDS);
            String b = second.get(30, TimeUnit.SECONDS);
            assertEquals(a, b, "identical requests must resolve to one task");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM diagnosis.t_diagnosis_task WHERE userId = ? AND idempotencyKey = ?",
                Integer.class, userId, key));
        jdbc.update("DELETE FROM diagnosis.t_diagnosis_task WHERE userId = ?", userId);
    }

    @Test
    void sameKeyWithADifferentImageIsRejected() {
        JdbcTemplate jdbc = jdbc();
        long userId = 990002L;
        String key = UUID.randomUUID().toString();
        DiagnosisTaskStorage storage = mock(DiagnosisTaskStorage.class);
        when(storage.upload(any(), any(), any())).thenReturn("v1");
        DiagnosisTaskService service = new DiagnosisTaskService(jdbc, storage);

        service.create(userId, key, file(png()));
        assertThrows(DiagnosisTaskService.RequestConflict.class,
                () -> service.create(userId, key, file(pngOfDifferentColor())),
                "reusing an idempotency key for different content must not silently return the old task");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM diagnosis.t_diagnosis_task WHERE userId = ?", Integer.class, userId));
        jdbc.update("DELETE FROM diagnosis.t_diagnosis_task WHERE userId = ?", userId);
    }

    @Test
    void idempotencyKeyIsScopedPerUser() {
        JdbcTemplate jdbc = jdbc();
        String key = UUID.randomUUID().toString();
        DiagnosisTaskStorage storage = mock(DiagnosisTaskStorage.class);
        when(storage.upload(any(), any(), any())).thenReturn("v1");
        DiagnosisTaskService service = new DiagnosisTaskService(jdbc, storage);

        String first = service.create(990003L, key, file(png())).taskId();
        String second = service.create(990004L, key, file(png())).taskId();
        assertEquals(false, first.equals(second), "another user reusing the key must get its own task");
        jdbc.update("DELETE FROM diagnosis.t_diagnosis_task WHERE userId IN (990003, 990004)");
    }

    private static MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("file", "eye.png", "image/png", bytes);
    }

    private static byte[] png() {
        return pngOfColor(0x336699);
    }

    private static byte[] pngOfDifferentColor() {
        return pngOfColor(0x996633);
    }

    private static byte[] pngOfColor(int rgb) {
        try {
            BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < 8; x++) {
                for (int y = 0; y < 8; y++) {
                    image.setRGB(x, y, rgb);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String envOrDefault(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
