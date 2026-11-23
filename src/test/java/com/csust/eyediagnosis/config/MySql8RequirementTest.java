package com.csust.eyediagnosis.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MySQL 8 门禁的行为：够版本放行，5.7 拒绝启动，非 MySQL 不判定，显式关闭时不连库。
 */
class MySql8RequirementTest {

    @Test
    void versionParsingAcceptsEightAndAbove() {
        assertTrue(MySql8Requirement.isSupported("8.0.26"));
        assertTrue(MySql8Requirement.isSupported("8.4.0"));
        assertTrue(MySql8Requirement.isSupported("9.1.2"));
        assertTrue(MySql8Requirement.isSupported("8.0.26-google"));
    }

    @Test
    void versionParsingRejectsFiveSevenAndGarbage() {
        assertFalse(MySql8Requirement.isSupported("5.7.19"));
        assertFalse(MySql8Requirement.isSupported("5.7"));
        assertFalse(MySql8Requirement.isSupported(null));
        assertFalse(MySql8Requirement.isSupported("unknown"));
    }

    @Test
    void mysql8Passes() throws Exception {
        MySql8Requirement gate = gateFor("MySQL", "8.0.26");

        assertDoesNotThrow(gate::check);
    }

    @Test
    void mysql57FailsWithActionableMessage() throws Exception {
        MySql8Requirement gate = gateFor("MySQL", "5.7.19");

        IllegalStateException error = assertThrows(IllegalStateException.class, gate::check);
        String message = error.getMessage();
        assertTrue(message.contains("MySQL 8.0+"));
        assertTrue(message.contains("5.7.19"));
        assertTrue(message.contains("3307"));
        assertTrue(message.contains("require-mysql8=false"));
    }

    @Test
    void nonMysqlIsSkipped() throws Exception {
        MySql8Requirement gate = gateFor("H2", "2.2.224");

        assertDoesNotThrow(gate::check);
    }

    @Test
    void explicitOptOutDoesNotTouchTheDatabase() {
        DataSource dataSource = mock(DataSource.class);
        MySql8Requirement gate = new MySql8Requirement(dataSource);
        ReflectionTestUtils.setField(gate, "required", false);

        assertDoesNotThrow(() -> gate.run(null));
        verifyNoInteractions(dataSource);
    }

    private MySql8Requirement gateFor(String product, String version) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(meta);
        when(meta.getDatabaseProductName()).thenReturn(product);
        when(meta.getDatabaseProductVersion()).thenReturn(version);
        return new MySql8Requirement(dataSource);
    }
}
