package com.csust.eyediagnosis.controlcenter.registry;

/** 配置值类型。目前只需要两种：自由文本与受控资产引用。 */
public enum ConfigType {

    /** 用户可见的短文本；长度按 Unicode 码点计。 */
    STRING,

    /**
     * 服务端签发的资产引用。
     *
     * <p>首期只接受 {@code null}（表示使用内置资源）或 32 位小写十六进制 Asset ID；
     * 不接受 URL、路径、SVG 或任何客户端拼出来的字符串——远程 URL 与未处理 SVG
     * 是 PRD FR-04 明令禁止的。
     */
    ASSET_REF
}
