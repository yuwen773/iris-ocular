package com.csust.eyediagnosis.miniapp;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.csust.eyediagnosis.common.InsertRaceGuard;
import com.csust.eyediagnosis.constant.AuthConstant;
import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.csust.eyediagnosis.mapper.UserMapper;
import com.csust.eyediagnosis.mapper.UserWechatMapper;
import com.csust.eyediagnosis.model.entity.User;
import com.csust.eyediagnosis.model.entity.UserWechat;
import com.csust.eyediagnosis.model.enums.UserRole;
import com.csust.eyediagnosis.security.PasswordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * 小程序静默登录：code → openid → 绑定账号 → 签发令牌。
 *
 * <p>首登自动建号（role=user），不与既有密码账号合并：体验版没有历史数据迁移需求，
 * 个人主体也拿不到手机号做身份核验。账号数据仍落在 t_user，管理端与既有查询无需改动。
 *
 * <p>已注销的账号（管理员在后台删除）不会再被静默重建——删掉就是删掉，
 * 与密码通道对已删除账号的口径一致。
 */
@Slf4j
@Service
public class MiniAppAuthService {

    private final WeChatMiniAppProperties properties;
    private final WeChatCode2SessionClient weChatClient;
    private final UserWechatMapper bindingMapper;
    private final UserMapper userMapper;
    private final PasswordService passwordService;
    private final MiniAppTokenService tokenService;
    private final TransactionTemplate transactions;
    private final InsertRaceGuard insertRaceGuard;
    private final SecureRandom random = new SecureRandom();

    public MiniAppAuthService(WeChatMiniAppProperties properties,
                              WeChatCode2SessionClient weChatClient,
                              UserWechatMapper bindingMapper,
                              UserMapper userMapper,
                              PasswordService passwordService,
                              MiniAppTokenService tokenService,
                              PlatformTransactionManager transactionManager,
                              InsertRaceGuard insertRaceGuard) {
        this.properties = properties;
        this.weChatClient = weChatClient;
        this.bindingMapper = bindingMapper;
        this.userMapper = userMapper;
        this.passwordService = passwordService;
        this.tokenService = tokenService;
        this.transactions = new TransactionTemplate(transactionManager);
        this.insertRaceGuard = insertRaceGuard;
    }

    /**
     * 换 code 是外部网络调用（最长 5s），必须留在事务外：事务里等一个 HTTP 响应会把
     * 数据库连接和行锁一起挂住。数据库侧（建号 + 写绑定）才进事务。
     */
    public LoginResult login(String code) {
        WeChatCode2SessionClient.WeChatSession session = weChatClient.exchange(code);
        User user = resolveOrRaceBack(session);
        if (AuthConstant.isBanned(user.getUserRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN_ERROR, "账号已被封禁");
        }
        MiniAppTokenService.IssuedToken issued = tokenService.issue(user.getId());
        return new LoginResult(issued.token(), issued.expiresInSeconds(), user);
    }

    /**
     * 并发首登：两个请求同时走到「建号 + 写绑定」，唯一键只放过一个。
     *
     * <p>落败方必须让事务**整体回滚**——否则它刚插入的 t_user 行会作为孤儿账号
     * 留在库里（异常在回调内被吞掉时，事务不会被标记为 rollback-only）。
     * 所以这里不捕获绑定插入的异常，而是让它逸出事务后回读赢家的账号。
     */
    private User resolveOrRaceBack(WeChatCode2SessionClient.WeChatSession session) {
        try {
            return Objects.requireNonNull(transactions.execute(status -> resolveUser(session)));
        } catch (DuplicateKeyException race) {
            User winner = transactions.execute(status -> {
                UserWechat binding = findBinding(session.openid());
                if (binding == null) {
                    // 绑定在竞态窗口里被删掉了：这不是「账号注销」，把原始冲突抛回去。
                    throw race;
                }
                User bound = userMapper.selectById(binding.getUserId());
                if (bound == null) {
                    throw new BusinessException(ErrorCode.FORBIDDEN_ERROR, "账号已注销，无法登录");
                }
                return bound;
            });
            log.info("小程序并发首登命中已有绑定, userId={}", Objects.requireNonNull(winner).getId());
            return winner;
        }
    }

    private User resolveUser(WeChatCode2SessionClient.WeChatSession session) {
        UserWechat binding = findBinding(session.openid());
        if (binding != null) {
            User bound = userMapper.selectById(binding.getUserId());
            if (bound != null) {
                return bound;
            }
            // 绑定指向的账号已被删除（t_user 逻辑删除：管理员在后台删掉了这个账号）。
            // 这里**不能**静默建一个新号顶上去——那等于让「删除账号」这个管理动作
            // 失效：封禁→删除的用户会拿到一个全新的、没被封禁的账号。
            // 密码通道对已删除账号同样进不来（登录按 isDelete 过滤），这里保持一致：
            // 账号已注销就是注销，不再登录。需要恢复由管理员处理绑定（见
            // docs/product/wechat-miniapp/README.md 的运维说明）。
            throw new BusinessException(ErrorCode.FORBIDDEN_ERROR, "账号已注销，无法登录");
        }
        User created = createUser(session.openid());
        UserWechat newBinding = new UserWechat();
        newBinding.setUserId(created.getId());
        newBinding.setAppid(properties.getAppid());
        newBinding.setOpenid(session.openid());
        newBinding.setUnionid(session.unionid());
        // 并发首登时这里的唯一键冲突会逸出：事务回滚（含刚建的账号），由调用方回读赢家。
        bindingMapper.insert(newBinding);
        return created;
    }

    private UserWechat findBinding(String openid) {
        return bindingMapper.selectOne(new QueryWrapper<UserWechat>()
                .eq("appid", properties.getAppid())
                .eq("openid", openid));
    }

    private User createUser(String openid) {
        String baseAccount = "wx_" + openid;
        for (int attempt = 0; attempt < 3; attempt++) {
            String account = attempt == 0 ? baseAccount : baseAccount + "_" + randomToken(4);
            User user = new User();
            user.setUserAccount(account);
            // 微信账号不走密码登录：写一段随机、不可知的 BCrypt 摘要占住 NOT NULL 列。
            // 随机源必须是 CSPRNG——这把摘要是账号的唯一「密码」，可预测就等于可登录。
            user.setUserPassword(passwordService.encode(randomToken(32)));
            user.setUserName("微信用户");
            user.setUserRole(UserRole.USER.getValue());
            // 外层是事务（resolveUser 跑在 TransactionTemplate 里），重试插入必须走
            // savepoint：PostgreSQL 上第一次撞键后事务即中止，不包 savepoint 的话
            // 换后缀的第二次插入只会拿到 25P02，三次都白试。
            java.util.concurrent.atomic.AtomicInteger affected = new java.util.concurrent.atomic.AtomicInteger();
            if (insertRaceGuard.tryInsert(() -> affected.set(userMapper.insert(user)))
                    && affected.get() == 1) {
                return user;
            }
            // userAccount 是唯一键：预检看不到并发事务尚未提交的行，只能靠插入本身仲裁。
            log.debug("微信账号 userAccount 冲突或未写入, 换后缀重试, attempt={}", attempt);
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "创建微信账号失败");
    }

    /** CSPRNG 生成的随机串（base64url，无填充）。 */
    private String randomToken(int bytes) {
        byte[] raw = new byte[bytes];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /**
     * @param token            登录令牌（Bearer）
     * @param expiresInSeconds 有效期（秒）
     * @param user             t_user 实体，调用方负责转成对外 VO
     */
    public record LoginResult(String token, long expiresInSeconds, User user) {
        public LoginResult {
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(user, "user");
        }
    }
}
