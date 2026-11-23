package com.csust.eyediagnosis.grpc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回环判定只此一处：启动基线与 gRPC 客户端必须同源。
 */
class GrpcHostsTest {

    @Test
    void literalLoopbackNamesAreRecognised() {
        assertTrue(GrpcHosts.isLoopback("127.0.0.1"));
        assertTrue(GrpcHosts.isLoopback("::1"));
        assertTrue(GrpcHosts.isLoopback("[::1]"));
        assertTrue(GrpcHosts.isLoopback("localhost"));
        assertTrue(GrpcHosts.isLoopback("LocalHost"));
    }

    @Test
    void missingHostMeansTheLocalDefault() {
        assertTrue(GrpcHosts.isLoopback(null));
        assertTrue(GrpcHosts.isLoopback("   "));
    }

    @Test
    void remoteHostsAreNotLoopback() {
        assertFalse(GrpcHosts.isLoopback("10.0.0.8"));
        assertFalse(GrpcHosts.isLoopback("0.0.0.0"));
        assertFalse(GrpcHosts.isLoopback("gpu-node.internal"));
        // 只认字面形式，不做 DNS 解析：名字里带 localhost 不代表它是本机
        assertFalse(GrpcHosts.isLoopback("localhost.evil.example"));
    }
}
