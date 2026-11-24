package com.csust.eyediagnosis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.csust.eyediagnosis.controlcenter.model.entity.ConfigAuditEvent;
import org.apache.ibatis.annotations.Mapper;

/** 审计事件：只插入。不提供 update/delete 方法。 */
@Mapper
public interface ConfigAuditEventMapper extends BaseMapper<ConfigAuditEvent> {
}
