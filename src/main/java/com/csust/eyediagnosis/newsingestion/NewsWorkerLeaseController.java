package com.csust.eyediagnosis.newsingestion;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Internal worker control-plane controller implementing {@code contracts/news/openapi.json}.
 *
 * <p>Returns wire-native JSON and OpenAPI HTTP statuses without {@code BaseResponse} wrapping.
 */
@RestController
@RequestMapping("/internal/news/v1/leases")
@RequiredArgsConstructor
public class NewsWorkerLeaseController {

    private final NewsIngestionService ingestionService;
    private final NewsWireCodec codec;

    @PostMapping(value = "/claim", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> claim(HttpServletRequest request) {
        NewsWorkerIdentity worker = requireWorker(request);
        Optional<NewsWireModels.Lease> lease = ingestionService.claim(worker);
        if (lease.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(codec.writeLease(lease.get()));
    }

    @PostMapping(value = "/{taskId}/batches", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> submitBatch(@PathVariable String taskId,
                                              @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                              HttpServletRequest request) {
        NewsWorkerIdentity worker = requireWorker(request);
        byte[] rawBody = readBoundedBody(request);
        String json = ingestionService.submitBatch(worker, taskId, idempotencyKey, rawBody);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json);
    }

    @PostMapping(value = "/{taskId}/complete", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> complete(@PathVariable String taskId,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                           HttpServletRequest request) {
        NewsWorkerIdentity worker = requireWorker(request);
        byte[] rawBody = readBoundedBody(request);
        String json = ingestionService.complete(worker, taskId, idempotencyKey, rawBody);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json);
    }

    @PostMapping(value = "/{taskId}/fail", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> fail(@PathVariable String taskId,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                       HttpServletRequest request) {
        NewsWorkerIdentity worker = requireWorker(request);
        byte[] rawBody = readBoundedBody(request);
        String json = ingestionService.fail(worker, taskId, idempotencyKey, rawBody);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json);
    }

    private static NewsWorkerIdentity requireWorker(HttpServletRequest request) {
        Object attr = request.getAttribute(NewsWorkerAuthFilter.ATTR_WORKER_IDENTITY);
        if (attr instanceof NewsWorkerIdentity identity) {
            return identity;
        }
        throw NewsControlException.unauthenticated();
    }

    private static byte[] readBoundedBody(HttpServletRequest request) {
        long contentLength = request.getContentLengthLong();
        if (contentLength > NewsWireModels.MAX_REQUEST_BYTES) {
            throw NewsControlException.invalidRequest();
        }
        try (InputStream in = request.getInputStream()) {
            int initialSize = contentLength > 0 ? (int) Math.min(contentLength, NewsWireModels.MAX_REQUEST_BYTES) : 1024;
            ByteArrayOutputStream out = new ByteArrayOutputStream(initialSize);
            byte[] buf = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buf)) != -1) {
                total += read;
                if (total > NewsWireModels.MAX_REQUEST_BYTES) {
                    throw NewsControlException.invalidRequest();
                }
                out.write(buf, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw NewsControlException.invalidRequest();
        }
    }
}
