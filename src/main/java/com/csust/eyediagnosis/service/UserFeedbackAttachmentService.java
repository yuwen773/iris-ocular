package com.csust.eyediagnosis.service;

import com.csust.eyediagnosis.config.UserFeedbackSettings;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.exception.ThrowUtils;
import com.csust.eyediagnosis.manager.CosManager;
import com.csust.eyediagnosis.manager.ImageArchive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 用户反馈的截图附件：写入私有对象存储，读取时换一条限时链接。
 *
 * <p>与 Agent 对话图片的区别：前缀是 {@code feedback/{userId}/}，**不共用**
 * {@code agent-chat/}。{@code UploadCompensationService} 的孤儿对账只扫自己那个前缀，
 * 把反馈截图塞进去会让它对着一批不属于自己的对象做删除决策。
 *
 * <p>不产生公开 URL：对象 key 落库，浏览器要看得走
 * {@code GET /feedback/{id}/attachment} 现签一条 5 分钟有效的链接。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserFeedbackAttachmentService {

    private static final long MAX_SIZE = 2 * 1024 * 1024;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png");
    private static final String KEY_PREFIX = "feedback/";
    /** 限时链接有效期：够用户看一眼截图，不足以被转发当长期资源 */
    private static final long PRESIGN_MILLIS = 5 * 60 * 1000L;

    private final ImageArchive imageArchive;
    private final CosManager cosManager;
    private final UserFeedbackSettings settings;

    /** 单个用户的截图前缀；提交反馈时用它校验 object key 归属。 */
    public static String keyPrefix(Long userId) {
        return KEY_PREFIX + userId + "/";
    }

    /**
     * 从 object key 取格式：只认 {@code .png} / {@code .jpg}，其余返回 null。
     * 文件名里的其它点号不影响判断——只看最后一段后缀。
     */
    public static String formatOf(String objectKey) {
        if (objectKey == null) {
            return null;
        }
        int dot = objectKey.lastIndexOf('.');
        if (dot < 0 || dot == objectKey.length() - 1) {
            return null;
        }
        String suffix = objectKey.substring(dot + 1).toLowerCase(Locale.ROOT);
        return "png".equals(suffix) || "jpg".equals(suffix) ? suffix : null;
    }

    /**
     * 校验并写入一张截图，返回 object key。
     *
     * <p>校验三层：大小、Content-Type、能否真正解码成图像。前两层挡掉明显的错误文件，
     * 第三层挡掉「改名成 .png 的随便一个字节流」——否则管理端详情页会显示一个裂图，
     * 而没人知道它本来就不是图片。
     */
    public String upload(MultipartFile file, Long userId) {
        // 开关也管上传：否则功能「关闭」时仍会往 COS 写对象，等于只关了一半。
        ThrowUtils.throwIf(!settings.isEnabled(), ErrorCode.FORBIDDEN_ERROR, "反馈功能暂未启用");
        ThrowUtils.throwIf(userId == null, ErrorCode.NO_AUTH_ERROR, "用户未登录");
        ThrowUtils.throwIf(file == null || file.isEmpty(), ErrorCode.PARAMS_ERROR, "截图不能为空");
        ThrowUtils.throwIf(file.getSize() > MAX_SIZE, ErrorCode.PARAMS_ERROR, "截图不能超过 2MB");
        ThrowUtils.throwIf(!ALLOWED_CONTENT_TYPES.contains(file.getContentType()),
                ErrorCode.PARAMS_ERROR, "仅支持 image/jpeg、image/png");
        try (var in = file.getInputStream()) {
            var image = ImageIO.read(in);
            ThrowUtils.throwIf(image == null || image.getWidth() <= 0 || image.getHeight() <= 0,
                    ErrorCode.PARAMS_ERROR, "无法解码为图片");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "无法解码为图片");
        }

        String format = "image/png".equalsIgnoreCase(file.getContentType()) ? "png" : "jpg";
        String objectKey = keyPrefix(userId) + UUID.randomUUID() + "." + format;
        File tempFile = null;
        try {
            tempFile = File.createTempFile("feedback-", "." + format);
            file.transferTo(tempFile);
            // 写失败由 ImageArchive 删掉本次 key，不留孤儿对象
            imageArchive.storeBlob(objectKey, tempFile);
            return objectKey;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("反馈截图上传失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "截图上传失败");
        } finally {
            if (tempFile != null && !tempFile.delete()) {
                log.warn("反馈截图临时文件删除失败: {}", tempFile.getAbsolutePath());
            }
        }
    }

    /** 现签一条限时预览链接（对象本身仍是私有的）。 */
    public String presignedUrl(String objectKey) {
        return cosManager.generatePresignedUrl(objectKey,
                new Date(System.currentTimeMillis() + PRESIGN_MILLIS));
    }

    /**
     * 对象是否真的存在。
     *
     * <p>提交反馈时用它确认「这个 key 确实上传过」：只比前缀会接受任意自造 key，
     * 管理端详情页就只剩一张裂图。
     */
    public boolean exists(String objectKey) {
        return cosManager.objectExists(objectKey);
    }

    /** 补偿删除：落库失败时清掉本次刚上传的对象。只接受刚上传返回的 key。 */
    public void discardQuietly(String objectKey) {
        imageArchive.discardQuietly(objectKey);
    }
}
