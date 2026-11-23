package com.csust.eyediagnosis.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 日志里的主体标识匿名单一入口。
 *
 * <p>运行日志不得出现原始 userId 与任何患者输入：关联一次请求只需要一个稳定的短哈希。
 * 口径只有一份——同一轮对话在控制器里记的 {@code userIdHash}，和流式闸门里记的
 * 必须是同一个值，否则排查时对不上号。
 */
public final class LogAnonymizer {

    private LogAnonymizer() {
    }

    /** userId → 日志用的短哈希（SHA-256 前 12 位十六进制）。 */
    public static String userId(Long userId) {
        if (userId == null) {
            return "anonymous";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(userId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (Exception e) {
            return "unavailable";
        }
    }
}
