package com.csust.eyediagnosis.grpc;

import java.util.Locale;

/**
 * gRPC 目标主机判定的唯一来源。
 *
 * <p>「回环」在部署安全基线（{@code StartupSafetyGuard} 拒绝密文外露）与客户端（明文连接
 * 记警告）里各判一次，判据必须同源：一处放宽、另一处没收，就会出现「启动检查认为本机、
 * 客户端认为远程」这类互相矛盾的行为。
 *
 * <p>空主机名按回环处理：没配 host 时客户端连接的是本机默认地址，与 {@code 127.0.0.1} 同义。
 * 只认字面形式（IPv4 回环 / IPv6 回环 / localhost），不做 DNS 解析——解析结果随时会变，
 * 拿它当启动期安全判据等于没判。
 */
public final class GrpcHosts {

    private GrpcHosts() {
    }

    /** 主机名是否是字面回环地址（含空值）。大小写不敏感，容忍 IPv6 的方括号写法。 */
    public static boolean isLoopback(String host) {
        if (host == null) {
            return true;
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.isEmpty()
                || "127.0.0.1".equals(normalized)
                || "::1".equals(normalized)
                || "localhost".equals(normalized);
    }
}
