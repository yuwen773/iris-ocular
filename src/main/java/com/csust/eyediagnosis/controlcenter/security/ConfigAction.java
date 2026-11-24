package com.csust.eyediagnosis.controlcenter.security;

/**
 * 配置中心可授权的动作。
 *
 * <p>这是**控制中心自己的**权限维度，不参与 {@code @AuthCheck} 的角色语义
 * （{@code mustRole} 只有 ADMIN 分支真正生效，不能拿它表达发布/回滚）。
 *
 * <p>当前分层：
 * <ul>
 *   <li>{@link #READ}/{@link #EDIT}/{@link #VALIDATE}：任何管理员；</li>
 *   <li>{@link #PUBLISH}/{@link #ROLLBACK}：管理员 <b>且</b> userId 在
 *       {@code eye-diagnosis.control-center.publisher-user-ids} 名单内。</li>
 * </ul>
 * 名单默认为空，即默认没有人能发布——启用控制中心不会顺带给出发布能力。
 */
public enum ConfigAction {

    /** 读取配置与版本列表。 */
    READ(false),

    /** 创建草稿 / 修改数值（不对外生效）。 */
    EDIT(false),

    /** 触发校验（schema、范围、引用完整性）。 */
    VALIDATE(false),

    /** 发布某个版本，使其生效。 */
    PUBLISH(true),

    /** 回滚到历史版本。 */
    ROLLBACK(true);

    private final boolean requiresPublisher;

    ConfigAction(boolean requiresPublisher) {
        this.requiresPublisher = requiresPublisher;
    }

    /** 是否要求请求者位于发布人白名单内。 */
    public boolean requiresPublisher() {
        return requiresPublisher;
    }
}
