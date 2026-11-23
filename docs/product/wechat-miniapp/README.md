# 微信小程序（患者端 · 体验版）

> 状态：**首版实现已落地，未上架**。目标是内部体验版跑通「拍照 → AI 判读 → 结果解读 → 记录 → Agent 咨询」。
> 本文是范围、约束与联调口径的唯一入口；实现细节以 `miniapp/` 与后端 `miniapp` 包为准。

## 1. 范围与定位

| 项 | 决定 |
|---|---|
| 目标用户 | 普通患者自助（不做医生工作台、不做机构版） |
| 发布形态 | **内部体验版**先行；不提交审核、不公开上架 |
| 账号主体 | 个人主体（无法申请医疗服务类目、不支持 `getPhoneNumber`） |
| 首版范围 | 核心闭环 + Agent 咨询（仅文字） |
| 不做 | 医生复核工作台、分享、随访、知识库管理、多版本回答、暗色手写主题之外的视觉变体 |

## 2. 关键约束（先读，均为代码/官方文档事实）

1. **改造前鉴权只有 Session Cookie**：`/api/user/login` 返回 `LoginUserVO`，没有 token；身份完全靠 `SESSION` cookie。
   这是下面第 2 条的直接后果——所以本次新增了一条**并行的** Bearer 令牌通道（§3.1），
   原有 Cookie 通道逐行不变。
2. **`SameOriginFilter` 会 403 掉小程序的每一个非 GET 请求**：它要求 `Origin`/`Referer` 的 host 等于请求 Host；
   而小程序请求的 Referer 被平台固定为 `https://servicewechat.com/{appid}/{version}/page-frame.html`，不可设置。
   因此小程序走**独立 Bearer 令牌通道**，Cookie 通道的同源校验原样保留。
3. **两条诊断链路口径不同**：首版走同步 `POST /api/diagnosis/diagnosis`（立即出结果、2MB 上限、gRPC 10s）；
   异步 `/api/diagnosis-tasks` 需医生复核才对患者可见，体验版无人值守，不采用。
4. **流式只能靠分块传输**：`wx.request` 的 `enableChunked`（基础库 2.20.2）+ `onChunkReceived` 拿 `ArrayBuffer`，
   自己拼 SSE。平台没有官方 SSE 支持，也没有官方 cookie jar。
5. **主包 / 总包**：主包 ≤ 2MB、总包 ≤ 20MB，tabBar 页必须在主包。当前构建产物约 430KB，暂不分包。
6. **合规**：个人主体无任何医疗服务类目；公开上架需企业/机构主体 + 资质。体验成员上限 15 人。

## 3. 架构

### 3.1 后端令牌通道（`com.csust.eyediagnosis.miniapp`）

```
wx.login ──code──▶ POST /api/mp/auth/login ──▶ WeChatCode2SessionClient（jscode2session）
                                              └─▶ t_user_wechat(appid, openid) 绑定
                                                  └─▶ 首登建 t_user（role=user）
                                              └─▶ MiniAppTokenService 签发令牌（Redis 存 SHA-256）
◀───────────── { token, expiresIn, user } ────┘
后续请求：Authorization: Bearer <token>
  └─ MiniAppTokenFilter(@Order 0) 解出 userId 写入请求属性 + 标记令牌认证
     ├─ UserServiceImpl.getLoginUser 先读该属性（读不到才走 Cookie 会话）
     └─ SameOriginFilter 见到标记即跳过 Origin/Referer 校验
```

- 迁移：`V35__miniapp_wechat_binding.sql`（新表 `t_user_wechat`，唯一键 `(appid, openid)`）。
- 令牌：32 字节随机、30 天滑动续期（`wechat.miniapp.token-ttl-days` 可调，默认 30）、Redis 只存哈希；
  `session_key` 不落库、不返回。
- 同源校验有两类放行：令牌认证通过的请求，以及**按路径唯一豁免**的 `/mp/auth/login`
  （它本身就是换令牌的入口，且不写 Cookie）。带无效 Bearer 头的登录请求也按无令牌放行，
  过期令牌不能把重新登录堵死。
- web/ 端行为不变：无 Bearer 头的请求完全走原 Cookie 会话分支。

### 3.2 小程序（`miniapp/`）

Taro 4 + React（Taro 支持的 18.x，与 `web/` 的 React 19 独立）+ TypeScript + Sass。

```
miniapp/src/
  app.tsx / app.config.ts
  styles/tokens.scss        ← scripts/gen-tokens.mjs 从 web/src/styles/tokens.css 生成（勿手改）
  theme.json                ← 同一脚本生成（导航栏 / tabBar 色值跟随令牌，勿手改）
  lib/{env,token,raw,auth,authedCall,request,api,domain,upload,privacy,sse,utf8,agentStream,agentHistory}.ts
  components/{Tags,Feedback,PrivacyDialog}.tsx
  pages/diagnosis/{index,result}.tsx   判读入口 / 结果
  pages/records/index.tsx              记录列表（上拉分页）
  pages/agent/index.tsx                咨询（enableChunked 流式）
  pages/profile/index.tsx              我的 / 隐私指引 / 退出
```

- 主题：`darkmode: true` + `theme.json`，令牌用 `@media (prefers-color-scheme: dark)` 翻转。
  `app.config.ts` 的导航栏 / tabBar 色值从生成的 `theme.json` 读，不手写十六进制。
- 图片：`chooseMedia(compressed)` + `compressImage` 压到 2MB 内；仍超限再走离屏画布等比缩放（见下）。
- 合规四项：隐私授权（自建说明弹层 `components/PrivacyDialog` → 平台 `requirePrivacyAuthorize`）、
  健康数据用途说明、AI 生成内容标识、结果页免责声明。
  弹层守的是**影像采集与相册**这两类平台隐私接口；启动时的静默登录只换 openid（`jscode2session`，
  不在隐私接口清单里），因此不挡在弹层之后——否则每次冷启动都要先点「同意」才能进判读页。
- 图片超限的顺序：`compressImage` 70 → 40 → 离屏画布等比缩到最长边 1600 重编码 → 仍超就明确要求重拍。
- Agent：单会话 + 「最近的对话」列表（`/agent/sessions` → 点选恢复）；流式除总超时 120s 外，
  还有 30s 无分片看门狗，按断流提示，不把半截回答当完整回答。
- 退出登录后本次运行内不再自动登录（`suspended`），「我的」页提供「重新登录」——否则微信身份
  会让「退出」在下一个请求就被静默登录回同一账号。

## 4. 本地联调

1. 后端：`SPRING_PROFILES_ACTIVE=local,dev` 起在 `:8081`；数据库需 MySQL 8（本机 3307）。
2. 配置小程序凭据（服务端环境变量，不入库）：`WECHAT_MINIAPP_APPID` / `WECHAT_MINIAPP_SECRET`。
   未配置时 `/api/mp/auth/login` 直接返回 `50000`（不会带空 AppID 去请求微信）。
3. 小程序：`cd miniapp && npm install && npm run dev:weapp`，用微信开发者工具打开 `miniapp/`。
   `project.config.json` 里 AppID 是 `touristappid`，真机联调前替换为你的 AppID。
4. 真机：开发者工具「详情 → 本地设置」勾选「不校验合法域名」，后端地址用局域网 IP
   （`TARO_APP_API_BASE=http://<LAN-IP>:8081/api npm run dev:weapp`）；手机端在「开发调试」里打开同一开关。

## 5. 验证

- 小程序：`npm run typecheck && npm test && npm run build:weapp`（CI `miniapp` job 同口径）。
  单测只覆盖纯函数（SSE 切帧、流式 UTF-8 解码、域映射、历史回放过滤），**没有组件测试**——
  计划里提过的 `@tarojs/test-utils-react` 没有引入，页面行为靠真机清单验收。
- 后端：`scripts/verify-fast.sh --java FlywayMySqlIntegrationTest --java MiniAppAuthServiceTest --java MiniAppTokenFilterTest --java MiniAppTokenServiceTest --java WeChatCode2SessionClientTest --java SameOriginFilterTest --db`
  （L2，覆盖 V35 迁移与 Spring 装配）。脚本不接受「只有 `--db`」的写法——它要求点名要跑的用例。
- 手工清单：静默登录建号且二次进入同账号；>2MB 图片被压缩或明确拒绝；拒识/待复核不给结论；
  Agent 流式逐字出现且以 `done` 收尾；隐私弹窗先于影像采集；系统深浅色切换无闪烁；
  `web/` 端 Cookie 会话与无令牌 POST 的 403 行为不变。

## 6. 未决与风险

| 项 | 说明 |
|---|---|
| 上架合规 | 个人主体不能申请医疗服务类目；公开上架需企业/机构主体与资质（可能涉及 AI 医疗器械注册证），本阶段不承担 |
| 体验版域名 | 未配置 `服务器域名`；真机依赖「不校验合法域名」。正式上架需 HTTPS + ICP 备案域名（备案要求境内服务器） |
| `enableChunked` | 社区实践、非官方背书；真机需先做最小验证，失败则退回非流式。30s 无分片看门狗同样未经真机校准，阈值可按实测调整 |
| 图片压缩 | 离屏画布等比缩放（最长边 1600）已实现，但 `createOffscreenCanvas` 的 2d 能力**未在真机验证**；任何一步不可用即回退到「提示重拍」，不会退化成上传超限文件 |
| COS 图片 | `/api/diagnosis/upload` 返回公网 COS URL 是既有行为，小程序沿用，作为已知债登记 |
| Agent 会话 | 只有「当前会话 + 历史恢复」，不做重命名/置顶/多版本。历史接口在无游标时返回**最早**的 N 条（`ORDER BY id ASC LIMIT n`），超长会话恢复后看到的是开头一截；要翻到更近的内容需要服务端加「从尾部取」的分页口径，本版未做 |
| 绑定与账号 | 首登按 `wx_<openid>` 建号；`unionid` 只在绑定时回填，不做跨 AppID 账号合并 |
| 令牌续期开销 | 每次校验是一次 Redis `GET` + 一次 `EXPIRE`（滑动续期），不做批量或本地缓存；体验版量级可接受 |
| 流内 40100 | 流式接口的鉴权失败会以 `{"code":40100…}` 正文回到同一路 SSE；客户端只在首块里认这个信封，被切碎或夹在正文后时退化为「连接中断」提示。真机抓包确认后再决定要不要改成「按帧解析」 |
