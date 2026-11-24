package com.csust.eyediagnosis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.csust.eyediagnosis.controlcenter.model.entity.ConfigDraft;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ConfigDraftMapper extends BaseMapper<ConfigDraft> {

    /**
     * 带 revision 条件的草稿更新——乐观并发的唯一判重点。
     *
     * <p>返回 0 表示 revision 已被别人推进（或草稿不存在）；调用方必须据此
     * 返回 40900，而不是把新值覆盖上去。编辑会同时把状态打回 {@code DRAFT}
     * 并清空校验结果：改动之后旧的校验结论不再成立。
     */
    @Update("UPDATE t_config_draft SET revision = revision + 1, payload = #{payload}, "
            + "status = 'DRAFT', validationRevision = NULL, validationResult = NULL, updatedBy = #{updatedBy} "
            + "WHERE id = #{id} AND revision = #{expectedRevision}")
    int updateWithRevision(@Param("id") long id,
                           @Param("expectedRevision") long expectedRevision,
                           @Param("payload") String payload,
                           @Param("updatedBy") Long updatedBy);

    /**
     * 标记校验通过，且只在 revision 未变时生效。
     *
     * <p>校验耗时期间别人可能已经改过草稿；此时校验结论对应的是旧内容，
     * 必须作废（返回 0 → 40900），不能把旧结论挂到新内容上。
     */
    @Update("UPDATE t_config_draft SET status = 'VALIDATED', validationRevision = revision, "
            + "validationResult = #{validationResult} "
            + "WHERE id = #{id} AND revision = #{expectedRevision}")
    int markValidated(@Param("id") long id,
                      @Param("expectedRevision") long expectedRevision,
                      @Param("validationResult") String validationResult);
}
