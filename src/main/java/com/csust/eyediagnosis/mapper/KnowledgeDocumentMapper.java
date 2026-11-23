package com.csust.eyediagnosis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.csust.eyediagnosis.model.entity.KnowledgeDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {
    @Update("UPDATE t_knowledge_document SET indexVersion=#{version}, status=0, chunkCount=NULL "
            + "WHERE id=#{id} AND isDelete=0")
    int beginIndex(@Param("id") Long id, @Param("version") String version);

    @Update("UPDATE t_knowledge_document SET status=#{status}, chunkCount=#{chunkCount} "
            + "WHERE id=#{id} AND indexVersion=#{version} AND isDelete=0")
    int finishIndex(@Param("id") Long id, @Param("version") String version,
                    @Param("status") int status, @Param("chunkCount") Integer chunkCount);

    /** 登记候选版本；只有当前已 READY 的文档可以更新。 */
    @Update("UPDATE t_knowledge_document SET pendingIndexVersion=#{version}, pendingUpdatedTime=NOW(6) "
            + "WHERE id=#{id} AND isDelete=0 AND status=1")
    int beginCandidate(@Param("id") Long id, @Param("version") String version);

    /**
     * 候选版本处理成功：原子切换发布指针，旧版本保持到这一刻。
     *
     * <p>文件元数据（fileName / fileUrl / fileSize / fileType / title）也在这一刻才落库：
     * 候选失败时文档行仍完整描述「正在被检索的那份文件」，不会出现「文件名是新的、
     * 检索结果是旧的」这种对不上的状态。
     */
    @Update("UPDATE t_knowledge_document SET indexVersion=#{version}, status=1, chunkCount=#{chunkCount}, "
            + "fileName=#{fileName}, fileUrl=#{fileUrl}, fileSize=#{fileSize}, fileType=#{fileType}, title=#{title}, "
            + "pendingIndexVersion=NULL, pendingUpdatedTime=NULL "
            + "WHERE id=#{id} AND pendingIndexVersion=#{version} AND isDelete=0")
    int promoteCandidate(@Param("id") Long id, @Param("version") String version,
                         @Param("chunkCount") Integer chunkCount,
                         @Param("fileName") String fileName, @Param("fileUrl") String fileUrl,
                         @Param("fileSize") Long fileSize, @Param("fileType") String fileType,
                         @Param("title") String title);

    /** 候选版本失败/被更新的候选取代：清掉登记，活动版本不变。 */
    @Update("UPDATE t_knowledge_document SET pendingIndexVersion=NULL, pendingUpdatedTime=NULL "
            + "WHERE id=#{id} AND pendingIndexVersion=#{version}")
    int clearCandidate(@Param("id") Long id, @Param("version") String version);

    /**
     * 把长时间停在「处理中」的文档置为失败终态。
     *
     * <p>ETL 在进程里跑，进程退出后没有任何东西会再碰这条记录，{@code status=0}
     * 会永远挂着；管理端于是显示「处理中」而永远不结束。启动后的定期扫描把这类
     * 记录收口成失败，至少让人看得见、能重传。
     */
    @Update("UPDATE t_knowledge_document SET status=2, chunkCount=NULL "
            + "WHERE isDelete=0 AND status=0 AND updateTime < DATE_SUB(NOW(), INTERVAL #{minutes} MINUTE)")
    int failStuckIndexing(@Param("minutes") int minutes);

    /**
     * 列出超时未完成的候选版本登记。活动版本保持 READY 可检索，
     * 清掉的只是「这次更新」的登记。
     *
     * <p>逐行返回而不是一条批量 UPDATE：调用方还要按 (id, pendingIndexVersion)
     * 删掉这次候选写进向量库的分块，只清指针会留下永远检索不到、也不会被覆盖的残留。
     */
    @Select("SELECT id, pendingIndexVersion FROM t_knowledge_document "
            + "WHERE isDelete=0 AND pendingIndexVersion IS NOT NULL "
            + "AND pendingUpdatedTime < DATE_SUB(NOW(), INTERVAL #{minutes} MINUTE)")
    List<KnowledgeDocument> selectStaleCandidates(@Param("minutes") int minutes);
}
