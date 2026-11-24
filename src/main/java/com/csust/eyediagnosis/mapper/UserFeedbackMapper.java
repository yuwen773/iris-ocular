package com.csust.eyediagnosis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.csust.eyediagnosis.model.entity.UserFeedback;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户反馈持久层。
 *
 * <p>除 BaseMapper 的常规 CRUD 外，只多两条「关联对象归属」的计数查询：
 * 用户提交反馈时可以把反馈挂到一次判读或一个 Agent 会话上，服务端必须确认
 * 那个对象**确实属于提交人**，否则等于给了「读别人任务存在性」的推断口子。
 *
 * <p>两条查询都直接读各自的原表：{@code t_diagnosis_task} 没有 isDelete 列
 * （V18），{@code t_agent_session} 有（V15），因此过滤条件不同，不能统一。
 */
public interface UserFeedbackMapper extends BaseMapper<UserFeedback> {

    /** 关联对象是一次判读任务：必须存在且属于提交人。 */
    @Select("SELECT COUNT(*) FROM t_diagnosis_task WHERE taskId = #{taskId} AND userId = #{userId}")
    int countOwnedDiagnosisTask(@Param("taskId") String taskId, @Param("userId") Long userId);

    /** 关联对象是一个 Agent 会话：必须存在、未删除且属于提交人。 */
    @Select("SELECT COUNT(*) FROM t_agent_session WHERE sessionId = #{sessionId} AND userId = #{userId} AND isDelete = 0")
    int countOwnedAgentSession(@Param("sessionId") String sessionId, @Param("userId") Long userId);
}
