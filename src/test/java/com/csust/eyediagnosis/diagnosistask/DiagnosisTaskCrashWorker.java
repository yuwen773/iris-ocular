package com.csust.eyediagnosis.diagnosistask;

import com.csust.eyediagnosis.grpc.DiagnosisGrpcClient;
import com.csust.eyediagnosis.grpc.DiagnosisProto;
import com.csust.eyediagnosis.integration.LegacyGateSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

/** Child JVM halts around actual auto-commit statements made by the runner. */
public class DiagnosisTaskCrashWorker {
    public static void main(String[] args) throws Exception {
        String url = args[0], phase = args[1];
        LegacyGateSupport.requireIsolated(url, "ISSUE17_TEST_URL", "issue17_test");
        byte[] image = Base64.getDecoder().decode(args[2]);
        String[] credentials = LegacyGateSupport.credentials(url);
        var base = new DriverManagerDataSource(LegacyGateSupport.jdbcUrl(url), credentials[0], credentials[1]);
        var targetExecuted = new java.util.concurrent.atomic.AtomicBoolean();
        var intercepted = new DelegatingDataSource(base) {
            @Override public Connection getConnection() throws java.sql.SQLException {
                Connection connection = super.getConnection();
                return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Connection.class}, (proxy, method, arguments) -> {
                    try {
                        Object result = method.invoke(connection, arguments);
                        if (method.getName().equals("commit") && targetExecuted.get()) Runtime.getRuntime().halt(72);
                        if (method.getName().equals("prepareStatement") && arguments[0] instanceof String sql
                                && ((phase.equals("reserved") && sql.contains("SET attemptCount=attemptCount+1"))
                                // 不写死整段 SET 子句：finish() 的列会随迁移增加（V33 加了 restrictReason）。
                                || (!phase.equals("reserved") && sql.contains("SET status=?")
                                        && sql.contains("inferenceSnapshot=?")))) {
                            PreparedStatement statement = (PreparedStatement) result;
                            return Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{PreparedStatement.class}, (p, m, a) -> {
                                try {
                                    if (m.getName().equals("executeUpdate") && phase.equals("before")) Runtime.getRuntime().halt(71);
                                    Object value = m.invoke(statement, a);
                                    if (m.getName().equals("executeUpdate") && phase.equals("uncommitted")) Runtime.getRuntime().halt(73);
                                    if (m.getName().equals("executeUpdate")) targetExecuted.set(true);
                                    return value;
                                } catch (InvocationTargetException e) { throw e.getCause(); }
                            });
                        }
                        return result;
                    } catch (InvocationTargetException e) { throw e.getCause(); }
                });
            }
        };
        DiagnosisTaskStorage storage = new DiagnosisTaskStorage() {
            public String upload(String key, byte[] bytes, String type) { return "v1"; }
            public byte[] readVersion(String key, String version) { return image; }
        };
        var grpc = new DiagnosisGrpcClient(null) {
            @Override public DiagnosisProto.DiagnosisResponse diagnose(byte[] bytes, String format) {
                return DiagnosisProto.DiagnosisResponse.newBuilder().setPredictedLabel("child")
                        .setOodStatus(DiagnosisProto.OodStatus.IN_DISTRIBUTION).build();
            }
        };
        new DiagnosisTaskRunner(new JdbcTemplate(intercepted), storage, grpc,
                Clock.fixed(Instant.parse("2026-11-05T00:00:00Z"), ZoneOffset.UTC)).runOnce();
        System.exit(3);
    }
}
