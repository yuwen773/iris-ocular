package com.csust.eyediagnosis.service.impl;

import com.csust.eyediagnosis.manager.CosManager;
import com.csust.eyediagnosis.mapper.KnowledgeDocumentMapper;
import com.csust.eyediagnosis.model.entity.KnowledgeDocument;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文档更新失败时，刚写进 COS 的那个对象要么被文档行引用，要么被删掉——不能悬空。
 *
 * <p>判据是回读数据库，不是「这一步失败了」：候选版本提交抛出异常，也可能发生在
 * 文档行已经改指向新对象之后；那时删对象就是把正在用的文件删了。
 */
class KnowledgeBaseServiceImplOrphanObjectTest {

    private static KnowledgeDocument readyDocument() {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(9L);
        doc.setUserId(42L);
        doc.setStatus(1);
        doc.setIndexVersion("v1");
        doc.setFileUrl("cos/old.txt");
        return doc;
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "eye.txt", "text/plain", "abc".getBytes());
    }

    private static KnowledgeBaseServiceImpl service(KnowledgeDocumentMapper mapper,
                                                    CosManager cos,
                                                    DocumentProcessingService processor) {
        KnowledgeBaseServiceImpl service = new KnowledgeBaseServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "cosManager", cos);
        ReflectionTestUtils.setField(service, "documentProcessingService", processor);
        return service;
    }

    private static void failInsideCandidate(DocumentProcessingService processor) {
        doThrow(new IllegalStateException("候选版本提交失败"))
                .when(processor).processDocumentCandidate(
                        anyLong(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void objectWithNoDatabaseReferenceIsDeleted() {
        KnowledgeDocumentMapper mapper = mock(KnowledgeDocumentMapper.class);
        CosManager cos = mock(CosManager.class);
        DocumentProcessingService processor = mock(DocumentProcessingService.class);
        when(mapper.selectById(9L)).thenReturn(readyDocument());
        when(mapper.selectCount(any())).thenReturn(0L);
        failInsideCandidate(processor);

        KnowledgeBaseServiceImpl service = service(mapper, cos, processor);

        assertThrows(IllegalStateException.class, () -> service.updateDocument(9L, file(), "标题", 42L));
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(cos).deleteObject(key.capture());
        assertTrue(key.getValue().endsWith("_eye.txt"), key.getValue());
    }

    @Test
    void objectAlreadyReferencedByTheRowIsKept() {
        KnowledgeDocumentMapper mapper = mock(KnowledgeDocumentMapper.class);
        CosManager cos = mock(CosManager.class);
        DocumentProcessingService processor = mock(DocumentProcessingService.class);
        when(mapper.selectById(9L)).thenReturn(readyDocument());
        // 文档行已经改指向新对象（updateById 成功、随后提交 ETL 才失败）
        when(mapper.selectCount(any())).thenReturn(1L);
        failInsideCandidate(processor);

        KnowledgeBaseServiceImpl service = service(mapper, cos, processor);

        assertThrows(IllegalStateException.class, () -> service.updateDocument(9L, file(), "标题", 42L));
        verify(cos, never()).deleteObject(anyString());
    }
}
