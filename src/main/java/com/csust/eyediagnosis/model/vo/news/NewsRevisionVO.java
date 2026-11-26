package com.csust.eyediagnosis.model.vo.news;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * Immutable editorial news revision view object returned by {@code GET /news/{id}/revisions} (WP-04).
 */
@Data
public class NewsRevisionVO implements Serializable {

    private Long id;

    private Long newsId;

    private Integer revisionNo;

    private String action;

    private Integer status;

    private String title;

    private String summary;

    private String content;

    private String coverImage;

    private String category;

    private String source;

    private Integer featured;

    private Long curationCandidateId;

    private Long materialRevisionId;

    private String evidenceSnapshotSha256;

    private String evidenceSnapshotJson;

    private String contentSha256;

    private Long actorId;

    private String actorRole;

    private String reviewNote;

    private Date createdAt;

    private static final long serialVersionUID = 1L;
}
