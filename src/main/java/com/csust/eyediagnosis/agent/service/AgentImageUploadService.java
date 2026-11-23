package com.csust.eyediagnosis.agent.service;

import com.csust.eyediagnosis.config.CosClientConfig;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.exception.ThrowUtils;
import com.csust.eyediagnosis.manager.CosManager;
import com.csust.eyediagnosis.manager.ImageArchive;
import com.csust.eyediagnosis.mapper.UploadTaskMapper;
import com.csust.eyediagnosis.model.entity.UploadTask;
import com.csust.eyediagnosis.upload.state.UploadCompensationService;
import com.csust.eyediagnosis.upload.state.UploadStateMachine;
import com.csust.eyediagnosis.upload.state.UploadStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.Resource;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class AgentImageUploadService {

    @Resource
    private CosManager cosManager;

    @Resource
    private ImageArchive imageArchive;

    @Resource
    private CosClientConfig cosClientConfig;

    @Resource
    private UploadStateMachine uploadStateMachine;

    @Resource
    private UploadTaskMapper uploadTaskMapper;

    @Resource
    private UploadCompensationService uploadCompensationService;

    @Resource
    private ImageReferenceSigner imageReferenceSigner;

    private static final long MAX_SIZE = 2 * 1024 * 1024;

    /** 取图重定向签名链接的有效期（分钟）。 */
    private static final int VIEW_PRESIGN_MINUTES = 5;

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png"
    );

    private static final String OBJECT_KEY_PREFIX = UploadCompensationService.OBJECT_KEY_PREFIX;
    public static final String VIEW_PREFIX = "/api/agent/image/view/";

    public String uploadImage(MultipartFile file, Long userId) {
        ThrowUtils.throwIf(userId == null, ErrorCode.NO_AUTH_ERROR, "用户未登录");
        ThrowUtils.throwIf(file == null || file.isEmpty(), ErrorCode.PARAMS_ERROR, "文件不能为空");
        ThrowUtils.throwIf(file.getSize() > MAX_SIZE, ErrorCode.PARAMS_ERROR, "文件大小不能超过 2MB");
        ThrowUtils.throwIf(!ALLOWED_CONTENT_TYPES.contains(file.getContentType()),
                ErrorCode.PARAMS_ERROR, "仅支持 image/jpeg、image/png");
        try (var in = file.getInputStream()) {
            BufferedImage image = ImageIO.read(in);
            ThrowUtils.throwIf(image == null || image.getWidth() <= 0 || image.getHeight() <= 0,
                    ErrorCode.PARAMS_ERROR, "无法解码为图像");
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "无法解码为图像");
        }

        String taskId = UUID.randomUUID().toString();
        String extension = getExtension(file.getContentType());
        String objectKey = OBJECT_KEY_PREFIX + userId + "/" + UUID.randomUUID() + extension;

        File tempFile = null;
        boolean blobStored = false;
        try {
            uploadStateMachine.transition(taskId, null, UploadStatus.UPLOADING);
            tempFile = File.createTempFile("upload-", extension);
            file.transferTo(tempFile);
            // 写失败由 ImageArchive 删掉本次 key，不留孤儿对象；补偿对账只是兜底。
            imageArchive.storeBlob(objectKey, tempFile);
            blobStored = true;
            uploadStateMachine.transition(taskId, UploadStatus.UPLOADING, UploadStatus.PERSISTED);

            UploadTask task = new UploadTask();
            task.setTaskId(taskId);
            task.setUserId(userId);
            task.setObjectKey(objectKey);
            task.setStatus(UploadStatus.PERSISTED.getCode());
            task.setRetryCount(0);
            uploadTaskMapper.insertPending(task);

            return VIEW_PREFIX + objectKey.substring(objectKey.lastIndexOf('/') + 1);
        } catch (Exception e) {
            log.error("图片上传失败: {}", e.getMessage(), e);
            if (blobStored) {
                // 对象已写入、任务行没写成：立刻删掉，不等补偿任务（默认每小时一次）。
                discardIfUnreferenced(objectKey, userId);
            }
            try {
                uploadStateMachine.transition(taskId, UploadStatus.UPLOADING, UploadStatus.FAILED);
            } catch (Exception ignored) {
                // state machine transition best-effort
            }
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片上传失败");
        } finally {
            if (tempFile != null && !tempFile.delete()) {
                log.warn("临时文件删除失败: {}", tempFile.getAbsolutePath());
            }
        }
    }

    /**
     * 落库失败后清理本次刚写入的对象。
     *
     * <p>先确认没有任何有效任务行引用这个 key 再删：连接在提交之后才断开这类
     * 「结果未知」的失败，任务行其实已经存在，此时删对象等于把一次成功的上传删成空引用。
     * 对象与任务行的一致性靠这里兜底，跨进程崩溃留下的残留交给补偿对账。
     */
    private void discardIfUnreferenced(String objectKey, Long userId) {
        try {
            UploadTask task = uploadTaskMapper.selectByObjectKey(userId, objectKey);
            if (task != null && UploadStatus.activeCodes().contains(task.getStatus())) {
                log.warn("上传失败但任务行已存在，保留对象交给补偿对账: key={}", objectKey);
                return;
            }
        } catch (Exception e) {
            log.warn("无法确认对象是否仍被引用，交给补偿对账: key={}", objectKey, e);
            return;
        }
        imageArchive.discardQuietly(objectKey);
    }

    /** Convert only an exact server-issued upload URL; never fetch a user URL. */
    public String resolveOwnedImageId(String imageUrl, Long userId) {
        ThrowUtils.throwIf(imageUrl == null || userId == null, ErrorCode.NO_AUTH_ERROR);
        String key = imageUrl.startsWith(VIEW_PREFIX)
                ? OBJECT_KEY_PREFIX + userId + "/" + imageUrl.substring(VIEW_PREFIX.length())
                : extractOwnedObjectKey(imageUrl);
        ThrowUtils.throwIf((!imageUrl.equals(cosClientConfig.getHost() + "/" + key)
                        && !imageUrl.equals(VIEW_PREFIX + key.substring(key.lastIndexOf('/') + 1)))
                        || !key.matches("agent-chat/" + userId + "/[A-Za-z0-9_-]+\\.(png|jpg)"),
                ErrorCode.NO_AUTH_ERROR, "图片引用未获授权");
        var task = uploadTaskMapper.selectByObjectKey(userId, key);
        ThrowUtils.throwIf(task == null || !userId.equals(task.getUserId())
                        || !key.equals(task.getObjectKey()) || !UploadStatus.activeCodes().contains(task.getStatus()),
                ErrorCode.PARAMS_ERROR, "图片不存在或已删除");
        return key;
    }

    public String authorizedViewUrl(String imageUrl, Long userId) {
        String key = resolveOwnedImageId(imageUrl, userId);
        return VIEW_PREFIX + key.substring(key.lastIndexOf('/') + 1);
    }

    /**
     * 取图 302 的目标地址。
     *
     * <p>必须是短时签名 URL，不能是对象存储的直链：桶私有的话直链直接 403；
     * 桶一旦被配成公有读，这条直链就成了绕过归属校验、长期有效的公开地址
     * （签名过期后自动失效，且只对本次这一张图有效）。
     */
    public String resolveViewTarget(String fileName, Long userId) {
        String key = resolveOwnedImageId(VIEW_PREFIX + fileName, userId);
        return cosManager.generatePresignedUrl(key,
                Date.from(Instant.now().plus(VIEW_PRESIGN_MINUTES, ChronoUnit.MINUTES)));
    }

    /**
     * 签发一个短期图片引用，供算法侧 MCP 工具取图。
     *
     * <p>算法侧只认这种引用：签名在后端、有效期短、目标地址由这里决定，
     * 模型无法把工具指向任意主机。归属校验照旧先做一遍（只有本人且未删除的
     * 图片才签发），引用里装的是短时预签名 URL。
     */
    public String issueImageReference(String fileName, Long userId) {
        ThrowUtils.throwIf(fileName == null || fileName.isBlank(), ErrorCode.PARAMS_ERROR, "缺少图片标识");
        return imageReferenceSigner.sign(resolveViewTarget(fileName, userId));
    }

    public void deleteImage(String imageUrl, Long userId) {
        ThrowUtils.throwIf(imageUrl == null || userId == null, ErrorCode.NO_AUTH_ERROR);
        String objectKey = imageUrl.startsWith(VIEW_PREFIX)
                ? resolveOwnedImageId(imageUrl, userId) : extractOwnedObjectKey(imageUrl);
        String ownerPrefix = OBJECT_KEY_PREFIX + userId + "/";
        ThrowUtils.throwIf(!objectKey.startsWith(ownerPrefix), ErrorCode.NO_AUTH_ERROR,
                "无权删除他人图片");
        // 先登记删除意图：即使下面的 COS 删除失败，补偿任务也会重试清理该对象
        uploadCompensationService.markDeleted(objectKey, userId);
        if (!cosManager.objectExists(objectKey)) {
            return;
        }
        cosManager.deleteObject(objectKey);
    }

    private String extractOwnedObjectKey(String imageUrl) {
        URI target;
        try {
            target = URI.create(imageUrl);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片 URL 非法");
        }
        ThrowUtils.throwIf(!"https".equalsIgnoreCase(target.getScheme())
                        || target.getHost() == null,
                ErrorCode.PARAMS_ERROR, "图片 URL 必须是 HTTPS");
        ThrowUtils.throwIf(cosClientConfig.getHost() == null
                        || !cosClientConfig.getHost().equalsIgnoreCase(
                                target.getScheme() + "://" + target.getHost()),
                ErrorCode.PARAMS_ERROR, "图片 host 与 COS 配置不一致");
        String path = target.getPath();
        ThrowUtils.throwIf(path == null || path.isBlank() || !path.startsWith("/"),
                ErrorCode.PARAMS_ERROR, "图片路径非法");
        return path.substring(1);
    }

    private String getExtension(String contentType) {
        if ("image/jpeg".equalsIgnoreCase(contentType)) {
            return ".jpg";
        }
        if ("image/png".equalsIgnoreCase(contentType)) {
            return ".png";
        }
        throw new BusinessException(ErrorCode.PARAMS_ERROR, "不支持的 Content-Type");
    }
}

