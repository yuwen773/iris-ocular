package com.csust.eyediagnosis.controlcenter.model.vo;

import java.util.List;

/**
 * 一页结果（发布历史 / 审批列表）。
 *
 * <p>{@code page} 从 1 开始；{@code pageSize} 由调用方给出，服务层夹到 [1, 100]。
 */
public record PageVO<T>(List<T> records, long total, long page, long pageSize) {
}
