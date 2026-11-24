package com.csust.eyediagnosis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.csust.eyediagnosis.controlcenter.model.entity.ConfigActivePointer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 单行活跃指针。
 *
 * <p>发布路径的串行化点：`SELECT … FOR UPDATE` 之后，所有发布/回滚按到达顺序执行，
 * 指针条件更新是第二道闸（读到旧值不等于期望值时 0 行受影响 → 40900）。
 */
@Mapper
public interface ConfigActivePointerMapper extends BaseMapper<ConfigActivePointer> {

    @Select("SELECT * FROM t_config_active_pointer WHERE id = 1 FOR UPDATE")
    ConfigActivePointer selectForUpdate();

    /**
     * NULL 安全的条件切换。
     *
     * <p>用 {@code <=>} 而不是 {@code =}：首次发布时 {@code releaseId} 是 NULL，
     * {@code =} 会让条件恒为假，永远切不过去。
     */
    @Update("UPDATE t_config_active_pointer SET releaseId = #{releaseId}, updatedBy = #{updatedBy} "
            + "WHERE id = 1 AND releaseId <=> #{expectedReleaseId,jdbcType=BIGINT}")
    int switchPointer(@Param("releaseId") Long releaseId,
                      @Param("expectedReleaseId") Long expectedReleaseId,
                      @Param("updatedBy") Long updatedBy);
}
