package com.csust.eyediagnosis.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 前置安全拒绝的审计出口。
 *
 * <p>为什么是接口：Origin / CSRF 过滤器在 `config` 与 `controlcenter` 之间，
 * 而审计实现属于配置中心（要落 `t_config_audit_event`）。用一个窄接口把方向
 * 反过来——过滤器只依赖「有人能记一笔」，不依赖审计表与用户查询的具体实现。
 *
 * <p>实现方必须<b>永不抛异常</b>：审计是旁路，写失败不能改变拒绝本身。
 */
public interface SecurityEventSink {

    /**
     * 记录一次被拒绝的敏感操作尝试。
     *
     * @param request    原始请求（用于在有登录态时解析操作人；不得持久化 Session 内容）
     * @param action     动作标签，如 {@code ORIGIN_REJECTED} / {@code CSRF_REJECTED}
     * @param reasonCode 失败原因码，与接口返回的 reasonCode 一致
     */
    void denied(HttpServletRequest request, String action, String reasonCode);
}
