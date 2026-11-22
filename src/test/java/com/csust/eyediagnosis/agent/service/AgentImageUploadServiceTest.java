package com.csust.eyediagnosis.agent.service;

import com.csust.eyediagnosis.config.CosClientConfig;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.manager.CosManager;
import com.csust.eyediagnosis.manager.ImageArchive;
import com.csust.eyediagnosis.mapper.UploadTaskMapper;
import com.csust.eyediagnosis.model.entity.UploadTask;
import com.csust.eyediagnosis.upload.state.UploadCompensationService;
import com.csust.eyediagnosis.upload.state.UploadStateMachine;
import com.csust.eyediagnosis.upload.state.UploadStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentImageUploadServiceTest {

    @Mock
    private CosManager cosManager;
    @Mock
    private CosClientConfig cosClientConfig;
    @Mock
    private UploadCompensationService uploadCompensationService;
    @Mock
    private ImageArchive imageArchive;
    @Mock
    private UploadStateMachine uploadStateMachine;
    @Mock
    private UploadTaskMapper uploadTaskMapper;
    @InjectMocks
    private AgentImageUploadService service;

    @Test
    void rejectsExecutableRenamedAsPng() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.png", "image/png", "not-an-image".getBytes());
        assertThrows(BusinessException.class, () -> service.uploadImage(file, 7L));
        verifyNoInteractions(cosManager);
    }

    @Test
    void deletionRejectsForeignObjectKey() {
        assertThrows(BusinessException.class,
                () -> service.deleteImage("https://cos-host/agent-chat/8/a.png", 7L));
        verifyNoInteractions(cosManager);
    }

    @Test
    void deleteCallsCosManagerWithOwnedObjectKey() {
        when(cosClientConfig.getHost()).thenReturn("https://cos-host");
        when(cosManager.objectExists(anyString())).thenReturn(true);

        service.deleteImage("https://cos-host/agent-chat/7/uuid.png", 7L);

        verify(cosManager).objectExists("agent-chat/7/uuid.png");
        verify(cosManager).deleteObject("agent-chat/7/uuid.png");
    }

    @Test
    void deleteRegistersDeletionIntentBeforeTouchingCos() {
        when(cosClientConfig.getHost()).thenReturn("https://cos-host");
        when(cosManager.objectExists(anyString())).thenReturn(true);

        service.deleteImage("https://cos-host/agent-chat/7/uuid.png", 7L);

        verify(uploadCompensationService).markDeleted("agent-chat/7/uuid.png", 7L);
    }

    @Test
    void deleteSkipsCallWhenObjectMissing() {
        when(cosClientConfig.getHost()).thenReturn("https://cos-host");
        when(cosManager.objectExists(anyString())).thenReturn(false);

        service.deleteImage("https://cos-host/agent-chat/7/uuid.png", 7L);

        verify(cosManager).objectExists("agent-chat/7/uuid.png");
        verify(cosManager, never()).deleteObject(anyString());
    }

    /**
     * 写入走 {@link ImageArchive} 这个原语：agent 侧不再直接调 CosManager.putObject，
     * 写失败时由原语删掉本次 key（补偿对账只是 30 分钟后的兜底）。
     */
    @Test
    void uploadWritesThroughTheArchiveSeam() throws Exception {
        String url = service.uploadImage(png("eye.png"), 7L);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(imageArchive).storeBlob(key.capture(), any(File.class));
        assertTrue(key.getValue().startsWith("agent-chat/7/"), "key 必须按用户隔离：" + key.getValue());
        assertTrue(key.getValue().endsWith(".png"));
        assertTrue(url.startsWith(AgentImageUploadService.VIEW_PREFIX), "返回的是服务端签发的查看地址");
        verify(uploadTaskMapper).insertPending(any(UploadTask.class));
    }

    @Test
    void uploadFailureIsReportedAndMarksTheTaskFailed() throws Exception {
        doThrow(new RuntimeException("cos down")).when(imageArchive).storeBlob(anyString(), any(File.class));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.uploadImage(png("eye.png"), 7L));

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), ex.getCode());
        assertEquals("图片上传失败", ex.getMessage());
        verify(uploadStateMachine).transition(anyString(), eq(UploadStatus.UPLOADING), eq(UploadStatus.FAILED));
        verify(uploadTaskMapper, never()).insertPending(any(UploadTask.class));
    }

    private static MockMultipartFile png(String name) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", out);
        return new MockMultipartFile("file", name, "image/png", out.toByteArray());
    }
}
