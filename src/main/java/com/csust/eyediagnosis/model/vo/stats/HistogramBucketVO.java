package com.csust.eyediagnosis.model.vo.stats;

import lombok.Data;

import java.io.Serializable;

/**
 * 直方图一格：桶标签 + 计数。
 *
 * <p>置信度直方图固定 10 桶（0–10 … 90–100），空桶由 Service 补 0，
 * 保证柱图横轴连续。
 */
@Data
public class HistogramBucketVO implements Serializable {

    /** 桶标签，如 {@code 0–10} */
    private String bucket;

    /** 计数 */
    private long count;

    public HistogramBucketVO() {
    }

    public HistogramBucketVO(String bucket, long count) {
        this.bucket = bucket;
        this.count = count;
    }

    private static final long serialVersionUID = 1L;
}
