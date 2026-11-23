-- 受限原因：患者影像任务进入 RESTRICTED 时如实记下是哪一条准入没满足。
-- 在此之前只有状态没有原因，前端只能说「受限」，运维也无法区分
-- 「OOD 未评估」「输出被拒识」「影像不可读」。
ALTER TABLE t_diagnosis_task
    ADD COLUMN restrictReason VARCHAR(64) NULL COMMENT '受限原因（仅 RESTRICTED 时有值）';
