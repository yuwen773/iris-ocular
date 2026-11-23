-- 判读证据的语义拆分（2026-11-23 全仓库审查 M-02）。
--
-- 此前这些含义挤在几个字段里：
--   * probability / reliability_score —— 同一个原始 softmax，被当成「置信度」展示；
--   * calibrationStatus —— 只有状态，没有「这份校准是哪个版本产物」的出处；
--   * qualityScore / qualityReasons —— 有分数与原因，但没有独立的「过没过」状态，
--     也没有判据版本。
-- 结果是：未标定的 softmax 会被读成「医学诊断可靠性」，而历史记录无法回答
-- 「这条结论是在哪份阈值、哪份校准下得出的」。
--
-- 拆分后每个含义各占一列，各自带版本：
--   classificationProbability      原始 softmax top-1（0–1）。不是校准概率，不是可靠性。
--   calibrationVersion             校准产物版本（数据集、样本量、统计口径的出处）。
--   imageQualityState              这张图的质量门结论（PASSED/FAILED/NOT_EVALUATED）。
--   imageQualityVersion            质量门判据版本：阈值变了必须换版本。
--   imageQualityCalibrationState   阈值本身是否在真实数据上标定过。
--   patientConclusionAllowed       该次结论是否允许作为面向患者的结论呈现。
--
-- 只加可空列：老记录没有这些值，不能反推（那等于编造证据）。
ALTER TABLE t_diagnosis
    ADD COLUMN classificationProbability DOUBLE NULL COMMENT '原始 softmax top-1（0-1）；不是校准概率，不是可靠性',
    ADD COLUMN calibrationVersion VARCHAR(64) NULL COMMENT '校准产物版本',
    ADD COLUMN imageQualityState VARCHAR(32) NULL COMMENT 'PASSED / FAILED / NOT_EVALUATED',
    ADD COLUMN imageQualityVersion VARCHAR(64) NULL COMMENT '质量门判据版本',
    ADD COLUMN imageQualityCalibrationState VARCHAR(32) NULL COMMENT '阈值是否在真实数据上标定（UNCALIBRATED / CALIBRATED）',
    ADD COLUMN patientConclusionAllowed TINYINT NULL COMMENT '是否允许作为面向患者的结论呈现';

ALTER TABLE t_inference_cache
    ADD COLUMN classificationProbability DOUBLE NULL COMMENT '原始 softmax top-1（0-1）；不是校准概率，不是可靠性',
    ADD COLUMN calibrationVersion VARCHAR(64) NULL COMMENT '校准产物版本',
    ADD COLUMN imageQualityState VARCHAR(32) NULL COMMENT 'PASSED / FAILED / NOT_EVALUATED',
    ADD COLUMN imageQualityVersion VARCHAR(64) NULL COMMENT '质量门判据版本',
    ADD COLUMN imageQualityCalibrationState VARCHAR(32) NULL COMMENT '阈值是否在真实数据上标定（UNCALIBRATED / CALIBRATED）',
    ADD COLUMN patientConclusionAllowed TINYINT NULL COMMENT '是否允许作为面向患者的结论呈现';
