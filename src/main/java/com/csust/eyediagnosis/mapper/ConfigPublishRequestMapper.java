package com.csust.eyediagnosis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.csust.eyediagnosis.controlcenter.model.entity.ConfigPublishRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 发布审批请求。
 *
 * <p>决定路径（批准/拒绝）必须 {@link #selectForUpdate}：同一审批单上的并发决定
 * 由这把行锁串行化，后到者看到的已是终态，只能返回 40909。
 */
@Mapper
public interface ConfigPublishRequestMapper extends BaseMapper<ConfigPublishRequest> {

    /** 决定事务的第一条读：锁住审批行，直到事务提交。 */
    @Select("SELECT * FROM t_config_publish_request WHERE id = #{id} FOR UPDATE")
    ConfigPublishRequest selectForUpdate(@Param("id") long id);

    @Update("UPDATE t_config_publish_request SET status = #{status}, decidedBy = #{decidedBy}, "
            + "decidedTime = NOW(), decisionReason = #{decisionReason,jdbcType=VARCHAR}, "
            + "releaseId = #{releaseId,jdbcType=BIGINT} "
            + "WHERE id = #{id} AND status = 'PENDING'")
    int decide(@Param("id") long id,
               @Param("status") String status,
               @Param("decidedBy") Long decidedBy,
               @Param("decisionReason") String decisionReason,
               @Param("releaseId") Long releaseId);

    @Select("<script>"
            + "SELECT * FROM t_config_publish_request"
            + "<where><if test='status != null and status != \"\"'>status = #{status}</if></where>"
            + " ORDER BY requestedTime DESC, id DESC LIMIT #{limit} OFFSET #{offset}"
            + "</script>")
    List<ConfigPublishRequest> page(@Param("status") String status,
                                    @Param("limit") int limit,
                                    @Param("offset") int offset);

    @Select("<script>"
            + "SELECT COUNT(*) FROM t_config_publish_request"
            + "<where><if test='status != null and status != \"\"'>status = #{status}</if></where>"
            + "</script>")
    long countByStatus(@Param("status") String status);
}
