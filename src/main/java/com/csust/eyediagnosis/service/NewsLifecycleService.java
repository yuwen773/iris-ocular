package com.csust.eyediagnosis.service;

import cn.hutool.http.HtmlUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.csust.eyediagnosis.common.BatchResultVO;
import com.csust.eyediagnosis.config.JsonConfig;
import com.csust.eyediagnosis.constant.NewsStatus;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.exception.ThrowUtils;
import com.csust.eyediagnosis.mapper.NewsAuditMapper;
import com.csust.eyediagnosis.mapper.NewsMapper;
import com.csust.eyediagnosis.model.entity.News;
import com.csust.eyediagnosis.model.entity.NewsAudit;
import com.csust.eyediagnosis.model.entity.User;
import com.csust.eyediagnosis.model.vo.news.NewsRevisionVO;
import com.csust.eyediagnosis.newscuration.NewsCurationModels;
import com.csust.eyediagnosis.newscuration.NewsEvidenceVerifier;
import com.csust.eyediagnosis.newsingestion.NewsMaterialCanonicalizer;
import com.csust.eyediagnosis.utils.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 新闻发布生命周期的写入 module：证据绑定、服务层发布门禁、不可变内容修订、审计、
 * 软删与提交后缓存纪元推进都在事务控制下协同完成（V17 + WP-04）。
 */
@Service
public class NewsLifecycleService {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<NewsCurationModels.CurationClaim>> CLAIM_LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<NewsCurationModels.EvidenceViolation>> VIOLATION_LIST_TYPE = new TypeReference<>() {};
    private static final Pattern HTML_BODY_BREAK = Pattern.compile(
            "(?i)<(?:br\\b[^<>]*|/(?:p|div|h[1-6]|li|blockquote|pre|td|th|tr)\\s*)>");

    private static final Map<Integer, Set<Integer>> ALLOWED = Map.of(
            NewsStatus.DRAFT, Set.of(NewsStatus.PENDING_REVIEW, NewsStatus.PUBLISHED),
            NewsStatus.PENDING_REVIEW, Set.of(
                    NewsStatus.PUBLISHED, NewsStatus.DRAFT, NewsStatus.OFFLINE),
            NewsStatus.PUBLISHED, Set.of(NewsStatus.OFFLINE, NewsStatus.DRAFT),
            NewsStatus.OFFLINE, Set.of(
                    NewsStatus.PUBLISHED, NewsStatus.DRAFT, NewsStatus.PENDING_REVIEW)
    );

    private final NewsMapper newsMapper;
    private final NewsAuditMapper newsAuditMapper;
    private final JdbcTemplate jdbc;
    private final NewsListCacheService newsListCacheService;
    private final ObjectMapper protocolMapper;

    public NewsLifecycleService(NewsMapper newsMapper, NewsAuditMapper newsAuditMapper) {
        this(newsMapper, newsAuditMapper, null, null, JsonConfig.createNewsProtocolObjectMapper());
    }

    @Autowired
    public NewsLifecycleService(
            NewsMapper newsMapper,
            NewsAuditMapper newsAuditMapper,
            @Autowired(required = false) JdbcTemplate jdbc,
            @Autowired(required = false) NewsListCacheService newsListCacheService,
            @Autowired(required = false) @Qualifier("newsProtocolObjectMapper") ObjectMapper protocolMapper) {
        this.newsMapper = newsMapper;
        this.newsAuditMapper = newsAuditMapper;
        this.jdbc = jdbc;
        this.newsListCacheService = newsListCacheService;
        this.protocolMapper = protocolMapper != null
                ? protocolMapper
                : JsonConfig.createNewsProtocolObjectMapper();
    }

    /**
     * WP-04：从已通过 WP-03 证据校验证明可进入编辑环节的精选候选生成草稿/待审核资讯，
     * 不可变绑定候选 id、可见原始资料修订 id 与冻结证据快照 SHA-256。严禁跳过人工草稿/审核直接发布。
     */
    @Transactional
    public Long createDraftFromCandidate(long candidateId, User actor, boolean submitForReview) {
        ThrowUtils.throwIf(jdbc == null, ErrorCode.OPERATION_ERROR, "未配置数据源，无法从精选候选生成资讯");
        ThrowUtils.throwIf(actor == null || actor.getId() == null, ErrorCode.NO_AUTH_ERROR);

        CandidateRow candidate = lockAndVerifyCandidate(candidateId, false);
        ensureNoActiveNewsForCandidate(candidateId);

        int initialStatus = submitForReview ? NewsStatus.PENDING_REVIEW : NewsStatus.DRAFT;
        String title = resolveCandidateTitle(candidate);
        String content = buildCandidateEditorialContent(candidate);
        String summary = resolveCandidateSummary(candidate, content);
        String category = truncate(candidate.category, 32);
        String sourceLabel = truncate(candidate.source + ":" + candidate.sourceRecordId, 255);

        validateClinicalAndInjectionSafety(title, summary, content);

        Date now = new Date();
        News news = new News();
        news.setTitle(title);
        news.setContent(content);
        news.setSummary(summary);
        news.setCoverImg("");
        news.setCategory(category);
        news.setSource(sourceLabel);
        news.setFeatured(0);
        news.setStatus(initialStatus);
        news.setUserId(actor.getId());
        if (submitForReview) {
            news.setSubmittedById(actor.getId());
            news.setSubmittedAt(now);
        }
        news.setCurationCandidateId(candidate.id);
        news.setMaterialRevisionId(candidate.materialRevisionId);
        news.setEvidenceSnapshotSha256(candidate.evidenceSnapshotSha256);
        news.setCurrentRevisionNo(1);
        news.setPublishedRevisionNo(null);
        news.setPublishTime(now);
        news.setEditTime(now);

        int rows = newsMapper.insert(news);
        ThrowUtils.throwIf(rows != 1 || news.getId() == null, ErrorCode.OPERATION_ERROR);

        insertRevisionRow(
                news.getId(), 1, "CREATE_FROM_CANDIDATE", initialStatus,
                title, summary, content, "", category, sourceLabel, 0,
                candidate.id, candidate.materialRevisionId, candidate.evidenceSnapshotSha256,
                candidate.evidenceSnapshotJson, actor, null);

        List<String> auditPayload = List.of(
                "title", "content", "summary", "status",
                "curationCandidateId", "materialRevisionId", "evidenceSnapshotSha256", "revisionNo");
        audit(news.getId(), actor, "CREATE_FROM_CANDIDATE", null, initialStatus, auditPayload);
        bumpCacheEpochIfConfigured();
        return news.getId();
    }

    /**
     * 编辑通道：校验状态迁移、服务层发布门禁、落审核人/时间、更新、写不可变内容修订、写审计。
     * id 为空时创建草稿或手动发布文章；返还落库后的新闻 id。
     */
    @Transactional
    public Long applyEdit(News patch, User actor, String note) {
        ThrowUtils.throwIf(patch == null, ErrorCode.PARAMS_ERROR);
        if (patch.getId() == null) {
            return create(patch, actor, note);
        }
        News current = selectCurrentState(patch.getId());
        if (current == null) {
            return null;
        }
        rejectEvidenceBindingMutation(patch, current);

        Integer from = current.getStatus();
        Integer to = patch.getStatus();
        if (to != null) {
            ThrowUtils.throwIf(!isAllowed(from, to),
                    ErrorCode.PARAMS_ERROR,
                    "非法状态迁移: " + NewsStatus.label(from) + " → " + NewsStatus.label(to));
            stampReview(patch, from, to, actor, note);
        }

        int effectiveStatus = to != null ? to : (from == null ? NewsStatus.DRAFT : from);
        String effectiveTitle = firstNonNull(patch.getTitle(), current.getTitle());
        String effectiveSummary = firstNonNull(patch.getSummary(), current.getSummary());
        String effectiveContent = firstNonNull(patch.getContent(), current.getContent());
        String effectiveCoverImg = firstNonNull(patch.getCoverImg(), current.getCoverImg());
        String effectiveCategory = firstNonNull(patch.getCategory(), current.getCategory());
        String effectiveSource = firstNonNull(patch.getSource(), current.getSource());
        int effectiveFeatured = patch.getFeatured() != null
                ? patch.getFeatured()
                : (current.getFeatured() != null ? current.getFeatured() : 0);

        CandidateRow boundCandidate = enforcePublishGateOnEdit(
                current, from, to, effectiveStatus, effectiveTitle, effectiveSummary, effectiveContent,
                note, actor);

        String action = to == null ? "EDIT" : actionFor(from, to);
        Integer nextRevisionNo = null;
        if (jdbc != null) {
            int baseRev = current.getCurrentRevisionNo() == null ? 0 : current.getCurrentRevisionNo();
            nextRevisionNo = baseRev + 1;
            patch.setCurrentRevisionNo(nextRevisionNo);
            if (effectiveStatus == NewsStatus.PUBLISHED) {
                patch.setPublishedRevisionNo(nextRevisionNo);
            }
        }

        int rows = newsMapper.updateById(patch);
        ThrowUtils.throwIf(rows <= 0 && jdbc != null, ErrorCode.OPERATION_ERROR);
        if (rows <= 0) {
            return null;
        }

        if (jdbc != null) {
            String snapshotJson = boundCandidate != null
                    ? boundCandidate.evidenceSnapshotJson
                    : loadExistingSnapshotJson(patch.getId(), current.getCurationCandidateId());
            insertRevisionRow(
                    patch.getId(), nextRevisionNo, action, effectiveStatus,
                    nonNullString(effectiveTitle), effectiveSummary, nonNullString(effectiveContent),
                    nonNullString(effectiveCoverImg), effectiveCategory, effectiveSource, effectiveFeatured,
                    current.getCurationCandidateId(), current.getMaterialRevisionId(),
                    current.getEvidenceSnapshotSha256(), snapshotJson, actor, note);
        }

        audit(patch.getId(), actor, action, from, to == null ? from : to, changedFields(patch, note));
        bumpCacheEpochIfConfigured();
        return patch.getId();
    }

    private Long create(News patch, User actor, String note) {
        Integer to = patch.getStatus();
        ThrowUtils.throwIf(to != null && !NewsStatus.isValid(to), ErrorCode.PARAMS_ERROR,
                "非法初始状态: " + NewsStatus.label(to));
        int initialStatus = to == null ? NewsStatus.DRAFT : to;

        boolean hasCandidateId = patch.getCurationCandidateId() != null;
        boolean hasRawBinding = patch.getMaterialRevisionId() != null || patch.getEvidenceSnapshotSha256() != null;
        ThrowUtils.throwIf(!hasCandidateId && hasRawBinding, ErrorCode.PARAMS_ERROR,
                "未绑定精选候选时不允许直接写入证据快照");

        CandidateRow verifiedCandidate = null;
        if (hasCandidateId) {
            ThrowUtils.throwIf(jdbc == null, ErrorCode.OPERATION_ERROR, "未配置数据源，无法绑定精选候选");
            ThrowUtils.throwIf(initialStatus == NewsStatus.PUBLISHED, ErrorCode.PARAMS_ERROR,
                    "精选候选资讯需先建草稿或提交审核后再发布");
            verifiedCandidate = lockAndVerifyCandidate(patch.getCurationCandidateId(), false);
            ensureNoActiveNewsForCandidate(verifiedCandidate.id);
            if (patch.getMaterialRevisionId() != null
                    && !Objects.equals(patch.getMaterialRevisionId(), verifiedCandidate.materialRevisionId)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "绑定的原始资料修订与精选候选不一致");
            }
            if (patch.getEvidenceSnapshotSha256() != null
                    && !Objects.equals(patch.getEvidenceSnapshotSha256(), verifiedCandidate.evidenceSnapshotSha256)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "证据快照摘要与精选候选不一致");
            }
            patch.setMaterialRevisionId(verifiedCandidate.materialRevisionId);
            patch.setEvidenceSnapshotSha256(verifiedCandidate.evidenceSnapshotSha256);
        }

        if (initialStatus == NewsStatus.PUBLISHED) {
            ThrowUtils.throwIf(isBlank(patch.getTitle()) || isBlank(patch.getContent()),
                    ErrorCode.PARAMS_ERROR, "直接发布资讯必须提供非空标题与正文");
            validateClinicalAndInjectionSafety(patch.getTitle(), patch.getSummary(), patch.getContent());
        }

        Date now = new Date();
        // 允许人工新建时直接发布：编辑页的「发布」按钮对人工文章送 status=1；精选候选已在上文拦截。
        patch.setStatus(initialStatus);
        patch.setUserId(actorId(actor));
        if (initialStatus == NewsStatus.PENDING_REVIEW) {
            patch.setSubmittedById(actorId(actor));
            patch.setSubmittedAt(now);
        } else if (initialStatus == NewsStatus.PUBLISHED) {
            patch.setReviewedById(actorId(actor));
            patch.setReviewedAt(now);
        }
        // V15 的 t_news 对 content / coverImg / publishTime 有 NOT NULL 约束。
        patch.setContent(patch.getContent() == null ? "" : patch.getContent());
        patch.setCoverImg(patch.getCoverImg() == null ? "" : patch.getCoverImg());
        patch.setTitle(patch.getTitle() == null ? "" : patch.getTitle());
        patch.setPublishTime(now);
        patch.setEditTime(now);
        if (jdbc != null) {
            patch.setCurrentRevisionNo(1);
            patch.setPublishedRevisionNo(initialStatus == NewsStatus.PUBLISHED ? 1 : null);
        }

        int rows = newsMapper.insert(patch);
        ThrowUtils.throwIf(rows != 1, ErrorCode.OPERATION_ERROR);

        if (jdbc != null) {
            insertRevisionRow(
                    patch.getId(), 1, "CREATE", initialStatus,
                    patch.getTitle(), patch.getSummary(), patch.getContent(), patch.getCoverImg(),
                    patch.getCategory(), patch.getSource(),
                    patch.getFeatured() == null ? 0 : patch.getFeatured(),
                    patch.getCurationCandidateId(), patch.getMaterialRevisionId(),
                    patch.getEvidenceSnapshotSha256(),
                    verifiedCandidate == null ? null : verifiedCandidate.evidenceSnapshotJson,
                    actor, note);
        }

        audit(patch.getId(), actor, "CREATE", null, patch.getStatus(),
                changedFields(patch, note));
        bumpCacheEpochIfConfigured();
        return patch.getId();
    }

    /**
     * 封面更新（供 NewsServiceImpl.uploadCoverImage 调用）：锁行更新 coverImg、写入新修订并推进缓存纪元。
     */
    @Transactional
    public void recordCoverUpdate(Long newsId, String coverUrl, Date editTime, User actor) {
        ThrowUtils.throwIf(newsId == null || newsId <= 0 || isBlank(coverUrl), ErrorCode.PARAMS_ERROR);
        News current = selectCurrentState(newsId);
        ThrowUtils.throwIf(current == null, ErrorCode.NOT_FOUND_ERROR);
        if (Integer.valueOf(NewsStatus.PUBLISHED).equals(current.getStatus())) {
            enforcePublishGateOnEdit(current, current.getStatus(), null, NewsStatus.PUBLISHED,
                    current.getTitle(), current.getSummary(), current.getContent(), null, actor);
        }

        News patch = new News();
        patch.setId(newsId);
        patch.setCoverImg(coverUrl);
        patch.setEditTime(editTime != null ? editTime : new Date());

        Integer nextRev = null;
        if (jdbc != null) {
            int baseRev = current.getCurrentRevisionNo() == null ? 0 : current.getCurrentRevisionNo();
            nextRev = baseRev + 1;
            patch.setCurrentRevisionNo(nextRev);
            if (Integer.valueOf(NewsStatus.PUBLISHED).equals(current.getStatus())) {
                patch.setPublishedRevisionNo(nextRev);
            }
        }

        int rows = newsMapper.updateById(patch);
        ThrowUtils.throwIf(rows != 1, ErrorCode.OPERATION_ERROR);

        if (jdbc != null) {
            String snapshotJson = loadExistingSnapshotJson(newsId, current.getCurationCandidateId());
            insertRevisionRow(
                    newsId, nextRev, "COVER_UPDATE",
                    current.getStatus() == null ? NewsStatus.DRAFT : current.getStatus(),
                    nonNullString(current.getTitle()), current.getSummary(), nonNullString(current.getContent()),
                    coverUrl, current.getCategory(), current.getSource(),
                    current.getFeatured() == null ? 0 : current.getFeatured(),
                    current.getCurationCandidateId(), current.getMaterialRevisionId(),
                    current.getEvidenceSnapshotSha256(), snapshotJson, actor, null);
        }

        audit(newsId, actor, "EDIT", current.getStatus(), current.getStatus(), List.of("coverImg"));
        bumpCacheEpochIfConfigured();
    }

    /** 软删除：@TableLogic 把 deleteById 变成 UPDATE isDelete=1，并写修订与提交后缓存纪元。 */
    @Transactional
    public boolean softDelete(Long newsId, User actor) {
        News current = selectCurrentState(newsId);
        ThrowUtils.throwIf(current == null, ErrorCode.NOT_FOUND_ERROR);
        int rows = newsMapper.deleteById(newsId);

        if (rows > 0 && jdbc != null) {
            int nextRev = (current.getCurrentRevisionNo() == null ? 0 : current.getCurrentRevisionNo()) + 1;
            String snapshotJson = loadExistingSnapshotJson(newsId, current.getCurationCandidateId());
            insertRevisionRow(
                    newsId, nextRev, "DELETE",
                    current.getStatus() == null ? NewsStatus.DRAFT : current.getStatus(),
                    nonNullString(current.getTitle()), current.getSummary(), nonNullString(current.getContent()),
                    nonNullString(current.getCoverImg()), current.getCategory(), current.getSource(),
                    current.getFeatured() == null ? 0 : current.getFeatured(),
                    current.getCurationCandidateId(), current.getMaterialRevisionId(),
                    current.getEvidenceSnapshotSha256(), snapshotJson, actor, null);
        }

        audit(newsId, actor, "DELETE", current.getStatus(), null,
                Map.of("deleted", true));
        if (rows > 0) {
            bumpCacheEpochIfConfigured();
        }
        return rows > 0;
    }

    /**
     * 批量软删除：逐条执行，部分失败如实回执；整个批次共用一个事务与提交后单次缓存失效。
     */
    @Transactional
    public BatchResultVO softDeleteBatch(List<Long> newsIds, User actor) {
        List<Long> targets = normalizeIds(newsIds);
        List<String> failedIds = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        int succeeded = 0;
        for (Long id : targets) {
            try {
                softDelete(id, actor);
                succeeded++;
            } catch (BusinessException e) {
                failedIds.add(String.valueOf(id));
                reasons.add(id + "：" + e.getMessage());
            }
        }
        String message = reasons.isEmpty()
                ? "已删除 " + succeeded + " 篇资讯"
                : String.join("；", reasons);
        return new BatchResultVO(targets.size(), succeeded, failedIds, message);
    }

    /**
     * 批量改发布状态：逐条经过服务层状态迁移表与发布门禁校验，非法迁移或未达门禁条目的写入不执行。
     */
    @Transactional
    public BatchResultVO changeStatusBatch(List<Long> newsIds, Integer status, String note, User actor) {
        ThrowUtils.throwIf(!NewsStatus.isValid(status), ErrorCode.PARAMS_ERROR,
                "非法目标状态: " + NewsStatus.label(status));
        List<Long> targets = normalizeIds(newsIds);
        List<String> failedIds = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        int succeeded = 0;
        for (Long id : targets) {
            try {
                News patch = new News();
                patch.setId(id);
                patch.setStatus(status);
                Long applied = applyEdit(patch, actor, note);
                if (applied == null) {
                    failedIds.add(String.valueOf(id));
                    reasons.add(id + "：状态未变更");
                    continue;
                }
                succeeded++;
            } catch (BusinessException e) {
                failedIds.add(String.valueOf(id));
                reasons.add(id + "：" + e.getMessage());
            }
        }
        String message = reasons.isEmpty()
                ? "已更新 " + succeeded + " 篇资讯的状态"
                : String.join("；", reasons);
        return new BatchResultVO(targets.size(), succeeded, failedIds, message);
    }

    /** 查询文章按修订号升序排列的不可变修订历史。 */
    public List<NewsRevisionVO> listRevisions(Long newsId) {
        if (newsId == null || newsId <= 0 || jdbc == null) {
            return List.of();
        }
        return jdbc.query("""
                SELECT id, newsId, revisionNo, action, CAST(status AS SIGNED) AS status, title, summary, content,
                       coverImg, category, source, CAST(featured AS SIGNED) AS featured, curationCandidateId,
                       materialRevisionId, evidenceSnapshotSha256, evidenceSnapshotJson,
                       contentSha256, actorId, actorRole, reviewNote, createdAt
                FROM t_news_revision
                WHERE newsId = ?
                ORDER BY revisionNo ASC
                """, (rs, rowNum) -> mapRevisionRow(rs), newsId);
    }

    private CandidateRow enforcePublishGateOnEdit(News current,
                                                  Integer from,
                                                  Integer to,
                                                  int effectiveStatus,
                                                  String effectiveTitle,
                                                  String effectiveSummary,
                                                  String effectiveContent,
                                                  String note,
                                                  User actor) {
        if (effectiveStatus != NewsStatus.PUBLISHED) {
            return null;
        }
        boolean hasAnyEvidence = current.getCurationCandidateId() != null
                || current.getMaterialRevisionId() != null
                || current.getEvidenceSnapshotSha256() != null;

        CandidateRow boundCandidate = null;
        if (hasAnyEvidence && jdbc != null && current.getCurationCandidateId() != null) {
            boundCandidate = lockAndVerifyCandidate(current.getCurationCandidateId(), true);
            verifyBindingMatchesCandidate(current, boundCandidate);
        } else if (hasAnyEvidence) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "资讯证据绑定字段不完整，禁止发布");
        }

        ThrowUtils.throwIf(effectiveTitle != null && effectiveTitle.isBlank(),
                ErrorCode.PARAMS_ERROR, "已发布资讯标题不能为空");
        ThrowUtils.throwIf(effectiveContent != null && effectiveContent.isBlank(),
                ErrorCode.PARAMS_ERROR, "已发布资讯正文不能为空");
        validateClinicalAndInjectionSafety(effectiveTitle, effectiveSummary, effectiveContent);

        if (!hasAnyEvidence) {
            return null;
        }

        ThrowUtils.throwIf(current.getCurationCandidateId() == null
                        || current.getMaterialRevisionId() == null
                        || isBlank(current.getEvidenceSnapshotSha256()),
                ErrorCode.PARAMS_ERROR, "资讯证据绑定字段不完整，禁止发布");
        ThrowUtils.throwIf(isBlank(effectiveTitle) || isBlank(effectiveContent),
                ErrorCode.PARAMS_ERROR, "证据绑定资讯发布前须具备完整标题与正文");
        ThrowUtils.throwIf(actor == null || actor.getId() == null,
                ErrorCode.NO_AUTH_ERROR, "发布需要已认证的审核操作人");

        if ("CURATED_NEEDS_REVIEW".equals(boundCandidate.status)) {
            boolean editingAlreadyPublished = (from != null && from == NewsStatus.PUBLISHED)
                    && (to == null || to == NewsStatus.PUBLISHED);
            if (editingAlreadyPublished) {
                boolean hasReviewNote = !isBlank(note) || !isBlank(current.getReviewNote());
                ThrowUtils.throwIf(!hasReviewNote, ErrorCode.PARAMS_ERROR,
                        "待复核候选必须填写人工审核意见后方可发布");
            } else {
                boolean cameFromApprovedReviewPath = (from != null && from == NewsStatus.PENDING_REVIEW)
                        || (from != null && from == NewsStatus.OFFLINE && current.getReviewedById() != null);
                ThrowUtils.throwIf(!cameFromApprovedReviewPath || isBlank(note),
                        ErrorCode.PARAMS_ERROR,
                        "待复核候选必须经待审核流转并填写人工审核意见后方可发布");
            }
        }
        return boundCandidate;
    }

    private void rejectEvidenceBindingMutation(News patch, News current) {
        if (patch.getCurationCandidateId() != null
                && !Objects.equals(patch.getCurationCandidateId(), current.getCurationCandidateId())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "已创建资讯不允许修改或追加精选候选绑定");
        }
        if (patch.getMaterialRevisionId() != null
                && !Objects.equals(patch.getMaterialRevisionId(), current.getMaterialRevisionId())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "已创建资讯不允许修改原始资料修订绑定");
        }
        if (patch.getEvidenceSnapshotSha256() != null
                && !Objects.equals(patch.getEvidenceSnapshotSha256(), current.getEvidenceSnapshotSha256())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "已创建资讯不允许修改证据快照摘要");
        }
    }

    private CandidateRow lockAndVerifyCandidate(long candidateId, boolean forPublish) {
        List<CandidateRow> rows = jdbc.query("""
                SELECT id, materialId, materialRevisionId, source, sourceRecordId, contentDigest,
                       policyVersion, callId, status, decision, evidenceTier, relevanceScore,
                       suggestedTitle, suggestedSummary, category, limitationsJson, claimsJson,
                       violationsJson, evidenceSnapshotJson, evidenceSnapshotSha256
                FROM t_news_curation_candidate
                WHERE id = ?
                FOR UPDATE
                """, (rs, rowNum) -> mapCandidateRow(rs), candidateId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "绑定的精选候选不存在: " + candidateId);
        }
        CandidateRow candidate = rows.getFirst();

        if (!"CURATED_ACCEPTED".equals(candidate.status) && !"CURATED_NEEDS_REVIEW".equals(candidate.status)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    (forPublish ? "候选未通过证据门禁或尚待对账，禁止发布: " : "候选状态不允许建稿: ") + candidate.status);
        }
        if (!readJsonList(candidate.violationsJson, VIOLATION_LIST_TYPE).isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "候选包含证据违规记录，禁止使用");
        }
        if (isBlank(candidate.evidenceSnapshotJson) || isBlank(candidate.evidenceSnapshotSha256)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "候选缺少冻结证据快照");
        }
        String computedSha = NewsMaterialCanonicalizer.sha256Hex(
                candidate.evidenceSnapshotJson.getBytes(StandardCharsets.UTF_8));
        if (!computedSha.equals(candidate.evidenceSnapshotSha256)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "冻结证据快照 SHA-256 校验失败，内容可能被篡改");
        }
        verifySnapshotIdentity(candidate);

        Integer matchingRevision = jdbc.queryForObject("""
                SELECT COUNT(*) FROM t_news_material_revision
                WHERE id = ? AND materialId = ? AND source = ? AND sourceRecordId = ? AND contentDigest = ?
                """, Integer.class, candidate.materialRevisionId, candidate.materialId, candidate.source,
                candidate.sourceRecordId, candidate.contentDigest);
        if (!Integer.valueOf(1).equals(matchingRevision)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "绑定的原始资料修订不存在或摘要不匹配");
        }

        if (isBlank(candidate.callId)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "候选缺少有效模型调用回执");
        }
        Integer matchingReceipt = jdbc.queryForObject("""
                SELECT COUNT(*) FROM t_news_model_call_receipt
                WHERE callId = ? AND materialRevisionId = ? AND policyVersion = ? AND state = 'SUCCEEDED'
                """, Integer.class, candidate.callId, candidate.materialRevisionId, candidate.policyVersion);
        if (!Integer.valueOf(1).equals(matchingReceipt)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "模型调用回执未成功或与候选证据不匹配");
        }

        return candidate;
    }

    private void verifySnapshotIdentity(CandidateRow candidate) {
        JsonNode snapshot;
        try {
            snapshot = protocolMapper.readTree(candidate.evidenceSnapshotJson);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "无法读取冻结证据快照");
        }
        if (snapshot == null || !snapshot.isObject()
                || !snapshot.path("materialId").isIntegralNumber()
                || !snapshot.path("materialId").canConvertToLong()
                || snapshot.path("materialId").longValue() != candidate.materialId
                || !snapshot.path("materialRevisionId").isIntegralNumber()
                || !snapshot.path("materialRevisionId").canConvertToLong()
                || snapshot.path("materialRevisionId").longValue() != candidate.materialRevisionId
                || !Objects.equals(snapshot.path("source").textValue(), candidate.source)
                || !Objects.equals(snapshot.path("sourceRecordId").textValue(), candidate.sourceRecordId)
                || !Objects.equals(snapshot.path("contentDigest").textValue(), candidate.contentDigest)
                || !Objects.equals(snapshot.path("evidenceTier").textValue(), candidate.evidenceTier)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "冻结证据快照与候选身份不一致");
        }
    }

    private void verifyBindingMatchesCandidate(News current, CandidateRow candidate) {
        if (!Objects.equals(current.getCurationCandidateId(), candidate.id)
                || !Objects.equals(current.getMaterialRevisionId(), candidate.materialRevisionId)
                || !Objects.equals(current.getEvidenceSnapshotSha256(), candidate.evidenceSnapshotSha256)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "资讯行与候选证据快照绑定不一致，禁止发布");
        }
    }

    private void ensureNoActiveNewsForCandidate(long candidateId) {
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_news WHERE curationCandidateId = ? AND isDelete = 0",
                Integer.class, candidateId);
        ThrowUtils.throwIf(existing != null && existing > 0,
                ErrorCode.PARAMS_ERROR, "该精选候选已关联活跃资讯，请勿重复生成");
    }

    private static void validateClinicalAndInjectionSafety(String title, String summary, String content) {
        String separatedBody = content == null ? null : HTML_BODY_BREAK.matcher(content).replaceAll(" ");
        for (String fieldText : new String[]{title, summary, content,
                normalizeBodyForSafetyCheck(content), normalizeBodyForSafetyCheck(separatedBody)}) {
            NewsEvidenceVerifier.findProhibitedClinicalMarker(fieldText).ifPresent(marker -> {
                throw new BusinessException(ErrorCode.PARAMS_ERROR,
                        "发布内容触及医学安全边界，包含禁用表述: " + marker);
            });
            NewsEvidenceVerifier.findPromptInjectionMarker(fieldText).ifPresent(marker -> {
                throw new BusinessException(ErrorCode.PARAMS_ERROR,
                        "发布内容包含疑似提示词注入指令: " + marker);
            });
        }
    }

    private static String normalizeBodyForSafetyCheck(String content) {
        return content == null ? null : HtmlUtil.unescape(HtmlUtil.cleanHtmlTag(content))
                .replaceAll("[\\[\\]*_`~\\\\]", "")
                .replaceAll("[\\s\\u00A0]+", " ");
    }

    private News selectCurrentState(Long newsId) {
        if (jdbc == null) {
            return newsMapper.selectOne(new QueryWrapper<News>()
                    .select("status")
                    .eq("id", newsId));
        }
        List<News> rows = jdbc.query("""
                SELECT id, CAST(status AS SIGNED) AS status, title, summary, content, coverImg, category, source,
                       CAST(featured AS SIGNED) AS featured, reviewNote, reviewedById, curationCandidateId,
                       materialRevisionId, evidenceSnapshotSha256, currentRevisionNo, publishedRevisionNo
                FROM t_news
                WHERE id = ? AND isDelete = 0
                FOR UPDATE
                """, (rs, rowNum) -> {
            News n = new News();
            n.setId(rs.getLong("id"));
            n.setStatus(getOptionalInt(rs, "status"));
            n.setTitle(rs.getString("title"));
            n.setSummary(rs.getString("summary"));
            n.setContent(rs.getString("content"));
            n.setCoverImg(rs.getString("coverImg"));
            n.setCategory(rs.getString("category"));
            n.setSource(rs.getString("source"));
            n.setFeatured(getOptionalInt(rs, "featured"));
            n.setReviewNote(rs.getString("reviewNote"));
            n.setReviewedById(getOptionalLong(rs, "reviewedById"));
            n.setCurationCandidateId(getOptionalLong(rs, "curationCandidateId"));
            n.setMaterialRevisionId(getOptionalLong(rs, "materialRevisionId"));
            n.setEvidenceSnapshotSha256(rs.getString("evidenceSnapshotSha256"));
            n.setCurrentRevisionNo(getOptionalInt(rs, "currentRevisionNo"));
            n.setPublishedRevisionNo(getOptionalInt(rs, "publishedRevisionNo"));
            return n;
        }, newsId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private String loadExistingSnapshotJson(Long newsId, Long candidateId) {
        List<String> prev = jdbc.query("""
                SELECT evidenceSnapshotJson
                FROM t_news_revision
                WHERE newsId = ? AND evidenceSnapshotJson IS NOT NULL
                ORDER BY revisionNo DESC
                LIMIT 1
                """, (rs, rowNum) -> rs.getString(1), newsId);
        if (!prev.isEmpty() && !isBlank(prev.getFirst())) {
            return prev.getFirst();
        }
        if (candidateId != null) {
            List<String> fromCandidate = jdbc.query(
                    "SELECT evidenceSnapshotJson FROM t_news_curation_candidate WHERE id = ?",
                    (rs, rowNum) -> rs.getString(1),
                    candidateId);
            if (!fromCandidate.isEmpty()) {
                return fromCandidate.getFirst();
            }
        }
        return null;
    }

    private void insertRevisionRow(Long newsId,
                                   int revisionNo,
                                   String action,
                                   int status,
                                   String title,
                                   String summary,
                                   String content,
                                   String coverImg,
                                   String category,
                                   String source,
                                   int featured,
                                   Long curationCandidateId,
                                   Long materialRevisionId,
                                   String evidenceSnapshotSha256,
                                   String evidenceSnapshotJson,
                                   User actor,
                                   String reviewNote) {
        String contentSha256 = NewsMaterialCanonicalizer.sha256Hex(
                JsonUtils.toJson(List.of(
                        nonNullString(title),
                        nonNullString(summary),
                        nonNullString(content),
                        nonNullString(coverImg),
                        nonNullString(category),
                        nonNullString(source),
                        featured
                )).getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
                INSERT INTO t_news_revision
                    (newsId, revisionNo, action, status, title, summary, content, coverImg,
                     category, source, featured, curationCandidateId, materialRevisionId,
                     evidenceSnapshotSha256, evidenceSnapshotJson, contentSha256,
                     actorId, actorRole, reviewNote)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                newsId,
                revisionNo,
                action,
                status,
                truncate(nonNullString(title), 255),
                truncate(summary, 512),
                nonNullString(content),
                truncate(nonNullString(coverImg), 512),
                truncate(category, 32),
                truncate(source, 255),
                featured,
                curationCandidateId,
                materialRevisionId,
                evidenceSnapshotSha256,
                evidenceSnapshotJson,
                contentSha256,
                actorId(actor),
                actor == null ? null : truncate(actor.getUserRole(), 24),
                truncate(reviewNote, 512));
    }

    private void bumpCacheEpochIfConfigured() {
        if (newsListCacheService != null) {
            newsListCacheService.bumpEpochInTransaction();
        }
    }

    private String resolveCandidateTitle(CandidateRow c) {
        if (!isBlank(c.suggestedTitle)) {
            return truncate(c.suggestedTitle.trim(), 255);
        }
        try {
            JsonNode root = protocolMapper.readTree(c.evidenceSnapshotJson);
            if (root.hasNonNull("title") && !root.get("title").asText().isBlank()) {
                return truncate(root.get("title").asText().trim(), 255);
            }
        } catch (JsonProcessingException ignored) {
            // Fall through to deterministic fallback title.
        }
        return truncate("眼科研究动态 (" + c.sourceRecordId + ")", 255);
    }

    private String resolveCandidateSummary(CandidateRow c, String fallbackContent) {
        if (!isBlank(c.suggestedSummary)) {
            return truncate(c.suggestedSummary.trim(), 512);
        }
        String collapsed = HtmlUtil.cleanHtmlTag(fallbackContent).replaceAll("\\s+", " ").trim();
        return truncate(collapsed, 512);
    }

    /**
     * 库内 {@code t_news.content} 的既有约定是富文本：编辑页保存时走 {@code renderNewsHtml}
     * 把 Markdown 转成 HTML，公开页再按 HTML 渲染。这里直接产出 HTML 并在拼接时逐段转义
     * （候选与声明文本来自模型输出，不能当可信标记），否则读者看到的是字面的「## 证据要点」。
     */
    private String buildCandidateEditorialContent(CandidateRow c) {
        StringBuilder sb = new StringBuilder();
        if (!isBlank(c.suggestedSummary)) {
            sb.append("<p>").append(HtmlUtil.escape(c.suggestedSummary.trim())).append("</p>\n");
        }
        List<NewsCurationModels.CurationClaim> claims = readJsonList(c.claimsJson, CLAIM_LIST_TYPE);
        if (!claims.isEmpty()) {
            sb.append("<h2>证据要点</h2>\n<ul>\n");
            for (NewsCurationModels.CurationClaim claim : claims) {
                sb.append("<li><strong>[").append(HtmlUtil.escape(claim.field())).append("]</strong> ")
                        .append(HtmlUtil.escape(claim.statement()))
                        .append("（原文引文：&ldquo;").append(HtmlUtil.escape(claim.quote()))
                        .append("&rdquo;）</li>\n");
            }
            sb.append("</ul>\n");
        }
        List<String> limitations = readJsonList(c.limitationsJson, STRING_LIST_TYPE);
        if (!limitations.isEmpty()) {
            sb.append("<h2>局限与边界说明</h2>\n<ul>\n");
            for (String limitation : limitations) {
                sb.append("<li>").append(HtmlUtil.escape(limitation)).append("</li>\n");
            }
            sb.append("</ul>\n");
        }
        String originUrl = extractSnapshotUrl(c.evidenceSnapshotJson);
        sb.append("<h2>原始资料索引</h2>\n<p>来源：")
                .append(HtmlUtil.escape(c.source)).append(" / ").append(HtmlUtil.escape(c.sourceRecordId));
        if (!isBlank(originUrl)) {
            sb.append("（").append(HtmlUtil.escape(originUrl)).append("）");
        }
        sb.append("</p>");
        return sb.toString().trim();
    }

    private String extractSnapshotUrl(String snapshotJson) {
        if (isBlank(snapshotJson)) {
            return null;
        }
        try {
            JsonNode root = protocolMapper.readTree(snapshotJson);
            return root.hasNonNull("url") ? root.get("url").asText() : null;
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private <T> List<T> readJsonList(String json, TypeReference<List<T>> type) {
        if (isBlank(json)) {
            return List.of();
        }
        try {
            List<T> list = protocolMapper.readValue(json, type);
            return list == null ? List.of() : list;
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "候选结构化 JSON 解析失败");
        }
    }

    private static CandidateRow mapCandidateRow(ResultSet rs) throws SQLException {
        return new CandidateRow(
                rs.getLong("id"),
                rs.getLong("materialId"),
                rs.getLong("materialRevisionId"),
                rs.getString("source"),
                rs.getString("sourceRecordId"),
                rs.getString("contentDigest"),
                rs.getString("policyVersion"),
                rs.getString("callId"),
                rs.getString("status"),
                rs.getString("decision"),
                rs.getString("evidenceTier"),
                getOptionalInt(rs, "relevanceScore"),
                rs.getString("suggestedTitle"),
                rs.getString("suggestedSummary"),
                rs.getString("category"),
                rs.getString("limitationsJson"),
                rs.getString("claimsJson"),
                rs.getString("violationsJson"),
                rs.getString("evidenceSnapshotJson"),
                rs.getString("evidenceSnapshotSha256")
        );
    }

    private static NewsRevisionVO mapRevisionRow(ResultSet rs) throws SQLException {
        NewsRevisionVO vo = new NewsRevisionVO();
        vo.setId(rs.getLong("id"));
        vo.setNewsId(rs.getLong("newsId"));
        vo.setRevisionNo(rs.getInt("revisionNo"));
        vo.setAction(rs.getString("action"));
        vo.setStatus(rs.getInt("status"));
        vo.setTitle(rs.getString("title"));
        vo.setSummary(rs.getString("summary"));
        vo.setContent(rs.getString("content"));
        vo.setCoverImage(rs.getString("coverImg"));
        vo.setCategory(rs.getString("category"));
        vo.setSource(rs.getString("source"));
        vo.setFeatured(rs.getInt("featured"));
        vo.setCurationCandidateId(getOptionalLong(rs, "curationCandidateId"));
        vo.setMaterialRevisionId(getOptionalLong(rs, "materialRevisionId"));
        vo.setEvidenceSnapshotSha256(rs.getString("evidenceSnapshotSha256"));
        vo.setEvidenceSnapshotJson(rs.getString("evidenceSnapshotJson"));
        vo.setContentSha256(rs.getString("contentSha256"));
        vo.setActorId(getOptionalLong(rs, "actorId"));
        vo.setActorRole(rs.getString("actorRole"));
        vo.setReviewNote(rs.getString("reviewNote"));
        Timestamp createdAt = rs.getTimestamp("createdAt");
        vo.setCreatedAt(createdAt == null ? null : new Date(createdAt.getTime()));
        return vo;
    }

    private static Integer getOptionalInt(ResultSet rs, String col) throws SQLException {
        Object val = rs.getObject(col);
        if (val == null) {
            return null;
        }
        if (val instanceof Boolean b) {
            return b ? 1 : 0;
        }
        return ((Number) val).intValue();
    }

    private static Long getOptionalLong(ResultSet rs, String col) throws SQLException {
        Object val = rs.getObject(col);
        if (val == null) {
            return null;
        }
        if (val instanceof Boolean b) {
            return b ? 1L : 0L;
        }
        return ((Number) val).longValue();
    }

    /** id 列表去重 + 去空。 */
    private List<Long> normalizeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Set<Long> unique = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null && id > 0) {
                unique.add(id);
            }
        }
        return new ArrayList<>(unique);
    }

    /** 合法迁移表：DRAFT→PUBLISHED 是人工编辑兼容通道，受发布门禁约束。 */
    private static boolean isAllowed(Integer from, Integer to) {
        if (!NewsStatus.isValid(from) || !NewsStatus.isValid(to)) {
            return false;
        }
        if (from.equals(to)) {
            return true;
        }
        return ALLOWED.get(from).contains(to);
    }

    private static void stampReview(News patch, Integer from, Integer to, User actor, String note) {
        Date now = new Date();
        if (NewsStatus.PUBLISHED == to) {
            // publishTime 是对外可见的时间：公开页按它排序与展示「发布于」，
            // 刊物预览按它分期刊。建稿时写它只为满足 NOT NULL，真正的发布时刻在这里覆盖。
            patch.setPublishTime(now);
        }
        if (NewsStatus.PENDING_REVIEW == to) {
            patch.setSubmittedById(actorId(actor));
            patch.setSubmittedAt(now);
        }
        if (NewsStatus.PUBLISHED == to || NewsStatus.OFFLINE == to
                || (NewsStatus.DRAFT == to && from != null && NewsStatus.PENDING_REVIEW == from)) {
            patch.setReviewedById(actorId(actor));
            patch.setReviewedAt(now);
        }
        if (note != null) {
            patch.setReviewNote(note);
            if (NewsStatus.OFFLINE == to) {
                patch.setOfflineReason(note);
            }
        }
    }

    private void audit(Long newsId, User actor, String action,
                       Integer fromStatus, Integer toStatus, Object payload) {
        NewsAudit entry = new NewsAudit();
        entry.setNewsId(newsId);
        entry.setActorId(actorId(actor));
        entry.setActorRole(actor == null ? null : actor.getUserRole());
        entry.setAction(action);
        entry.setFromStatus(fromStatus == null ? null : NewsStatus.label(fromStatus));
        entry.setToStatus(toStatus == null ? null : NewsStatus.label(toStatus));
        entry.setCreatedTime(new Date());
        entry.setPayload(JsonUtils.toJson(payload));
        newsAuditMapper.insert(entry);
    }

    private static Long actorId(User actor) {
        return actor == null ? null : actor.getId();
    }

    private static String actionFor(Integer from, Integer to) {
        return switch (to) {
            case NewsStatus.PENDING_REVIEW -> "SUBMIT_REVIEW";
            case NewsStatus.PUBLISHED -> "PUBLISH";
            case NewsStatus.OFFLINE -> "OFFLINE";
            case NewsStatus.DRAFT ->
                    from != null && NewsStatus.PENDING_REVIEW == from ? "REJECT" : "EDIT";
            default -> "EDIT";
        };
    }

    /** 字段级 diff 摘要：只记「哪些字段被改」，不复制富文本正文全文。 */
    private static List<String> changedFields(News patch, String note) {
        List<String> changed = new ArrayList<>();
        if (patch.getTitle() != null) changed.add("title");
        if (patch.getContent() != null) changed.add("content");
        if (patch.getSummary() != null) changed.add("summary");
        if (patch.getCoverImg() != null) changed.add("coverImg");
        if (patch.getStatus() != null) changed.add("status");
        if (patch.getCategory() != null) changed.add("category");
        if (patch.getSource() != null) changed.add("source");
        if (patch.getFeatured() != null) changed.add("featured");
        if (patch.getCurationCandidateId() != null) changed.add("curationCandidateId");
        if (note != null) changed.add("note");
        return changed;
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private static String nonNullString(String text) {
        return text == null ? "" : text;
    }

    private static String firstNonNull(String preferred, String fallback) {
        return preferred != null ? preferred : fallback;
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }

    private record CandidateRow(
            long id,
            long materialId,
            long materialRevisionId,
            String source,
            String sourceRecordId,
            String contentDigest,
            String policyVersion,
            String callId,
            String status,
            String decision,
            String evidenceTier,
            Integer relevanceScore,
            String suggestedTitle,
            String suggestedSummary,
            String category,
            String limitationsJson,
            String claimsJson,
            String violationsJson,
            String evidenceSnapshotJson,
            String evidenceSnapshotSha256
    ) {
    }
}
