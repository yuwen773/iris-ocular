package com.csust.eyediagnosis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.csust.eyediagnosis.controlcenter.model.entity.ConfigRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ConfigRequestMapper extends BaseMapper<ConfigRequest> {

    /** 幂等重放读取；唯一键是 (actorId, requestId)。 */
    @Select("SELECT * FROM t_config_request WHERE actorId = #{actorId} AND requestId = #{requestId}")
    ConfigRequest selectByActorAndRequest(@Param("actorId") long actorId,
                                          @Param("requestId") String requestId);

    /**
     * 撞唯一键之后的回读。
     *
     * <p>用当前读（{@code FOR UPDATE}）而不是快照读：REPEATABLE READ 下，
     * 如果本事务之前已经做过一致性读，普通 SELECT 可能看不到对方刚提交的行，
     * 会把一次正常的并发重试误判成「记录不存在」。
     */
    @Select("SELECT * FROM t_config_request WHERE actorId = #{actorId} AND requestId = #{requestId} FOR UPDATE")
    ConfigRequest selectByActorAndRequestForUpdate(@Param("actorId") long actorId,
                                                   @Param("requestId") String requestId);

    /** 回填首次执行结果；与业务写、占位插入同事务。 */
    @Update("UPDATE t_config_request SET resultCode = #{resultCode}, "
            + "resultPayload = #{resultPayload,jdbcType=LONGVARCHAR} "
            + "WHERE actorId = #{actorId} AND requestId = #{requestId}")
    int complete(@Param("actorId") long actorId,
                 @Param("requestId") String requestId,
                 @Param("resultCode") int resultCode,
                 @Param("resultPayload") String resultPayload);
}
