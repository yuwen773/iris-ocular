package com.csust.eyediagnosis.grpc;

import com.csust.eyediagnosis.config.GrpcConfig;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLException;
import java.io.File;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class DiagnosisGrpcClient {

    private final GrpcConfig grpcConfig;
    private ManagedChannel channel;
    private DiagnosisServiceGrpc.DiagnosisServiceBlockingStub blockingStub;

    public DiagnosisGrpcClient(GrpcConfig grpcConfig) {
        this.grpcConfig = grpcConfig;
    }

    @PostConstruct
    public void init() {
        channel = buildChannel();
        blockingStub = DiagnosisServiceGrpc.newBlockingStub(channel);
    }

    private ManagedChannel buildChannel() {
        String host = grpcConfig.getHost();
        if (!grpcConfig.isTlsEnabled()) {
            if (!GrpcHosts.isLoopback(host)) {
                // 明文 gRPC 到非本机地址：影像字节与诊断结果既不过加密，也不校验对端身份。
                // 部署 profile 下由 StartupSafetyGuard 直接拒绝启动；这里再记一条，
                // 让本地/联调环境也看得见这条链路没有保护。
                log.warn("gRPC 诊断通道使用明文连接到非回环地址 {}:{}——必须由 TLS 或网络隔离保护",
                        host, grpcConfig.getPort());
            }
            return ManagedChannelBuilder.forAddress(host, grpcConfig.getPort())
                    .usePlaintext()
                    .build();
        }
        if (isBlank(grpcConfig.getTlsClientCertPath()) != isBlank(grpcConfig.getTlsClientKeyPath())) {
            throw new IllegalStateException(
                    "gRPC mTLS 需要同时配置 tls-client-cert-path 与 tls-client-key-path");
        }
        try {
            SslContextBuilder ssl = GrpcSslContexts.forClient();
            if (!isBlank(grpcConfig.getTlsCaCertPath())) {
                ssl.trustManager(new File(grpcConfig.getTlsCaCertPath()));
            }
            if (!isBlank(grpcConfig.getTlsClientCertPath())) {
                ssl.keyManager(new File(grpcConfig.getTlsClientCertPath()),
                        new File(grpcConfig.getTlsClientKeyPath()));
            }
            log.info("gRPC 诊断通道启用 TLS（host={}，mTLS={}）",
                    host, !isBlank(grpcConfig.getTlsClientCertPath()));
            return NettyChannelBuilder.forAddress(host, grpcConfig.getPort())
                    .sslContext(ssl.build())
                    .build();
        } catch (SSLException e) {
            throw new IllegalStateException("gRPC TLS 配置无效: " + e.getMessage(), e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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
