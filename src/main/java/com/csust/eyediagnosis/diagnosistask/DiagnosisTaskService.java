package com.csust.eyediagnosis.diagnosistask;

import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class DiagnosisTaskService {
    static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final DiagnosisTaskStorage storage;

    public DiagnosisTaskService(JdbcTemplate jdbc, DiagnosisTaskStorage storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    public SafeStatus create(long userId, String key, MultipartFile file) {
        if (key == null || !key.matches("[A-Za-z0-9._~-]{1,128}") || file == null || file.isEmpty()
                || file.getSize() > MAX_IMAGE_BYTES) throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片或幂等键无效");
        byte[] bytes;
        String format;
        try {
            bytes = file.getBytes();
            try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw new IllegalArgumentException();
                var reader = readers.next();
                try {
                    reader.addIIOReadWarningListener((source, warning) -> {
                        throw new IllegalArgumentException("Image decoding warning");
                    });
                    reader.setInput(stream, true, true);
                    format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                    if (!(format.equals("png") || format.equals("jpeg")) || reader.getWidth(0) <= 0
                            || reader.getHeight(0) <= 0 || (long) reader.getWidth(0) * reader.getHeight(0) > 40_000_000L
                            || reader.read(0) == null) throw new IllegalArgumentException();
                } finally { reader.dispose(); }
            }
        } catch (Exception e) { throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片无效"); }
        String imageDigest = digest(bytes);
        String requestDigest = digest(("diagnosis-task-v1\n" + format + "\n" + imageDigest)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var existing = findRequest(userId, key, requestDigest);
        if (existing != null) return existing;
        String id = UUID.randomUUID().toString();
        String objectKey = "diagnosis-task/" + userId + "/" + id;
        String version = storage.upload(objectKey, bytes, "image/" + format);
        boolean validVersion = version != null && !version.isBlank() && !"null".equalsIgnoreCase(version.trim());
        if (!validVersion) version = null;
        String status = validVersion ? "PENDING" : "RESTRICTED";
        // 受限原因必须和状态一起落库：只放在返回对象里，用户刷新状态或幂等重放
        // 之后就只剩一句「受限」——那正是 V33 加这一列要消掉的现象。
        String restrictReason = validVersion ? null : "STORAGE_VERSION_MISSING";
        try {
            jdbc.update("INSERT INTO t_diagnosis_task (taskId,userId,idempotencyKey,requestDigest,imageDigest,objectKey,objectVersion,imageFormat,status,restrictReason,createdAt,updatedAt) VALUES (?,?,?,?,?,?,?,?,?,?,NOW(6),NOW(6))",
                    id, userId, key, requestDigest, imageDigest, objectKey, version, format, status, restrictReason);
        } catch (DuplicateKeyException e) {
            var concurrent = findRequest(userId, key, requestDigest);
            if (concurrent == null) throw e;
            return concurrent;
        }
        return new SafeStatus(id, safeStatus(status), restrictReason);
    }

    private SafeStatus findRequest(long userId, String key, String requestDigest) {
        var rows = jdbc.query("SELECT taskId,requestDigest,status,restrictReason FROM t_diagnosis_task WHERE userId=? AND idempotencyKey=?",
                (rs, n) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)}, userId, key);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        if (!requestDigest.equals(row[1])) throw new RequestConflict();
        return new SafeStatus(row[0], safeStatus(row[2]), row[3]);
    }

    static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static class RequestConflict extends RuntimeException { }

    public SafeStatus getOwned(String id, long userId) {
        var rows = jdbc.query("SELECT status,restrictReason FROM t_diagnosis_task WHERE taskId = ? AND userId = ?",
                (rs, row) -> new SafeStatus(id, safeStatus(rs.getString("status")), rs.getString("restrictReason")), id, userId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        return rows.getFirst();
    }

    public java.util.List<SafeStatus> listOwned(long userId) {
        return jdbc.query("SELECT taskId,status,restrictReason FROM t_diagnosis_task WHERE userId=? ORDER BY createdAt DESC, taskId DESC LIMIT 50",
                (rs, row) -> new SafeStatus(rs.getString(1), safeStatus(rs.getString(2)), rs.getString(3)), userId);
    }

    public record TaskImage(byte[] bytes, String format) { }

    /** 任务影像不可读（未归档或对象存储失败），与"任务不存在"区分。 */
    public static class ImageUnavailable extends RuntimeException {
        public ImageUnavailable(String message) { super(message); }

        public ImageUnavailable(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * 读取任务影像。调用方必须先完成授权（例如医生复核的 {@code detail()}），
     * 本方法只按 taskId 做存储读取与绑定校验，不再重复用户归属判断。
     */
    public TaskImage readImage(String taskId) {
        var rows = jdbc.query(
                "SELECT objectKey,objectVersion,imageDigest,imageFormat FROM t_diagnosis_task WHERE taskId=?",
                (rs, n) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)},
                taskId);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        String[] row = rows.getFirst();
        if (row[1] == null || row[1].isBlank()) throw new ImageUnavailable("IMAGE_NOT_ARCHIVED");
        try {
            byte[] bytes = storage.readVersion(row[0], row[1]);
            boolean validFormat = "png".equals(row[3]) || "jpeg".equals(row[3]);
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES || !validFormat
                    || !digest(bytes).equals(row[2])) {
                throw new IllegalStateException("Image binding mismatch");
            }
            return new TaskImage(bytes, row[3]);
        } catch (Exception e) {
            // 对外只回一个「影像不可用」：存储异常、格式/大小/摘要不符，客户端能做的
            // 事都一样。但原因必须挂在异常链上——丢掉它，线上就只剩一句
            // IMAGE_UNAVAILABLE，既分不清是对象没了还是摘要对不上，也没有堆栈可查。
            throw new ImageUnavailable("IMAGE_UNAVAILABLE", e);
        }
    }

    private String safeStatus(String status) {
        return switch (status) {
            case "PENDING", "RUNNING", "DRAFTING" -> "PROCESSING";
            case "WAITING_REVIEW" -> "WAITING_REVIEW";
            default -> "RESTRICTED";
        };
    }

    /**
     * 面向客户端的任务状态。
     *
     * @param restrictReason 仅 {@code RESTRICTED} 时有值，是准入拒绝的原因码
     *                       （{@code OOD_NOT_EVALUATED}、{@code ABSTAIN_QUALITY_FAIL} 等）。
     *                       前端据此说明「为什么受限」，不再只有一句「受限」。
     */
    public record SafeStatus(String taskId, String status, String restrictReason) { }
}
