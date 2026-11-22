import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.csust.eyediagnosis.agent.observability.ToolResourceContext;
import com.csust.eyediagnosis.agent.rag.DocumentParser;
import com.csust.eyediagnosis.agent.rag.TextChunkerService;
import com.csust.eyediagnosis.agent.tools.DocumentSearchTool;
import com.csust.eyediagnosis.manager.CosManager;
import com.csust.eyediagnosis.mapper.KnowledgeDocumentMapper;
import com.csust.eyediagnosis.model.entity.KnowledgeDocument;
import com.csust.eyediagnosis.service.impl.DocumentProcessingService;
import com.csust.eyediagnosis.service.impl.KnowledgeBaseServiceImpl;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.flywaydb.core.Flyway;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import static org.mockito.Mockito.*;

class IrisRound2StorageProbe {
    private static final String TENANT = "default_tenant";
    private static final String DATABASE = "default_database";
    private static final String COLLECTION = "iris-codex-rc-round2";
    private static final long USER = 42L;

    public static void main(String[] args) throws Exception {
        var datasource = new MysqlDataSource();
        datasource.setURL("jdbc:mysql://127.0.0.1:" + args[0] + "/iris_audit?sslMode=DISABLED&allowPublicKeyRetrieval=true");
        datasource.setUser("root");
        try (var db = datasource.getConnection(); var ddl = db.createStatement()) { ddl.execute(Files.readString(java.nio.file.Path.of(args[2]))); }
        var factory = new MybatisSqlSessionFactoryBean();
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(false);
        config.addMapper(KnowledgeDocumentMapper.class);
        factory.setDataSource(datasource);
        factory.setConfiguration(config);
        factory.afterPropertiesSet();

        var upserts = new AtomicInteger();
        var failedUpsertNumber = new AtomicInteger(-1);
        var failedDeletes = new AtomicInteger();
        var deleteAttempts = new AtomicInteger();
        var restClient = RestClient.builder().requestInterceptor((request, body, execution) -> {
            String path = request.getURI().getPath();
            if (path.endsWith("/upsert") && upserts.incrementAndGet() == failedUpsertNumber.get()) {
                throw new IOException("injected second batch write failure");
            }
            if (path.endsWith("/delete")) {
                deleteAttempts.incrementAndGet();
                if (failedDeletes.get() > 0) {
                    failedDeletes.decrementAndGet();
                    throw new IOException("injected vector cleanup failure");
                }
            }
            return execution.execute(request, body);
        });
        var api = ChromaApi.builder().baseUrl("http://127.0.0.1:" + args[1])
                .restClientBuilder(restClient).build();
        var embedding = new EmbeddingModel() {
            public EmbeddingResponse call(EmbeddingRequest request) {
                return new EmbeddingResponse(IntStream.range(0, request.getInstructions().size())
                        .mapToObj(i -> new Embedding(new float[]{1, 0, 0}, i)).toList());
            }
            public float[] embed(Document doc) { return new float[]{1, 0, 0}; }
        };
        var store = ChromaVectorStore.builder(api, embedding).tenantName(TENANT).databaseName(DATABASE)
                .collectionName(COLLECTION).initializeSchema(true).initializeImmediately(true).build();
        String collectionId = api.getCollection(TENANT, DATABASE, COLLECTION).id();
        var chunker = mock(TextChunkerService.class);
        when(chunker.chunk(anyString(), anyMap())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            int count = text.contains("original") ? 11 : 1;
            return IntStream.range(0, count).mapToObj(i -> new Document(
                    "synthetic-" + i, text + "-part-" + i, invocation.getArgument(1))).toList();
        });
        try (var session = factory.getObject().openSession(true)) {
            var mapper = session.getMapper(KnowledgeDocumentMapper.class);
            var processor = new DocumentProcessingService();
            ReflectionTestUtils.setField(processor, "documentParser", new DocumentParser());
            ReflectionTestUtils.setField(processor, "textChunkerService", chunker);
            ReflectionTestUtils.setField(processor, "knowledgeDocumentMapper", mapper);
            ReflectionTestUtils.setField(processor, "vectorStore", store);
            var tool = new DocumentSearchTool();
            ReflectionTestUtils.setField(tool, "searchTopK", 20);
            ReflectionTestUtils.setField(tool, "finalTopK", 20);
            ReflectionTestUtils.setField(tool, "vectorStore", store);
            ReflectionTestUtils.setField(tool, "knowledgeDocumentMapper", mapper);

            long first = createRow(mapper);
            failedUpsertNumber.set(upserts.get() + 2);
            process(processor, first, "original-cleanup");
            String firstAnswer = tool.searchDocuments("synthetic", USER);
            int firstResourceCount = ToolResourceContext.drain().size();
            long firstVectorCount = api.countEmbeddings(TENANT, DATABASE, collectionId);
            int firstStatus = mapper.selectById(first).getStatus();
            System.out.printf("PROBE successfulCleanup status=%d vectorCount=%d oldText=%s resources=%d deletes=%d%n",
                    firstStatus, firstVectorCount, firstAnswer.contains("original-cleanup"), firstResourceCount, deleteAttempts.get());
            check(firstStatus == 2 && firstVectorCount == 0 && firstResourceCount == 0, "cleanup control");

            long second = createRow(mapper);
            failedUpsertNumber.set(upserts.get() + 2);
            failedDeletes.set(4);
            process(processor, second, "original-residual");
            String failedAnswer = tool.searchDocuments("synthetic", USER);
            int failedResourceCount = ToolResourceContext.drain().size();
            long failedVectorCount = api.countEmbeddings(TENANT, DATABASE, collectionId);
            int failedStatus = mapper.selectById(second).getStatus();
            System.out.printf("PROBE cleanupUnavailable status=%d vectorCount=%d oldText=%s resources=%d deletes=%d%n",
                    failedStatus, failedVectorCount, failedAnswer.contains("original-residual"), failedResourceCount, deleteAttempts.get());
            check(failedStatus == 2 && failedVectorCount == 10 && !failedAnswer.contains("original-residual")
                    && failedResourceCount == 0, "failed row must be excluded");

            var knowledge = new KnowledgeBaseServiceImpl();
            ReflectionTestUtils.setField(knowledge, "baseMapper", mapper);
            ReflectionTestUtils.setField(knowledge, "cosManager", mock(CosManager.class));
            ReflectionTestUtils.setField(knowledge, "documentProcessingService", processor);
            failedDeletes.set(1);
            knowledge.updateDocument(second, new MockMultipartFile("file", "replacement.txt", "text/plain",
                    "current-replacement".getBytes(java.nio.charset.StandardCharsets.UTF_8)), "replacement", USER);

            String result = tool.searchDocuments("synthetic", USER);
            var resources = ToolResourceContext.drain();
            long vectorCount = api.countEmbeddings(TENANT, DATABASE, collectionId);
            var row = mapper.selectById(second);
            System.out.printf("PROBE updateAfterFailedCleanup status=%d publishedChunks=%d vectorCount=%d oldText=%s newText=%s resources=%d%n",
                    row.getStatus(), row.getChunkCount(), vectorCount, result.contains("original-residual"),
                    result.contains("current-replacement"), resources.size());
            check(row.getStatus() == 1 && row.getChunkCount() == 1 && vectorCount == 10
                    && result.contains("original-residual") && result.contains("current-replacement")
                    && resources.stream().anyMatch(ref -> ref.id().equals("doc:" + second)), "stale-generation reproduction");
            knowledge.deleteDocument(second, owner());
            String deletedAnswer = tool.searchDocuments("synthetic", USER);
            int deletedResources = ToolResourceContext.drain().size();
            long deletedVectors = api.countEmbeddings(TENANT, DATABASE, collectionId);
            System.out.printf("PROBE deleteAfterRetry rowPresent=%s vectors=%d oldText=%s resources=%d%n",
                    mapper.selectById(second) != null, deletedVectors, deletedAnswer.contains("original-residual"), deletedResources);
            check(mapper.selectById(second) == null && deletedVectors == 9 && deletedResources == 0
                    && !deletedAnswer.contains("original-residual"), "deleted row must filter the physical tail");
        }
    }

    private static com.csust.eyediagnosis.model.entity.User owner() {
        var user = new com.csust.eyediagnosis.model.entity.User();
        user.setId(USER);
        user.setUserRole("user");
        return user;
    }

    private static long createRow(KnowledgeDocumentMapper mapper) {
        var row = new KnowledgeDocument();
        row.setUserId(USER);
        row.setTitle("original");
        row.setFileName("original.txt");
        row.setFileUrl("fixture-original.txt");
        row.setFileType("txt");
        row.setStatus(0);
        mapper.insert(row);
        return row.getId();
    }

    private static void process(DocumentProcessingService processor, long id, String text) throws IOException {
        var file = Files.createTempFile("iris-round2-rag-", ".txt");
        Files.writeString(file, text);
        try {
            processor.processDocument(id, file.toFile(), "txt", "original");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
