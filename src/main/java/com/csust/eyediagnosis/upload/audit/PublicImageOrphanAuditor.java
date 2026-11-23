package com.csust.eyediagnosis.upload.audit;

import com.csust.eyediagnosis.manager.CosManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code public/} 前缀的孤儿对象对账：判读原图与用户头像写在这里，但没有任何任务表
 * 登记它们（{@code agent-chat/} 有 {@code t_upload_task}，这一条没有）。
 *
 * <p>因此这里的做法与 agent-chat 的补偿<b>故意不同</b>：<b>只报告，不删除</b>。
 * 同一个前缀下混着两类对象，而「引用」的判定要回查 {@code t_diagnosis.url} 与
 * {@code t_user.userAvatar}——判错一次就是删掉一张真实的眼底原图，不可恢复。
 * 把这个判定变成一条可核对的记录，由人决定怎么处置，比让定时任务替人承担删除风险更诚实。
 *
 * <p>引用值在库里既可能是对象 key（{@code public/7/xxx.png}），也可能是带签名参数的
 * 完整 URL；这里统一归一化成 key 再比对（去 scheme/host、去查询串、URL 解码）。
 * 归一化认不出来的一律按「已引用」处理：宁可少报一个孤儿，不可多报一个正在用的对象。
 *
 * <p>日志口径：默认只报<b>条数</b>。对象 key 里嵌着用户名下的原始文件名，属于患者侧内容，
 * 不进常规日志；需要具体清单时把本类的日志级别临时开到 DEBUG。
 */
@Slf4j
@Component
public class PublicImageOrphanAuditor {

    /** 判读原图与头像在 COS 上的前缀。 */
    public static final String PUBLIC_PREFIX = "public/";

    private final CosManager cosManager;
    private final JdbcTemplate jdbc;

    /**
     * 宽限期（分钟，默认 24 小时）：最近写入的对象不参与判定。
     * 上传流程是「先写 COS、再落库」，宽限期用来避开这段窗口。
     */
    @Value("${eye-diagnosis.upload.orphan-audit.min-age-minutes:1440}")
    private long minAgeMinutes;

    public PublicImageOrphanAuditor(CosManager cosManager, JdbcTemplate jdbc) {
        this.cosManager = cosManager;
        this.jdbc = jdbc;
    }

    /**
     * 审计一次。
     *
     * @return 扫描数、判定为无引用的对象数与样例 key（最多 10 个）
     */
    public AuditReport audit() {
        List<CosManager.CosObjectInfo> objects = cosManager.listObjects(PUBLIC_PREFIX);
        if (objects.isEmpty()) {
            return new AuditReport(0, 0, List.of());
        }
        Set<String> referenced = loadReferencedKeys();
        Date cutoff = Date.from(Instant.now().minus(Duration.ofMinutes(minAgeMinutes)));
        int scanned = 0;
        List<String> unreferenced = new ArrayList<>();
        for (CosManager.CosObjectInfo object : objects) {
            if (object.lastModified() != null && object.lastModified().after(cutoff)) {
                continue;
            }
            scanned++;
            if (!referenced.contains(object.key())) {
                unreferenced.add(object.key());
            }
        }
        List<String> samples = unreferenced.stream().limit(10).toList();
        if (unreferenced.isEmpty()) {
            log.debug("判读影像孤儿对象审计完成：扫描 {} 个对象，均有数据库记录引用", scanned);
        } else {
            // key 里带着原始文件名，默认只报条数；要看清单请临时开 DEBUG。
            log.warn("判读影像孤儿对象审计：扫描 {} 个对象，{} 个没有任何记录引用"
                    + "（只报告不删除，请人工核对后清理；明细见本类 DEBUG 日志）",
                    scanned, unreferenced.size());
            if (log.isDebugEnabled()) {
                log.debug("判读影像孤儿对象明细：{}", samples);
            }
        }
        return new AuditReport(scanned, unreferenced.size(), samples);
    }

    /**
     * 两处可能引用 {@code public/} 对象的值，归一化成对象 key。
     *
     * <p>分开查询、在内存里合并，不写 {@code UNION}：两张表的列只有一列是同名同义，
     * 而 {@code t_user.userAvatar} 在 V15 里显式声明了 collation、其它列继承库默认——
     * 一旦部署库的默认 collation 不同，UNION 会直接报 1267 非法混合排序规则。
     *
     * <p>不按 {@code isDelete} 过滤：软删除的行仍可能是有意保留的记录，
     * 把它们算作「有引用」只会少报孤儿，不会误删。
     */
    private Set<String> loadReferencedKeys() {
        Set<String> keys = new HashSet<>();
        collect(queryStrings("SELECT url FROM t_diagnosis WHERE url IS NOT NULL"), keys);
        collect(queryStrings("SELECT userAvatar FROM t_user WHERE userAvatar IS NOT NULL"), keys);
        return keys;
    }

    private List<String> queryStrings(String sql) {
        return jdbc.queryForList(sql, String.class);
    }

    private static void collect(List<String> values, Set<String> keys) {
        for (String value : values) {
            String key = toObjectKey(value);
            if (key != null) {
                keys.add(key);
            }
        }
    }

    /** URL / key → 对象 key；认不出来时返回 {@code null}（调用方按「已引用」处理）。 */
    static String toObjectKey(String value) {
        if (value == null) {
            return null;
        }
        String key = value.trim();
        if (key.isEmpty()) {
            return null;
        }
        int scheme = key.indexOf("://");
        if (scheme >= 0) {
            int pathStart = key.indexOf('/', scheme + 3);
            key = pathStart < 0 ? "" : key.substring(pathStart);
        }
        int query = key.indexOf('?');
        if (query >= 0) {
            key = key.substring(0, query);
        }
        while (key.startsWith("/")) {
            key = key.substring(1);
        }
        try {
            key = URLDecoder.decode(key, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return key.isEmpty() ? null : key;
    }

    /**
     * 一次审计的结果。
     *
     * @param scanned      参与判定的对象数（已排除宽限期内的对象）
     * @param unreferenced 判定为「没有任何记录引用」的对象数
     * @param samples      样例 key，便于人工核对
     */
    public record AuditReport(int scanned, int unreferenced, List<String> samples) {
    }
}
