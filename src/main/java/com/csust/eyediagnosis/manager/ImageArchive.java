package com.csust.eyediagnosis.manager;

import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.ciModel.persistence.ImageInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.io.File;

/**
 * 影像对象的唯一写入原语：把「写进 COS」和「写失败时清掉本次可能已写出的对象」绑成一次调用。
 *
 * <p>两种写入模式按调用方要的**产物**区分，不按调用方身份：
 * {@link #storePicture} 要 COS 回传原图信息（宽高 / 格式），判读影像用；
 * {@link #storeBlob} 原样写入，对话附件用。
 *
 * <p>失败语义两者相同：key 由调用方在调用前现生成、没有任何记录引用它，
 * 所以写入过程中任何失败（超时、鉴权、回包解析失败）都删除这个 key 再抛出去。
 * 删除本身幂等（COS 对不存在的 key 删除是成功），失败只记日志——主因是写入失败，
 * 清理失败不该把它盖掉。
 *
 * <p>本类不决定「影像要不要留」：上传成功之后，落库失败 / 0 行 / 提交结果未知
 * 各自怎么补偿仍是调用方的业务判断（见
 * {@code docs/backend-image-storage-failure-semantics.md}）。
 */
@Service
@Slf4j
public class ImageArchive {

    @Resource
    private CosManager cosManager;

    /** 一次成功写入后回给调用方的原图信息。 */
    public record StoredPicture(int width, int height, String format) { }

    /**
     * 写入图片对象并要求 COS 返回原图信息。写失败或回包不可解析都会先删除本对象再抛异常。
     */
    public StoredPicture storePicture(String objectKey, File file) {
        try {
            PutObjectResult result = cosManager.putPictureObject(objectKey, file);
            ImageInfo info = result.getCiUploadResult().getOriginalInfo().getImageInfo();
            return new StoredPicture(info.getWidth(), info.getHeight(), info.getFormat());
        } catch (RuntimeException e) {
            // 对象可能已经写进存储，只是回包解析失败（CI 字段缺失、宽高为空等）：
            // 那种情况下 key 从未返回给调用方，落库也不会发生，不删就是一张永久孤儿原图
            // ——public/ 只有只读审计（列出无引用对象并告警，不自动删除），清理仍需人工。
            log.error("图片写入对象存储失败，清理可能已写入的对象: key={}", objectKey, e);
            discardQuietly(objectKey);
            throw e;
        }
    }

    /**
     * 原样写入对象（不做图片处理）。写失败会先删除本对象再抛异常。
     */
    public void storeBlob(String objectKey, File file) {
        try {
            cosManager.putObject(objectKey, file);
        } catch (RuntimeException e) {
            log.error("附件写入对象存储失败，清理可能已写入的对象: key={}", objectKey, e);
            discardQuietly(objectKey);
            throw e;
        }
    }

    /**
     * 补偿删除：对象已写入存储、但后续落库失败时删除这一个对象。
     *
     * <p>调用方只能传本次刚上传返回的 key，不得用请求参数拼 key。
     * 删除失败不抛异常——落库失败才是主因，补偿失败记日志。
     *
     * <p>如实说明：{@code public/{userId}/} 前缀目前**没有**自动对账会扫；
     * 仓库里唯一的对账器（{@code UploadCompensationService}）只扫 {@code agent-chat/}。
     */
    public void discardQuietly(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            cosManager.deleteObject(objectKey);
        } catch (Exception e) {
            log.error("补偿删除对象失败，可能留下孤儿对象: key={}", objectKey, e);
        }
    }
}
