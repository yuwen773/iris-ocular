package com.csust.eyediagnosis.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@Data
@ConfigurationProperties(prefix = "diagnosis.grpc")
public class GrpcConfig {
    private String host = "127.0.0.1";
    private int port = 50051;
    private int deadlineMs = 10000;

    // ── 传输安全 ────────────────────────────────────────────────────────
    // 影像字节与诊断结果都走这条通道。回环/私有网络隔离之外的明文连接既没有加密，
    // 也没有对端身份校验，因此跨主机部署必须显式选一条：开 TLS（可带 mTLS 客户端证书），
    // 或明确声明这段链路已被网络隔离覆盖。

    /** 是否对 gRPC 通道启用 TLS。 */
    private boolean tlsEnabled = false;

    /** 服务端证书的 CA（PEM）；留空表示用 JDK 默认信任库（公网 CA 签发的证书）。 */
    private String tlsCaCertPath;

    /** mTLS 客户端证书（PEM）；配置了就必须同时配置私钥。 */
    private String tlsClientCertPath;

    /** mTLS 客户端私钥（PEM）。 */
    private String tlsClientKeyPath;

    /**
     * 显式承认「非回环地址上的明文 gRPC 由网络隔离保护」。
     *
     * <p>默认 false：部署 profile 下非回环 + 明文 + 未承认隔离，启动即失败，
     * 而不是安静地把患者影像发到一条没有加密的链路上。
     */
    private boolean plaintextAllowed = false;
}
