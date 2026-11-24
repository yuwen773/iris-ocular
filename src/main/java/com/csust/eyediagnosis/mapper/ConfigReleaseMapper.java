package com.csust.eyediagnosis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.csust.eyediagnosis.controlcenter.model.entity.ConfigRelease;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 发布快照：只有插入与按 id 读取，没有更新路径（不可变）。 */
@Mapper
public interface ConfigReleaseMapper extends BaseMapper<ConfigRelease> {

    /**
     * 下一个发布序号。
     *
     * <p>只在持有活跃指针行锁（{@code FOR UPDATE}）的事务里调用：锁把发布串行化，
     * 因此 MAX+1 不会出现两个事务拿到同一个号。唯一键 {@code uk_config_release_version}
     * 是第二道闸。
     */
    @Select("SELECT COALESCE(MAX(releaseVersion), 0) FROM t_config_release")
    long selectMaxVersion();

    @Select("SELECT * FROM t_config_release ORDER BY releaseVersion DESC LIMIT #{limit} OFFSET #{offset}")
    List<ConfigRelease> page(@Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM t_config_release")
    long countAll();
}
