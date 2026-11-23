package com.csust.eyediagnosis.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 把「服务端取图路径」翻译成「后端签发的短期图片引用」。
 *
 * <p>模型手里只有 {@code /api/agent/image/view/<file>} 这种服务端路径，而算法侧的
 * MCP 工具只认签发过的引用（见 {@link ImageReferenceSigner}）。翻译发生在工具调用
 * 的最后一跳：Java 侧在这里签发引用，算法侧只负责校验，两边的信任边界因此是
 * 「后端签、后端验」，模型全程碰不到目标地址。
 */
@Slf4j
@Component
public class AgentImageReferenceResolver {

    /** 只接受纯文件名：查询串、路径分隔符都不允许进签名目标。 */
    private static final String FILE_NAME_PATTERN = "[A-Za-z0-9_-]+\\.(png|jpg)";

    private final AgentImageUploadService uploadService;

    public AgentImageReferenceResolver(AgentImageUploadService uploadService) {
        this.uploadService = uploadService;
    }

    /**
     * 返回可直接交给工具的引用；不是自家图片路径时原样返回。
     *
     * <p>签发失败（密钥未配置、图片已删、归属不符）时原样返回：让下游按「无法
     * 处理这个输入」失败，而不是把一个未经授权的目标塞给工具。
     */
    public String toReference(String value, Long userId) {
        if (value == null || userId == null) {
            return value;
        }
        if (!value.startsWith(AgentImageUploadService.VIEW_PREFIX)) {
            return value;
        }
        String fileName = value.substring(AgentImageUploadService.VIEW_PREFIX.length());
        if (!fileName.matches(FILE_NAME_PATTERN)) {
            return value;
        }
        try {
            return uploadService.issueImageReference(fileName, userId);
        } catch (Exception e) {
            log.warn("图片引用签发失败，交由工具按无效输入处理: {}", e.getMessage());
            return value;
        }
    }
}
