package com.csust.eyediagnosis.service.impl;

import com.csust.eyediagnosis.agent.rag.DocumentParser;
import com.csust.eyediagnosis.agent.rag.TextChunkerService;
import com.csust.eyediagnosis.manager.CosManager;
import com.csust.eyediagnosis.mapper.KnowledgeDocumentMapper;
import com.csust.eyediagnosis.model.entity.KnowledgeDocument;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文档更新的候选版本：新文件的元数据只在发布成功的那一刻才落库。
 *
 * <p>不变量有两条：
 * 1) 发布成功 → {@code indexVersion} 与 fileName/fileUrl/fileSize/fileType/title 同一次
 *    UPDATE 写进文档行，不会出现「新文件名 + 旧检索内容」；
 * 2) 候选失败 → 文档行原封不动（仍描述正在被检索的旧文件），这次上传的对象被删掉。
 */
class DocumentProcessingServiceCandidateTest {

    private static final DocumentProcessingService.CandidateMetadata METADATA =
            new DocumentProcessingService.CandidateMetadata(
                    "new.txt", "cos/new.txt", 3L, ".txt", "新标题", "cos/old.txt");

    @Test
    void candidatePublishesFileMetadataTogetherWithIndexVersion() throws Exception {
        KnowledgeDocumentMapper mapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeDocument row = new KnowledgeDocument();
        row.setId(9L);
        row.setUserId(42L);
        row.setIndexVersion("v1");
        row.setPendingIndexVersion("v2");
        when(mapper.selectById(9L)).thenReturn(row);
        when(mapper.beginCandidate(9L, "v2")).thenReturn(1);
        when(mapper.promoteCandidate(anyLong(), anyString(), anyInt(), anyString(), anyString(),
                anyLong(), anyString(), anyString())).thenReturn(1);

        VectorStore vectorStore = mock(VectorStore.class);
        CosManager cos = mock(CosManager.class);
        File temp = tempFile();
        DocumentProcessingService service = service(mapper, vectorStore, parserReturning("内容"), cos);

        service.processDocumentCandidate(9L, temp, ".txt", "新标题", "v2", METADATA);

        verify(mapper).promoteCandidate(9L, "v2", 1, "new.txt", "cos/new.txt", 3L, ".txt", "新标题");
        // 发布成功：新对象正被引用，不能删；被取代的旧对象已无引用，必须清掉
        verify(cos).deleteObject("cos/old.txt");
        verify(cos, never()).deleteObject("cos/new.txt");
    }

    @Test
    void failedCandidateLeavesRowOnTheOldFileAndDiscardsTheNewObject() throws Exception {
        KnowledgeDocumentMapper mapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeDocument row = new KnowledgeDocument();
        row.setId(9L);
        row.setUserId(42L);
        row.setIndexVersion("v1");
        row.setPendingIndexVersion("v2");
        when(mapper.selectById(9L)).thenReturn(row);
        when(mapper.beginCandidate(9L, "v2")).thenReturn(1);

        VectorStore vectorStore = mock(VectorStore.class);
        CosManager cos = mock(CosManager.class);
        DocumentParser parser = mock(DocumentParser.class);
        when(parser.parse(any(), any())).thenThrow(new IllegalStateException("解析失败"));
        File temp = tempFile();
        DocumentProcessingService service = service(mapper, vectorStore, parser, cos);

        service.processDocumentCandidate(9L, temp, ".txt", "新标题", "v2", METADATA);

        // 文档行没有被改：发布指针没动，元数据仍是旧文件的
        verify(mapper, never()).promoteCandidate(anyLong(), anyString(), anyInt(), anyString(), anyString(),
                anyLong(), anyString(), anyString());
        verify(mapper).clearCandidate(9L, "v2");
        // 新对象不再被任何行引用，必须删掉
        verify(cos).deleteObject("cos/new.txt");
        // 候选失败绝不碰正在被检索的旧对象
        verify(cos, never()).deleteObject("cos/old.txt");
    }

    @Test
    void supersededCandidateDiscardsItsObjectWithoutTouchingTheActiveVersion() throws Exception {
        KnowledgeDocumentMapper mapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeDocument row = new KnowledgeDocument();
        row.setId(9L);
        row.setUserId(42L);
        row.setIndexVersion("v1");
        row.setPendingIndexVersion("v3"); // 已被更新的候选取代
        when(mapper.selectById(9L)).thenReturn(row);

        CosManager cos = mock(CosManager.class);
        File temp = tempFile();
        DocumentProcessingService service = service(mapper, mock(VectorStore.class), parserReturning("内容"), cos);

        service.processDocumentCandidate(9L, temp, ".txt", "新标题", "v2", METADATA);

        verify(mapper, never()).clearCandidate(anyLong(), anyString());
        verify(cos).deleteObject("cos/new.txt");
        verify(cos, never()).deleteObject("cos/old.txt");
    }

    private DocumentProcessingService service(KnowledgeDocumentMapper mapper, VectorStore vectorStore,
                                              DocumentParser parser, CosManager cos) {
        TextChunkerService chunker = mock(TextChunkerService.class);
        when(chunker.chunk(any(), any())).thenReturn(List.of(
                new Document("chunk-0", "片段", new HashMap<>(Map.of("documentId", 9L)))));
        DocumentProcessingService service = new DocumentProcessingService();
        ReflectionTestUtils.setField(service, "vectorStore", vectorStore);
        ReflectionTestUtils.setField(service, "knowledgeDocumentMapper", mapper);
        ReflectionTestUtils.setField(service, "documentParser", parser);
        ReflectionTestUtils.setField(service, "textChunkerService", chunker);
        ReflectionTestUtils.setField(service, "cosManager", cos);
        return service;
    }

    private DocumentParser parserReturning(String paragraph) {
        DocumentParser parser = mock(DocumentParser.class);
        when(parser.parse(any(), any())).thenReturn(List.of(paragraph));
        when(parser.buildMetadata(any(), any(), any(), any())).thenReturn(new HashMap<>(Map.of("title", "t")));
        return parser;
    }

    private File tempFile() throws Exception {
        File temp = Files.createTempFile("etl-candidate", ".txt").toFile();
        Files.writeString(temp.toPath(), "内容");
        return temp;
    }
}
