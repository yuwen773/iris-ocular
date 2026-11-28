package com.csust.eyediagnosis.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PostgreSQL 门禁的行为：18+ 且装了 pgvector 才放行；MySQL、低版本、缺扩展都拒绝启动；
 * 显式关闭时不连库。
 */
class PostgreSqlRequirementTest {

    @Test
    void versionParsingAcceptsEighteenAndAbove() {
        assertTrue(PostgreSqlRequirement.isSupported("18.0", 18));
        assertTrue(PostgreSqlRequirement.isSupported("18.4", 18));
        assertTrue(PostgreSqlRequirement.isSupported("19.1 (Debian 19.1-1)", 18));
        assertTrue(PostgreSqlRequirement.isSupported("18", 18));
    }

    @Test
    void versionParsingRejectsSeventeenAndGarbage() {
        assertFalse(PostgreSqlRequirement.isSupported("17.6", 18));
        assertFalse(PostgreSqlRequirement.isSupported("16.4 (Debian)", 18));
        assertFalse(PostgreSqlRequirement.isSupported(null, 18));
        assertFalse(PostgreSqlRequirement.isSupported("unknown", 18));
    }

    @Test
    void postgresWithPgvectorPasses() throws Exception {
        PostgreSqlRequirement gate = gateFor("PostgreSQL", "18.4", "0.8.1");

        assertDoesNotThrow(gate::check);
    }

    @Test
    void mysqlIsRejectedWithActionableMessage() throws Exception {
        PostgreSqlRequirement gate = gateFor("MySQL", "8.0.46", null);

        IllegalStateException error = assertThrows(IllegalStateException.class, gate::check);
        assertTrue(error.getMessage().contains("PostgreSQL 18+"));
        assertTrue(error.getMessage().contains("MySQL"));
        assertTrue(error.getMessage().contains("require-postgresql=false"));
    }

    @Test
    void oldPostgresIsRejected() throws Exception {
        PostgreSqlRequirement gate = gateFor("PostgreSQL", "17.6", "0.8.0");

        IllegalStateException error = assertThrows(IllegalStateException.class, gate::check);
        assertTrue(error.getMessage().contains("17.6"));
        assertTrue(error.getMessage().contains("18+"));
    }

    @Test
    void missingVectorExtensionIsRejected() throws Exception {
        PostgreSqlRequirement gate = gateFor("PostgreSQL", "18.4", null);

        IllegalStateException error = assertThrows(IllegalStateException.class, gate::check);
        assertTrue(error.getMessage().contains("pgvector"));
        assertTrue(error.getMessage().contains("bootstrap-roles.sql"));
    }

    @Test
    void explicitOptOutDoesNotTouchTheDatabase() {
        DataSource dataSource = mock(DataSource.class);
        PostgreSqlRequirement gate = new PostgreSqlRequirement(dataSource);
        ReflectionTestUtils.setField(gate, "required", false);

        assertDoesNotThrow(() -> gate.run(null));
        verifyNoInteractions(dataSource);
    }

    @Test
    void missingStringtypeIsRejectedBecauseJsonbWritesWouldFailAtRuntime() throws Exception {
        PostgreSqlRequirement gate = gateFor("PostgreSQL", "18.4", "0.8.7",
                "jdbc:postgresql://127.0.0.1:5432/iris?currentSchema=diagnosis");

        IllegalStateException error = assertThrows(IllegalStateException.class, gate::check);
        assertTrue(error.getMessage().contains("stringtype=unspecified"));
    }

    @Test
    void stringtypeInTheUrlPasses() throws Exception {
        PostgreSqlRequirement gate = gateFor("PostgreSQL", "18.4", "0.8.7",
                "jdbc:postgresql://127.0.0.1:5432/iris?currentSchema=diagnosis&stringtype=unspecified");

        assertDoesNotThrow(gate::check);
    }

    private PostgreSqlRequirement gateFor(String product, String version, String vectorVersion) throws Exception {
        return gateFor(product, version, vectorVersion, null);
    }

    private PostgreSqlRequirement gateFor(String product, String version, String vectorVersion, String url) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        Statement statement = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(meta);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(vectorVersion != null);
        when(rs.getString(1)).thenReturn(vectorVersion);
        when(meta.getDatabaseProductName()).thenReturn(product);
        when(meta.getDatabaseProductVersion()).thenReturn(version);
        when(meta.getURL()).thenReturn(url);
        return new PostgreSqlRequirement(dataSource);
    }
}
