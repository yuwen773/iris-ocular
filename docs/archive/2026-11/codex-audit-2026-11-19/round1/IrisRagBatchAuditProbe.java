import com.csust.eyediagnosis.agent.rag.*;
import com.csust.eyediagnosis.agent.tools.DocumentSearchTool;
import com.csust.eyediagnosis.agent.observability.ToolResourceContext;
import com.csust.eyediagnosis.mapper.KnowledgeDocumentMapper;
import com.csust.eyediagnosis.model.entity.KnowledgeDocument;
import com.csust.eyediagnosis.service.impl.DocumentProcessingService;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import static org.mockito.Mockito.*;

class IrisRagBatchAuditProbe {
    public static void main(String[] args) throws Exception {
        var parser = mock(DocumentParser.class);
        when(parser.parse(anyString(), eq("txt"))).thenReturn(List.of("synthetic document"));
        when(parser.buildMetadata(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new HashMap<>(Map.of("docId", "124", "title", "synthetic")));
        var chunker = mock(TextChunkerService.class);
        when(chunker.chunk(anyString(), anyMap())).thenAnswer(invocation -> IntStream.range(0, 11)
            .mapToObj(i -> new Document("source-" + i, "synthetic segment " + i, invocation.getArgument(1)))
            .toList());
        var mapper = mock(KnowledgeDocumentMapper.class);
        var doc = new KnowledgeDocument();
        doc.setId(124L);
        doc.setUserId(42L);
        when(mapper.selectById(124L)).thenReturn(doc);
        var vector = mock(VectorStore.class);
        var written = new ArrayList<Document>();
        var batches = new AtomicInteger();
        doAnswer(invocation -> {
            if (batches.incrementAndGet() == 2) throw new IllegalStateException("synthetic batch failure");
            written.addAll(invocation.getArgument(0));
            return null;
        }).when(vector).add(anyList());
        when(vector.similaritySearch(any(SearchRequest.class))).thenAnswer(invocation -> List.copyOf(written));

        var processor = new DocumentProcessingService();
        ReflectionTestUtils.setField(processor, "documentParser", parser);
        ReflectionTestUtils.setField(processor, "textChunkerService", chunker);
        ReflectionTestUtils.setField(processor, "knowledgeDocumentMapper", mapper);
        ReflectionTestUtils.setField(processor, "vectorStore", vector);
        processor.processDocument(124L, Files.createTempFile("iris-rag-audit", ".txt").toFile(), "txt", "synthetic");
        var update = org.mockito.ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(mapper).updateById(update.capture());
        var retriever = new DocumentSearchTool();
        ReflectionTestUtils.setField(retriever, "searchTopK", 12);
        ReflectionTestUtils.setField(retriever, "finalTopK", 5);
        ReflectionTestUtils.setField(retriever, "vectorStore", vector);
        String answer = retriever.searchDocuments("synthetic query", 42L);
        var refs = ToolResourceContext.drain();
        System.out.println("PROBE failedDocumentStatus=" + update.getValue().getStatus()
            + " chunkCount=" + update.getValue().getChunkCount() + " visibleChunks=" + written.size()
            + " textMatches=" + answer.contains("synthetic segment 0") + " resourceRefs=" + refs);
        if (update.getValue().getStatus() != 2 || written.size() != 10 || refs.isEmpty()
                || !answer.contains("synthetic segment 0")) throw new AssertionError("fixture did not reproduce");
    }
}

