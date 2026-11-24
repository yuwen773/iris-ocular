package com.csust.eyediagnosis.controlcenter.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明一个配置中心端点的动作权限。
 *
 * <p>与 {@code @AuthCheck} 并列使用而非替代：{@code @AuthCheck} 负责"是不是管理员"，
 * 本注解负责"这个管理员能不能执行这个动作"。两者语义互不污染。
 *
 * <p>校验由 {@link ConfigPermissionAspect} 完成；未登录抛 40100，
 * 非管理员或不在发布人名单内抛 {@code 40301 CONFIG_PERMISSION_DENIED}。
 *
 * <p>只允许标在方法上：切点是 {@code @annotation(...)}，标在类上不会被拦截，
 * 与其留一个"看起来生效其实没有"的表达，不如直接不允许。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ConfigPermission {

    /** 该端点要求的动作。 */
    ConfigAction value();
}
