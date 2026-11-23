package com.csust.eyediagnosis.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.IService;
import com.csust.eyediagnosis.common.BatchResultVO;
import com.csust.eyediagnosis.model.dto.diagnosis.DiagnosisReexamineDTO;
import com.csust.eyediagnosis.model.dto.user.UserEditDTO;
import com.csust.eyediagnosis.model.dto.user.UserQueryDTO;
import com.csust.eyediagnosis.model.entity.User;
import com.csust.eyediagnosis.model.vo.user.LoginUserVO;
import com.csust.eyediagnosis.model.vo.user.UserVO;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/**
* @author 钰玟
* @description 针对表【t_user(用户)】的数据库操作Service
* @createDate 2025-04-12 15:23:32
*/
public interface UserService extends IService<User> {
    /**
     * 用户注册
     *
     * @param userAccount   用户账户
     * @param userPassword  用户密码
     * @param checkPassword 校验密码
     * @return 新用户 id
     */
    long userRegister(String userAccount, String userPassword, String checkPassword);

    /**
     * 用户登录
     * @param userAccount 登录账号
     * @param userPassword 登录密码
     * @param request 请求对象
     * @return VO对象 (脱敏后的对象)
     */
    LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request);

    /**
     * 获取当前登录用户
     * @param request 请求
     * @return user
     */
    User getLoginUser(HttpServletRequest request);

    /**
     * 账号当前是否仍可用于访问（存在且未被封禁）。
     *
     * <p>{@link #getLoginUser} 只能在请求开始时回答这个问题。流式响应会持续数分钟，
     * 期间发生的封禁需要长连接自己回查——用这个方法，不要另写一份判据。
     *
     * @param userId 用户 id
     * @return 存在且未封禁时为 {@code true}
     */
    boolean isAccountActive(Long userId);


    /**
     * 获取脱敏的已登录用户信息
     * @param user 用户
     * @return 脱敏对象
     */
    LoginUserVO getLoginUserVO(User user);

    /**
     * 用户注销
     * @param request 请求
     * @return boolean
     */
    Boolean userLogout(HttpServletRequest request);

    /**
     * 获取user vo
     * @param user user
     * @return userVO
     */
     UserVO getUserVO(User user);

    /**
     * 获取user列表
     * @param userList 列表
     * @return VO列表
     */
     List<UserVO> getUserVOList(List<User> userList);


    /**
     * 分页查询用户列表
     * @param userQueryDTO
     * @return
     */
     QueryWrapper<User> getUserQueryWrapper(UserQueryDTO userQueryDTO);

    /**
     * 是否为管理员
     *
     * @param user
     * @return
     */
    boolean isAdmin(User user);

    /**
     * 上传用户头像
     * @param multipartFile  文件
     * @param userEditDTO userDTO
     * @param loginUser 当前用户
     * @return userVO
     */
    UserVO uploadUserAvatar(MultipartFile multipartFile, UserEditDTO userEditDTO, User loginUser);

    /**
     * 用户申请复诊
     * @param diagnosisReexamineDTO 复诊信息
     * @param loginUser 当前用户
     */
    int reexamineDiagnosis(DiagnosisReexamineDTO diagnosisReexamineDTO, User loginUser);

    /**
     * 更新当前登录用户的自助资料。目标 ID 永远取 loginUser.id，
     * 不接受请求中传入的 id/userRole/userPassword 等身份字段。
     */
    boolean updateOwnProfile(UserEditDTO input, User loginUser);

    /**
     * 对 BCrypt 密码执行编码，供 Controller 在创建/重置密码时使用。
     */
    String encodePassword(String rawPassword);

    /**
     * 批量删除用户。
     *
     * <p>管理员账号不在可删范围内：删掉唯一的管理员之后这套系统就没人能管了，
     * 因此这类目标会被跳过并写进失败清单，而不是静默删除。
     *
     * @param userIds 目标用户 id
     * @return 逐条结果回执
     */
    BatchResultVO deleteUsers(List<Long> userIds);

    /**
     * 批量改角色（封禁 / 解封 / 提权）。
     *
     * @param userIds  目标用户 id
     * @param userRole 目标角色：user / ban / admin
     * @return 逐条结果回执
     */
    BatchResultVO updateUserRole(List<Long> userIds, String userRole);

    /**
     * 单条删除用户。
     *
     * <p>与批量删除共用同一条策略：管理员账号不可删除。单条入口不再直接
     * {@code removeById}，否则批量入口的保护会被绕过。
     */
    boolean deleteUser(Long id);

    /**
     * 单条更新用户。角色字段与批量改角色共用白名单，且管理员账号不可单条降级。
     */
    boolean updateUser(com.csust.eyediagnosis.model.dto.user.UserUpdateDTO input);
}
