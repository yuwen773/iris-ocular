package com.csust.eyediagnosis.integration;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

/**
 * 隔离库套件自己搭 {@code t_diagnosis_task} 的 schema，只重放「建表」迁移，
 * 之后追加的 ALTER 迁移不会自动跟上来。新增列时如果忘了在这里补，
 * 失败会以 {@code Unknown column} 的形式出现在业务查询里，而不是测试搭建阶段。
 *
 * <p>判断一律按列是否存在：表在、列不在，正是「这个库跑过旧版本套件」的形状，
 * 按表存在与否判断会跳过 ALTER。
 */
public final class DiagnosisTaskSchemaFixture {

    private DiagnosisTaskSchemaFixture() {
    }

    /** 重放 V18 建表迁移与之后所有 ALTER 迁移（当前只有 V33 的 restrictReason）。 */
    public static void apply(DataSource dataSource, JdbcTemplate jdbc) {
        if (columnExists(jdbc, "taskId") == 0) {
            new ResourceDatabasePopulator(
                    new ClassPathResource("db/migration/V18__diagnosis_task.sql")).execute(dataSource);
        }
        if (columnExists(jdbc, "restrictReason") == 0) {
            new ResourceDatabasePopulator(
                    new ClassPathResource("db/migration/V33__diagnosis_task_restrict_reason.sql")).execute(dataSource);
        }
    }

    private static int columnExists(JdbcTemplate jdbc, String column) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema=DATABASE() AND table_name='t_diagnosis_task' AND column_name=?",
                Integer.class, column);
    }
}
