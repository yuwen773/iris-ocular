package com.csust.eyediagnosis.miniapp;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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

import java.util.Objects;

/**
 * 小程序静默登录：code → openid → 绑定账号 → 签发令牌。
 *
 * <p>首登自动建号（role=user），不与既有密码账号合并：体验版没有历史数据迁移需求，
 * 个人主体也拿不到手机号做身份核验。账号数据仍落在 t_user，管理端与既有查询无需改动。
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

    public MiniAppAuthService(WeChatMiniAppProperties properties,
                              WeChatCode2SessionClient weChatClient,
                              UserWechatMapper bindingMapper,
                              UserMapper userMapper,
                              PasswordService passwordService,
                              MiniAppTokenService tokenService,
                              PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.weChatClient = weChatClient;
        this.bindingMapper = bindingMapper;
        this.userMapper = userMapper;
        this.passwordService = passwordService;
        this.tokenService = tokenService;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * 换 code 是外部网络调用（最长 5s），必须留在事务外：事务里等一个 HTTP 响应会把
     * 数据库连接和行锁一起挂住。数据库侧（建号 + 写绑定）才进事务。
     */
    public LoginResult login(String code) {
        WeChatCode2SessionClient.WeChatSession session = weChatClient.exchange(code);
        User user = Objects.requireNonNull(transactions.execute(status -> resolveUser(session)));
        if (AuthConstant.BAN_ROLE.equals(user.getUserRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN_ERROR, "账号已被封禁");
        }
        MiniAppTokenService.IssuedToken issued = tokenService.issue(user.getId());
        return new LoginResult(issued.token(), issued.expiresInSeconds(), user);
    }

    private User resolveUser(WeChatCode2SessionClient.WeChatSession session) {
        UserWechat binding = findBinding(session.openid());
        if (binding != null) {
            User bound = userMapper.selectById(binding.getUserId());
            if (bound != null) {
                return bound;
            }
            // 绑定指向的账号已被删除：重新建号并改绑，避免用户卡在「登录成功却没有身份」。
            User recreated = createUser(session.openid());
            rebind(binding, recreated.getId(), session.unionid());
            return recreated;
        }
        User created = createUser(session.openid());
        UserWechat newBinding = new UserWechat();
        newBinding.setUserId(created.getId());
        newBinding.setAppid(properties.getAppid());
        newBinding.setOpenid(session.openid());
        newBinding.setUnionid(session.unionid());
        try {
            bindingMapper.insert(newBinding);
            return created;
        } catch (DuplicateKeyException e) {
            // 并发首登：另一个请求先写了绑定，本次新建的账号作废，回读赢家的账号。
            UserWechat winnerBinding = findBinding(session.openid());
            User winner = winnerBinding == null ? null : userMapper.selectById(winnerBinding.getUserId());
            if (winner == null) {
                throw e;
            }
            log.info("小程序并发首登命中已有绑定, userId={}", winner.getId());
            return winner;
        }
    }

    private UserWechat findBinding(String openid) {
        return bindingMapper.selectOne(new QueryWrapper<UserWechat>()
                .eq("appid", properties.getAppid())
                .eq("openid", openid));
    }

    private void rebind(UserWechat binding, Long userId, String unionid) {
        binding.setUserId(userId);
        if (binding.getUnionid() == null && unionid != null) {
            binding.setUnionid(unionid);
        }
        bindingMapper.updateById(binding);
    }

    private User createUser(String openid) {
        String baseAccount = "wx_" + openid;
        for (int attempt = 0; attempt < 3; attempt++) {
            String account = attempt == 0 ? baseAccount : baseAccount + "_" + RandomUtil.randomString(6);
            if (userMapper.selectCount(new QueryWrapper<User>().eq("userAccount", account)) > 0) {
                continue;
            }
            User user = new User();
            user.setUserAccount(account);
            // 微信账号不走密码登录：写一段随机、不可知的 BCrypt 摘要占住 NOT NULL 列。
            user.setUserPassword(passwordService.encode(RandomUtil.randomString(32)));
            user.setUserName("微信用户");
            user.setUserRole(UserRole.USER.getValue());
            if (userMapper.insert(user) == 1) {
                return user;
            }
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "创建微信账号失败");
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
