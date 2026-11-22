package com.csust.eyediagnosis.manager;

import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.ciModel.persistence.CIUploadResult;
import com.qcloud.cos.model.ciModel.persistence.ImageInfo;
import com.qcloud.cos.model.ciModel.persistence.OriginalInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 影像写入原语的失败语义：写失败 / 回包不可解析 → 删掉本次这个 key 再抛；
 * 成功 → 一个对象都不删；补偿删除本身失败只记日志。
 *
 * <p>全部使用 Mock 的 COS 客户端，不访问真实对象存储。
 */
@ExtendWith(MockitoExtension.class)
class ImageArchiveTest {

    @Mock
    private CosManager cosManager;
    @InjectMocks
    private ImageArchive imageArchive;

    private final File file = new File("unused-in-mock.png");

    @Test
    void storePictureReturnsTheOriginalImageInfo() {
        when(cosManager.putPictureObject(anyString(), any(File.class))).thenReturn(pictureResult());

        ImageArchive.StoredPicture stored = imageArchive.storePicture("/public/7/a.png", file);

        assertEquals(640, stored.width());
        assertEquals(480, stored.height());
        assertEquals("png", stored.format());
        verify(cosManager, never()).deleteObject(anyString());
    }

    @Test
    void storePictureCleansTheKeyWhenTheWriteFails() {
        when(cosManager.putPictureObject(anyString(), any(File.class)))
                .thenThrow(new RuntimeException("SocketTimeoutException: read timed out"));

        assertThrows(RuntimeException.class, () -> imageArchive.storePicture("/public/7/a.png", file));

        // 超时也可能已经在存储侧写了一半：删一次是幂等的，留下才是不可逆的。
        verify(cosManager).deleteObject("/public/7/a.png");
    }

    /**
     * 对象已经写进存储，回包却解析不出宽高（CI 字段缺失）。此时 key 从未返回给调用方，
     * 落库不会发生——不删就是一张永久孤儿原图：public/ 前缀没有对账器。
     */
    @Test
    void storePictureCleansTheKeyWhenTheReplyCannotBeParsed() {
        when(cosManager.putPictureObject(anyString(), any(File.class))).thenReturn(new PutObjectResult());

        assertThrows(RuntimeException.class, () -> imageArchive.storePicture("/public/7/a.png", file));

        verify(cosManager).deleteObject("/public/7/a.png");
    }

    @Test
    void storeBlobWritesWithoutDeleting() {
        imageArchive.storeBlob("agent-chat/7/a.png", file);

        verify(cosManager).putObject("agent-chat/7/a.png", file);
        verify(cosManager, never()).deleteObject(anyString());
    }

    @Test
    void storeBlobCleansTheKeyWhenTheWriteFails() {
        doThrow(new RuntimeException("cos down")).when(cosManager).putObject(anyString(), any(File.class));

        assertThrows(RuntimeException.class, () -> imageArchive.storeBlob("agent-chat/7/a.png", file));

        verify(cosManager).deleteObject("agent-chat/7/a.png");
    }

    @Test
    void compensationDeleteSwallowsStorageErrors() {
        doThrow(new RuntimeException("cos down")).when(cosManager).deleteObject("public/7/x.png");

        assertDoesNotThrow(() -> imageArchive.discardQuietly("public/7/x.png"));
    }

    @Test
    void compensationDeleteIgnoresBlankKey() {
        assertDoesNotThrow(() -> imageArchive.discardQuietly(null));
        assertDoesNotThrow(() -> imageArchive.discardQuietly("  "));
        verify(cosManager, never()).deleteObject(anyString());
    }

    private static PutObjectResult pictureResult() {
        ImageInfo info = new ImageInfo();
        info.setWidth(640);
        info.setHeight(480);
        info.setFormat("png");
        OriginalInfo original = new OriginalInfo();
        original.setImageInfo(info);
        CIUploadResult ci = new CIUploadResult();
        ci.setOriginalInfo(original);
        PutObjectResult result = new PutObjectResult();
        result.setCiUploadResult(ci);
        return result;
    }
}
