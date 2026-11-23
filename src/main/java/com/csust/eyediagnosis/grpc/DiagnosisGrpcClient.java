package com.csust.eyediagnosis.grpc;

import com.csust.eyediagnosis.config.GrpcConfig;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class DiagnosisGrpcClient {

    private final GrpcConfig grpcConfig;
    private ManagedChannel channel;
    private com.csust.eyediagnosis.grpc.DiagnosisServiceGrpc.DiagnosisServiceBlockingStub blockingStub;

    public DiagnosisGrpcClient(GrpcConfig grpcConfig) {
        this.grpcConfig = grpcConfig;
    }

    @PostConstruct
    public void init() {
        String host = grpcConfig.getHost();
        boolean loopback = host == null || host.isBlank()
                || "127.0.0.1".equals(host) || "::1".equals(host) || "localhost".equalsIgnoreCase(host);
        if (!loopback) {
            // 明文 gRPC 到非本机地址：影像字节与诊断结果都不过任何加密，也不做对端身份校验。
            // 跨主机部署必须走隧道 / 反向代理 TLS 终止 / 私有网络隔离；这里至少不要安静地发出去。
            log.warn("gRPC 诊断通道使用明文连接到非回环地址 {}:{}——跨主机部署必须由网络隔离或 TLS 隧道保护",
                    host, grpcConfig.getPort());
        }
        channel = ManagedChannelBuilder
                .forAddress(host, grpcConfig.getPort())
                .usePlaintext()
                .build();
        blockingStub = com.csust.eyediagnosis.grpc.DiagnosisServiceGrpc.newBlockingStub(channel);
    }

    public com.csust.eyediagnosis.grpc.DiagnosisProto.DiagnosisResponse diagnose(byte[] imageData, String imageFormat) {
        com.csust.eyediagnosis.grpc.DiagnosisProto.DiagnosisRequest request = com.csust.eyediagnosis.grpc.DiagnosisProto.DiagnosisRequest.newBuilder()
                .setImageData(com.google.protobuf.ByteString.copyFrom(imageData))
                .setImageFormat(imageFormat)
                .build();
        try {
            return blockingStub
                    .withDeadlineAfter(grpcConfig.getDeadlineMs(), TimeUnit.MILLISECONDS)
                    .diagnose(request);
        } catch (StatusRuntimeException e) {
            log.error("gRPC diagnosis failed: {}", e.getStatus().getDescription(), e);
            throw new RuntimeException("诊断服务调用失败: " + e.getStatus().getDescription(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (channel != null && !channel.isShutdown()) {
            channel.shutdown();
            try {
                channel.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                channel.shutdownNow();
            }
        }
    }
}
