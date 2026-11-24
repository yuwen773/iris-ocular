package com.csust.eyediagnosis.controlcenter.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 单行活跃指针（V35）。
 *
 * <p>发布事务的第一步是 {@code SELECT ... FOR UPDATE} 本行，把并发发布串行化；
 * 没有这行就无法在一个事务里同时做「校验当前版本 → 插入 release → 切换指针」。
 */
@Data
@TableName("t_config_active_pointer")
public class ConfigActivePointer {

    public static final long SINGLETON_ID = 1L;

    @TableId(type = IdType.INPUT)
    private Long id;

    /** 当前生效 release id；null 表示从未发布。 */
    private Long releaseId;

    private Long updatedBy;

    private Date updatedTime;
}
