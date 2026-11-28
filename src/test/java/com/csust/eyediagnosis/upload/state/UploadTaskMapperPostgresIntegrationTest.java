package com.csust.eyediagnosis.upload.state;

import com.csust.eyediagnosis.integration.LegacyGateSupport;
import com.csust.eyediagnosis.mapper.UploadTaskMapper;
import com.csust.eyediagnosis.model.entity.UploadTask;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code UploadTaskMapper.xml} 里「保留状态」过滤的真实 PostgreSQL 回归测试。
 *
 * <p>这个过滤曾经写成 {@code status IN (1, 3)}，而实际落库的恒为 PERSISTED(2)，
 * 于是补偿对账会认为用户的所有图片都是孤儿并将其删除。缺陷在 SQL 里，
 * 纯 Mockito 单测覆盖不到，因此这里连真实数据库跑一遍。
 *
 * <p>隔离库由 {@code BUSINESS_TEST_URL} 指定（库名必须叫 {@code business_test}）：
 * 未设置时整类跳过，设置了但指向别的库时直接失败。schema 由 {@code db/postgresql}
 * 的 Flyway 迁移建立——与生产走同一份迁移历史，不再借用已退役的 MySQL 线。
 */
class UploadTaskMapperPostgresIntegrationTest {

    private static final long TEST_USER_ID = 9_999_991L;

    private SqlSessionFactory sqlSessionFactory;

    @BeforeEach
    void setUp() throws Exception {
        String url = LegacyGateSupport.assumeIsolated(System.getenv("BUSINESS_TEST_URL"), "business_test");
        DataSource dataSource = LegacyGateSupport.dataSource(url);
        LegacyGateSupport.ensureSchema(url, dataSource, List.of(), () -> {
            throw new IllegalStateException("BUSINESS_TEST_URL must point at PostgreSQL");
        });

        Configuration configuration = new Configuration(new Environment(
                "upload-task-it", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.postgresql.Driver", LegacyGateSupport.jdbcUrl(url),
                        LegacyGateSupport.credentials(url)[0], LegacyGateSupport.credentials(url)[1])));
        configuration.setMapUnderscoreToCamelCase(false);
        new XMLMapperBuilder(
                Resources.getResourceAsStream("mapper/UploadTaskMapper.xml"),
                configuration,
                "mapper/UploadTaskMapper.xml",
                configuration.getSqlFragments()).parse();
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (sqlSessionFactory == null) {
            return;
        }
        try (SqlSession session = sqlSessionFactory.openSession()) {
            Connection connection = session.getConnection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM t_upload_task WHERE userId = ?")) {
                statement.setLong(1, TEST_USER_ID);
                statement.executeUpdate();
            }
            connection.commit();
        }
    }

    @Test
    void persistedTaskKeyIsRetainedWhileFailedTaskKeyIsNot() {
        String persistedKey = "agent-chat/it/persisted.png";
        String failedKey = "agent-chat/it/failed.png";
        insert("task-it-persisted", persistedKey, UploadStatus.PERSISTED);
        insert("task-it-failed", failedKey, UploadStatus.FAILED);

        List<String> active = selectActiveKeys();

        assertTrue(active.contains(persistedKey), "PERSISTED 状态的对象必须被保留，实际: " + active);
        assertFalse(active.contains(failedKey), "FAILED 状态的对象不应被保留，实际: " + active);
    }

    @Test
    void deletedTaskKeyDropsOutOfTheActiveSet() {
        String key = "agent-chat/it/deleted.png";
        insert("task-it-deleted", key, UploadStatus.PERSISTED);
        assertTrue(selectActiveKeys().contains(key));

        int affected = updateStatus("task-it-deleted",
                UploadStatus.PERSISTED.getCode(), UploadStatus.DELETED.getCode());

        assertEquals(1, affected);
        assertFalse(selectActiveKeys().contains(key), "标记为 DELETED 后不应再被保留");
    }

    @Test
    void updateStatusIsCompareAndSet() {
        insert("task-it-cas", "agent-chat/it/cas.png", UploadStatus.PERSISTED);

        int affected = updateStatus("task-it-cas",
                UploadStatus.PERSISTED.getCode(), UploadStatus.DELETED.getCode());
        int stale = updateStatus("task-it-cas",
                UploadStatus.PERSISTED.getCode(), UploadStatus.FAILED.getCode());

        assertEquals(1, affected);
        assertEquals(0, stale, "状态已被推进后，旧的 fromStatus 不应再匹配");
    }

    private void insert(String taskId, String objectKey, UploadStatus status) {
        UploadTask task = new UploadTask();
        task.setTaskId(taskId);
        task.setUserId(TEST_USER_ID);
        task.setObjectKey(objectKey);
        task.setStatus(status.getCode());
        task.setRetryCount(0);
        try (SqlSession session = sqlSessionFactory.openSession()) {
            // 走 mapper 接口，验证接口方法与 XML 语句的绑定关系
            session.getMapper(UploadTaskMapper.class).insertPending(task);
            session.commit();
        }
    }

    private List<String> selectActiveKeys() {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            return session.getMapper(UploadTaskMapper.class)
                    .selectActiveKeys(TEST_USER_ID, UploadStatus.activeCodes());
        }
    }

    private int updateStatus(String taskId, int fromStatus, int toStatus) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            int affected = session.getMapper(UploadTaskMapper.class)
                    .updateStatus(taskId, fromStatus, toStatus);
            session.commit();
            return affected;
        }
    }
}
