package com.csust.eyediagnosis.newsworkbench;

import com.csust.eyediagnosis.constant.NewsStatus;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class NewsIssueService {

    private static final int MAX_ARTICLES = 500;
    private static final Pattern DOI_PREFIX = Pattern.compile(
            "^(?:https?://(?:dx\\.)?doi\\.org/|doi:\\s*)");
    private static final Pattern DOI = Pattern.compile("10\\.[0-9]{4,}/[^\\s]+");
    private static final Pattern PMID = Pattern.compile("[1-9][0-9]*");

    private final JdbcTemplate jdbc;

    public Preview preview(LocalDate startOn, LocalDate endOn) {
        if (startOn == null || endOn == null || endOn.isBefore(startOn)
                || startOn.getYear() < 1000 || endOn.getYear() > 9999
                || endOn.equals(LocalDate.of(9999, 12, 31))
                || ChronoUnit.DAYS.between(startOn, endOn) >= 31) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请选择最多 31 天的刊物日期范围");
        }

        List<Article> articles = jdbc.query("""
                SELECT n.id, n.publishTime, r.revisionNo, r.title, r.summary, m.id AS materialRevisionId,
                       m.source, m.sourceRecordId, m.pmid, m.doi
                FROM t_news n
                JOIN t_news_revision r ON r.newsId = n.id AND r.revisionNo = n.publishedRevisionNo
                JOIN t_news_material_revision m ON m.id = r.materialRevisionId AND m.visibleAt IS NOT NULL
                WHERE n.isDelete = 0 AND n.status = ? AND r.status = ?
                  AND r.evidenceSnapshotSha256 IS NOT NULL
                  AND n.publishTime >= ? AND n.publishTime < ?
                ORDER BY n.publishTime DESC, n.id DESC
                LIMIT ?
                """, (rs, row) -> new Article(
                rs.getLong("id"), rs.getInt("revisionNo"), rs.getString("title"), rs.getString("summary"),
                rs.getObject("publishTime", LocalDateTime.class), rs.getLong("materialRevisionId"),
                rs.getString("source"), rs.getString("sourceRecordId"),
                normalizePmid(rs.getString("pmid")), normalizeDoi(rs.getString("doi"))),
                NewsStatus.PUBLIC_VISIBLE, NewsStatus.PUBLISHED,
                startOn.atStartOfDay(), endOn.plusDays(1).atStartOfDay(), MAX_ARTICLES + 1);
        if (articles.size() > MAX_ARTICLES) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该窗口超过 500 篇资讯，请缩小日期范围");
        }

        List<Entry> entries = group(articles);
        return new Preview(startOn, endOn, articles.size(), entries);
    }

    private static List<Entry> group(List<Article> articles) {
        int[] roots = new int[articles.size()];
        Map<String, Integer> identifiers = new HashMap<>();
        for (int i = 0; i < articles.size(); i++) {
            roots[i] = i;
            Article article = articles.get(i);
            connect(roots, identifiers, i, "pmid:", article.pmid());
            connect(roots, identifiers, i, "doi:", article.doi());
        }
        Map<Integer, List<Article>> components = new LinkedHashMap<>();
        for (int i = 0; i < articles.size(); i++) {
            components.computeIfAbsent(root(roots, i), ignored -> new ArrayList<>()).add(articles.get(i));
        }

        Map<Long, Entry> entries = new HashMap<>();
        for (List<Article> component : components.values()) {
            boolean conflict = component.stream().map(Article::pmid).filter(Objects::nonNull)
                    .distinct().limit(2).count() > 1
                    || component.stream().map(Article::doi).filter(Objects::nonNull)
                    .distinct().limit(2).count() > 1;
            if (conflict) {
                component.forEach(article -> entries.put(article.newsId(), new Entry(true, List.of(article))));
            } else {
                entries.put(component.getFirst().newsId(), new Entry(false, List.copyOf(component)));
            }
        }
        return articles.stream().filter(article -> entries.containsKey(article.newsId()))
                .map(article -> entries.get(article.newsId())).toList();
    }

    private static void connect(int[] roots, Map<String, Integer> identifiers,
                                int index, String kind, String value) {
        if (value != null) {
            Integer previous = identifiers.putIfAbsent(kind + value, index);
            if (previous != null) {
                roots[root(roots, index)] = root(roots, previous);
            }
        }
    }

    private static int root(int[] roots, int index) {
        while (roots[index] != index) {
            index = roots[index];
        }
        return index;
    }

    private static String normalizePmid(String raw) {
        String value = raw == null ? "" : raw.strip();
        return PMID.matcher(value).matches() ? value : null;
    }

    private static String normalizeDoi(String raw) {
        String value = raw == null ? "" : DOI_PREFIX.matcher(raw.strip().toLowerCase(Locale.ROOT)).replaceFirst("");
        return DOI.matcher(value).matches() ? value : null;
    }

    public record Preview(LocalDate startOn, LocalDate endOn, int articleCount, List<Entry> entries) {}

    public record Entry(boolean identifierConflict, List<Article> articles) {}

    public record Article(long newsId, int revisionNo, String title, String summary,
                          LocalDateTime publishTime, long materialRevisionId, String source,
                          String sourceRecordId, String pmid, String doi) {}
}
