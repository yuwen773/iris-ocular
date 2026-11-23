package com.csust.eyediagnosis.agent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具调用前把「服务端取图路径」翻译成「后端签发的短期引用」。
 */
class AgentImageReferenceResolverTest {

    private static AgentImageReferenceResolver resolver(AgentImageUploadService uploadService) {
        return new AgentImageReferenceResolver(uploadService);
    }

    @Test
    void ownedImagePathBecomesASignedReference() {
        AgentImageUploadService uploadService = mock(AgentImageUploadService.class);
        when(uploadService.issueImageReference(eq("a.png"), eq(7L)))
                .thenReturn("iris-img://payload?exp=1&sig=deadbeef");

        String reference = resolver(uploadService)
                .toReference(AgentImageUploadService.VIEW_PREFIX + "a.png", 7L);

        assertEquals("iris-img://payload?exp=1&sig=deadbeef", reference);
        verify(uploadService).issueImageReference("a.png", 7L);
    }

    @Test
    void foreignInputIsPassedThroughUntouched() {
        AgentImageUploadService uploadService = mock(AgentImageUploadService.class);

        String reference = resolver(uploadService)
                .toReference("https://evil.example/eye.png", 7L);

        assertEquals("https://evil.example/eye.png", reference);
        verify(uploadService, never()).issueImageReference(anyString(), eq(7L));
    }

    @Test
    void suspiciousFileNameIsNotSigned() {
        AgentImageUploadService uploadService = mock(AgentImageUploadService.class);

        String reference = resolver(uploadService)
                .toReference(AgentImageUploadService.VIEW_PREFIX + "../../secret.png?x=1", 7L);

        assertEquals(AgentImageUploadService.VIEW_PREFIX + "../../secret.png?x=1", reference);
        verify(uploadService, never()).issueImageReference(anyString(), eq(7L));
    }

    @Test
    void signingFailureKeepsTheOriginalValueSoTheToolRejectsIt() {
        AgentImageUploadService uploadService = mock(AgentImageUploadService.class);
        when(uploadService.issueImageReference(anyString(), eq(7L)))
                .thenThrow(new IllegalStateException("密钥未配置"));

        String reference = resolver(uploadService)
                .toReference(AgentImageUploadService.VIEW_PREFIX + "a.png", 7L);

        assertEquals(AgentImageUploadService.VIEW_PREFIX + "a.png", reference);
    }

    @Test
    void anonymousCallerCannotGetAReference() {
        AgentImageUploadService uploadService = mock(AgentImageUploadService.class);

        String reference = resolver(uploadService)
                .toReference(AgentImageUploadService.VIEW_PREFIX + "a.png", null);

        assertEquals(AgentImageUploadService.VIEW_PREFIX + "a.png", reference);
        verify(uploadService, never()).issueImageReference(anyString(), eq(null));
    }
}
