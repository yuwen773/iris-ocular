package com.csust.eyediagnosis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.csust.eyediagnosis.model.entity.UserWechat;
import org.apache.ibatis.annotations.Mapper;

/**
 * 小程序微信账号绑定表的数据库操作。
 */
@Mapper
public interface UserWechatMapper extends BaseMapper<UserWechat> {
}
