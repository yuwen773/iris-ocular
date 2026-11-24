package com.csust.eyediagnosis.miniapp;

import com.csust.eyediagnosis.exception.BusinessException;
import com.csust.eyediagnosis.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

/**
 * 调用微信 {@code jscode2session}，把 wx.login 的一次性 code 换成 openid。
 *
 * <p>只在服务端调用。返回对象里<b>不保留 session_key</b>——它在本系统没有任何用途，
 * 少存一份就少一处泄露面（微信同样要求它不得下发到小程序）。
 */
@Slf4j
@Component
public class WeChatCode2SessionClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;
    private final WeChatMiniAppProperties properties;

    /**
     * 本类有第二个（包内可见的）构造器给单测用，Spring 因此不会自动选中唯一的构造器，
     * 必须在这里显式标注；漏了会在启动时变成「No default constructor found」。
     */
    @Autowired
    public WeChatCode2SessionClient(RestClient.Builder builder, WeChatMiniAppProperties properties) {
        this(withTimeouts(builder).build(), properties);
    }

    /** 包内可见：单测用 MockRestServiceServer 绑定好的 RestClient 直接构造。 */
    WeChatCode2SessionClient(RestClient restClient, WeChatMiniAppProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    private static RestClient.Builder withTimeouts(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT);
        factory.setReadTimeout(TIMEOUT);
        return builder.requestFactory(factory);
    }

    public WeChatSession exchange(String code) {
        if (!properties.isConfigured()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "小程序登录未配置");
        }
        Code2SessionResponse response;
        try {
            response = restClient.get()
                    .uri(uri -> uri.scheme("https")
                            .host("api.weixin.qq.com")
                            .path("/sns/jscode2session")
                            .queryParam("appid", properties.getAppid())
                            .queryParam("secret", properties.getSecret())
                            .queryParam("js_code", code)
                            .queryParam("grant_type", "authorization_code")
                            .build())
                    .retrieve()
                    .body(Code2SessionResponse.class);
        } catch (RestClientException e) {
            // 异常信息里可能带请求 URI（含 secret），只记类型不记原文。
            log.warn("微信 jscode2session 调用失败: {}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "微信登录失败，请稍后重试");
        }
        if (response == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "微信登录失败，请稍后重试");
        }
        if (response.errcode() != null && response.errcode() != 0) {
            throw mapError(response.errcode());
        }
        if (response.openid() == null || response.openid().isBlank()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "微信登录失败，请稍后重试");
        }
        return new WeChatSession(response.openid(), response.unionid());
    }

    private BusinessException mapError(int errcode) {
        return switch (errcode) {
            // 40029 code 无效；40163 code 已被使用（小程序端重复提交同一次登录）
            case 40029, 40163 -> new BusinessException(ErrorCode.PARAMS_ERROR, "微信登录凭证无效，请重新进入小程序");
            case 45011 -> new BusinessException(ErrorCode.FORBIDDEN_ERROR, "登录过于频繁，请稍后再试");
            case 40226 -> new BusinessException(ErrorCode.FORBIDDEN_ERROR, "当前微信账号存在风险，暂无法登录");
            default -> {
                log.warn("微信 jscode2session 返回错误码 {}", errcode);
                yield new BusinessException(ErrorCode.SYSTEM_ERROR, "微信登录失败，请稍后重试");
            }
        };
    }

    /**
     * 一次 code2session 的可用结果。
     *
     * @param openid  同一 AppID 内唯一的用户标识
     * @param unionid 绑定微信开放平台后才会下发，可能为 null
     */
    public record WeChatSession(String openid, String unionid) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Code2SessionResponse(String openid, String unionid, Integer errcode, String errmsg) {
    }
}
