package com.csust.eyediagnosis.service;

import com.csust.eyediagnosis.config.UserFeedbackSettings;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.manager.CosManager;
import com.csust.eyediagnosis.manager.ImageArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 截图附件的三道关：开关、能不能解码成图、写到哪里。
 *
 * <p>开关也有用例，因为上传端点绕开了 {@code UserFeedbackLifecycleService}：
 * 只在那一边检查，「关闭」就只关了一半——对象照样写进 COS。
 */
class UserFeedbackAttachmentServiceTest {

    private ImageArchive imageArchive;
    private CosManager cosManager;
    private UserFeedbackSettings settings;
    private UserFeedbackAttachmentService service;

    @BeforeEach
    void setUp() {
        imageArchive = mock(ImageArchive.class);
        cosManager = mock(CosManager.class);
        settings = new UserFeedbackSettings();
        service = new UserFeedbackAttachmentService(imageArchive, cosManager, settings);
    }

    @Test
    void uploadIsBlockedWhenFeatureDisabled() {
        settings.setEnabled(false);
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.upload(png(2, 2), 7L));
        assertEquals(ErrorCode.FORBIDDEN_ERROR.getCode(), error.getCode());
        verifyNoInteractions(imageArchive);
    }

    @Test
    void uploadRejectsBytesThatAreNotAnImage() {
        MockMultipartFile fake = new MockMultipartFile("file", "shot.png", "image/png",
                "not really a png".getBytes());
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.upload(fake, 7L));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), error.getCode());
        verifyNoInteractions(imageArchive);
    }

    @Test
    void uploadStoresUnderOwnPrefixAndReturnsObjectKey() {
        String key = service.upload(png(2, 2), 7L);
        assertTrue(key.startsWith("feedback/7/"), "截图必须落在本人前缀下：" + key);
        assertTrue(key.endsWith(".png"), "格式后缀应由服务端决定：" + key);
        verify(imageArchive).storeBlob(eq(key), any());
    }

    @Test
    void existsDelegatesToStorage() {
        when(cosManager.objectExists(anyString())).thenReturn(true, false);
        assertTrue(service.exists("feedback/7/abc.png"));
        assertFalse(service.exists("feedback/7/missing.png"));
    }

    /** 真实可解码的 PNG：第三道关要读像素，随便的字节流会被拒。 */
    private static MockMultipartFile png(int width, int height) {
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return new MockMultipartFile("file", "shot.png", "image/png", out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
