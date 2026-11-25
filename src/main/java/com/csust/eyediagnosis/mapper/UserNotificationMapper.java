package com.csust.eyediagnosis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.csust.eyediagnosis.model.entity.UserNotification;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户通知持久层。
 *
 * <p>除常规 CRUD 外只多一条「任务归属人」查询：复核完成的接收人是任务所有者，
 * 而复核流程手里只有 taskId。把这条查询放在这里而不是塞进 DoctorReviewService，
 * 是为了让「通知发给谁」只有一处实现。
 */
public interface UserNotificationMapper extends BaseMapper<UserNotification> {

    /** 判读任务的所有者；任务不存在时返回 null。 */
    @Select("SELECT userId FROM t_diagnosis_task WHERE taskId = #{taskId}")
    Long selectTaskOwner(@Param("taskId") String taskId);
}
