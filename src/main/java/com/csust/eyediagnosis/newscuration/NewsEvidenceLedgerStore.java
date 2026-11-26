package com.csust.eyediagnosis.newscuration;

import com.csust.eyediagnosis.newscuration.NewsEvidenceLedger.Attribute;
import com.csust.eyediagnosis.newscuration.NewsEvidenceLedger.Active;
import com.csust.eyediagnosis.newscuration.NewsEvidenceLedger.Method;
import com.csust.eyediagnosis.newscuration.NewsEvidenceLedger.Overlay;
import com.csust.eyediagnosis.newscuration.NewsEvidenceLedger.Request;
import com.csust.eyediagnosis.newscuration.NewsEvidenceLedger.Verification;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * JDBC store for the WP-08B.2 evidence verification ledger.
 *
 * <p>Rows are append-only. Revocation stamps the same row, and the generated {@code activeKey} column
 * enforces "one active verification per attribute" at the database level, so two reviewers racing on the
 * same attribute cannot both win.</p>
 */
@Component
public class NewsEvidenceLedgerStore {

    private final JdbcTemplate jdbc;

    public NewsEvidenceLedgerStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Verification> list(long materialRevisionId) {
        return jdbc.query("""
                        SELECT id, materialRevisionId, attribute, value, method, evidenceUrl, sourceVersion,
                               evidenceNote, verifiedBy, verifiedAt, revokedBy, revokedAt, revokeNote
                        FROM t_news_evidence_verification
                        WHERE materialRevisionId = ?
                        ORDER BY verifiedAt, id
                        """,
                (rs, rowNum) -> map(rs), materialRevisionId);
    }

    /** Active overlay used when preparing a model call or replaying a frozen one. */
    public Overlay overlay(long materialRevisionId) {
        List<Active> active = jdbc.query("""
                        SELECT attribute, value, method, evidenceUrl, sourceVersion, verifiedBy, verifiedAt
                        FROM t_news_evidence_verification
                        WHERE materialRevisionId = ? AND revokedAt IS NULL
                        ORDER BY attribute
                        """,
                (rs, rowNum) -> new Active(Attribute.valueOf(rs.getString("attribute")),
                        rs.getString("value"), Method.valueOf(rs.getString("method")),
                        rs.getString("evidenceUrl"), rs.getString("sourceVersion"),
                        rs.getString("verifiedBy"), toInstant(rs, "verifiedAt")),
                materialRevisionId);
        return Overlay.of(materialRevisionId, active);
    }

    /** Rebuilds the overlay frozen into a model call identity; replay never re-reads mutable state. */
    public static Overlay frozen(long materialRevisionId, List<Active> attributes) {
        return Overlay.of(materialRevisionId, attributes == null ? List.of() : attributes);
    }

    public boolean materialRevisionExists(long materialRevisionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_news_material_revision WHERE id = ?", Integer.class, materialRevisionId);
        return Integer.valueOf(1).equals(count);
    }

    /** Appends one reviewer verification; returns the stored row. */
    public Verification append(long materialRevisionId, Request request, String reviewer) {
        try {
            jdbc.update("""
                            INSERT INTO t_news_evidence_verification
                                (materialRevisionId, attribute, value, method, evidenceUrl, sourceVersion,
                                 evidenceNote, verifiedBy, verifiedAt)
                            VALUES (?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6))
                            """,
                    materialRevisionId, request.attribute().name(), request.value(), request.method().name(),
                    request.evidenceUrl(), request.sourceVersion(), request.evidenceNote(), reviewer);
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("EVIDENCE_ALREADY_ACTIVE", duplicate);
        }
        return jdbc.queryForObject("""
                        SELECT id, materialRevisionId, attribute, value, method, evidenceUrl, sourceVersion,
                               evidenceNote, verifiedBy, verifiedAt, revokedBy, revokedAt, revokeNote
                        FROM t_news_evidence_verification
                        WHERE materialRevisionId = ? AND attribute = ? AND revokedAt IS NULL
                        """,
                (rs, rowNum) -> map(rs), materialRevisionId, request.attribute().name());
    }

    /** Revokes one active verification. The row is retained; only the revocation columns change. */
    public Verification revoke(long id, String reviewer, String note) {
        String trimmed = note == null ? null : note.trim();
        if (trimmed != null && (trimmed.length() > 512 || trimmed.contains("<"))) {
            throw new IllegalArgumentException("EVIDENCE_NOTE_INVALID");
        }
        int updated = jdbc.update("""
                        UPDATE t_news_evidence_verification
                        SET revokedBy = ?, revokedAt = UTC_TIMESTAMP(6), revokeNote = ?
                        WHERE id = ? AND revokedAt IS NULL
                        """,
                reviewer, trimmed, id);
        if (updated != 1) {
            throw new IllegalStateException("EVIDENCE_NOT_ACTIVE");
        }
        return jdbc.queryForObject("""
                        SELECT id, materialRevisionId, attribute, value, method, evidenceUrl, sourceVersion,
                               evidenceNote, verifiedBy, verifiedAt, revokedBy, revokedAt, revokeNote
                        FROM t_news_evidence_verification WHERE id = ?
                        """,
                (rs, rowNum) -> map(rs), id);
    }

    private static Verification map(ResultSet rs) throws SQLException {
        return new Verification(rs.getLong("id"), rs.getLong("materialRevisionId"),
                Attribute.valueOf(rs.getString("attribute")), rs.getString("value"),
                Method.valueOf(rs.getString("method")), rs.getString("evidenceUrl"),
                rs.getString("sourceVersion"), rs.getString("evidenceNote"),
                rs.getString("verifiedBy"), toInstant(rs, "verifiedAt"),
                rs.getString("revokedBy"), toInstant(rs, "revokedAt"), rs.getString("revokeNote"));
    }

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
