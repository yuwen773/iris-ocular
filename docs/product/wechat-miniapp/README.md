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
- 令牌：32 字节随机、30 天滑动续期（`wechat.miniapp.token-ttl-days` 可调，默认 30，
  **夹到 1–90 天**：配成 0 会签出即抛即废的令牌，配成几千天会造出事实上永不过期的凭据）、
  Redis 只存哈希；`session_key` 不落库、不返回。
- 无效令牌按方法分流：写操作（POST/PUT/PATCH/DELETE）由过滤器直接回 `40100` 业务信封
  （小程序没有浏览器自动附带的凭证，同源校验也过不去，这是客户端静默重登的依据）；
  安全方法（GET/HEAD/OPTIONS）放行且不打令牌标记——公开只读接口不被一枚过期令牌一起
  打挂，受保护接口由鉴权层照常给 `40100`。
- 令牌库（Redis）不可用时**失败关闭**：回 `50000` 业务信封，绝不把「查不到令牌」当成
  「没带令牌」放行。
- 同源校验有两类放行：令牌认证通过的请求，以及**按路径唯一豁免**的 `/mp/auth/login`
  （它本身就是换令牌的入口，且不写 Cookie）。带无效 Bearer 头的登录请求也按无令牌放行，
  过期令牌不能把重新登录堵死。
- 已注销账号（`t_user` 逻辑删除）拒绝登录，不静默重建，见 §8。
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
  还有 30s 无分片看门狗，按断流提示，不把半截回答当完整回答。恢复时先取最近 40 条，
  在列表顶部点「加载更早消息」可继续往前翻。
- 退出登录后本次运行内不再自动登录（`suspended`），「我的」页提供「重新登录」——否则微信身份
  会让「退出」在下一个请求就被静默登录回同一账号。

### 3.3 会话历史接口（Web 与小程序共用）

`GET /api/agent/history/{threadId}` 默认返回最新 40 条，`limit` 上限默认 100。
每页都按消息 ID 升序排列。`beforeId` 取该 ID 之前最近的一页，`afterId` 取该 ID 之后最近的一页，两个参数互斥。

- `hasMore` 表示还有更早消息。为真时，`nextCursor` 为本页第一条 ID，传入 `beforeId`。
- `hasNewer` 表示还有更新消息。`newerCursor` 为本页最后一条 ID，传入 `afterId` 可以继续取后面的消息，也可以从最新页检查之后新增的消息。
- 游标以十进制字符串传输。空页的两个游标均为 `null`。请求会先校验会话归属，游标缺失对应消息、已被删除或属于其他会话时返回业务码 `40000`。

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
  单测 5 个文件 36 条，只覆盖纯函数（SSE 切帧、流式 UTF-8 解码、域映射、历史回放过滤、
  令牌失效信封识别），**没有组件测试**——计划里提过的 `@tarojs/test-utils-react` 没有引入，
  页面行为靠真机清单验收。
- 后端：`scripts/verify-fast.sh --java EyeDiagnosisApplicationTests --java FlywayMySqlIntegrationTest --java MiniAppAuthServiceTest --java MiniAppTokenFilterTest --java MiniAppTokenServiceTest --java WeChatCode2SessionClientTest --java WeChatMiniAppPropertiesTest --java SameOriginFilterTest --db`
  （L2，覆盖 V35 迁移与 Spring 装配）。脚本不接受「只有 `--db`」的写法——它要求点名要跑的用例。
  必须带 `EyeDiagnosisApplicationTests`：新增的 Spring bean 装配错误只在加载完整上下文时暴露，定向用例跑不出来。
- 手工清单：静默登录建号且二次进入同账号；>2MB 图片被压缩或明确拒绝；拒识/待复核不给结论；
  Agent 流式逐字出现且以 `done` 收尾；隐私弹窗先于影像采集；系统深浅色切换无闪烁；
  `web/` 端 Cookie 会话与无令牌 POST 的 403 行为不变。

## 6. 未决与风险

| 项 | 说明 |
|---|---|
| 上架合规 | 个人主体不能申请医疗服务类目；公开上架需企业/机构主体与资质（可能涉及 AI 医疗器械注册证），本阶段不承担 |
| 体验版域名 | 未配置 `服务器域名`；真机依赖「不校验合法域名」。正式上架需 HTTPS + ICP 备案域名（备案要求境内服务器） |
| `enableChunked` | 社区实践、非官方背书；真机需先做最小验证，失败则退回非流式。30s 无分片看门狗同样未经真机校准，阈值可按实测调整 |
| 图片压缩 | 离屏画布等比缩放（最长边 1600）已实现，但 `createOffscreenCanvas` 的 2d 能力**未在真机验证**；任何一步不可用即回退到「提示重拍」，不会退化成上传超限文件。`compressImage` 单档失败（部分格式/机型）不再中断整条链：跳过该档继续走下一档与画布兜底 |
| COS 图片 | `/api/diagnosis/upload` 返回公网 COS URL 是既有行为，小程序沿用，作为已知债登记 |
| Agent 会话 | 支持当前会话、历史恢复和加载更早消息。重命名、置顶和多版本由 Web 提供 |
| 绑定与账号 | 首登按 `wx_<openid>` 建号；`unionid` 只在绑定时回填，不做跨 AppID 账号合并 |
| 令牌续期开销 | 每次校验是一次 Redis `GET` + 一次 `EXPIRE`（滑动续期），不做批量或本地缓存；体验版量级可接受 |
| 流内 40100 | 分块流与 `success` 整包两条路径都认这个信封（`lib/authEnvelope.ts`），认出后静默重登并**重开一条流**。重开是安全的：40100 信封只在落库之前产生（令牌过滤器，或 `chatStream` 里的 `getLoginUser` / `claimOrValidateSession`），流开始后的错误走 SSE `event: error` 帧，不会被当成 40100 重试——不会在会话里留下两条用户消息。真机仍需确认 `enableChunked` 下两条路径各自的命中情况 |
| 协作控制器 | `CollaborationRunController` 直接读 Session，不走令牌通道；协作能力默认全关（`eye-diagnosis.collaboration.*`），本阶段不启用。启用前必须补令牌通道的同等校验 |
| 登出接口 | `/mp/auth/logout` 是写操作，令牌失效时会被过滤器回 40100；客户端按「服务端吊销失败也要清本地」处理（`logout()` 吞掉异常后清 storage）。服务端吊销与本地清理是两件事，这条口径是有意的 |

## 7. 真机验收清单（发布门禁 · 未执行）

> **状态：未执行。** 当前环境没有真实 AppID / AppSecret、没有微信开发者工具、没有可达的 HTTPS
> API 域名，也没有测试设备与合法测试账号。因此下列 14 项**全部未验证**——模拟器/构建产物跑通
> **不等于**真机验收。生产上线保持 BLOCKED，直到本清单全绿且每项留有截图或录屏。

前置条件（缺一不可）：

- [ ] 真实 AppID + AppSecret（服务端环境变量 `WECHAT_MINIAPP_APPID` / `WECHAT_MINIAPP_SECRET`，不入库）
- [ ] 后端 HTTPS 对外可达，并在公众平台后台配置 `request` / `uploadFile` 合法域名
- [ ] 至少一台测试设备 + 一个合法测试微信账号（加入体验成员，个人主体上限 15 人）
- [ ] 测试影像不来自真实患者（用公开眼底图或自拍非眼底图，后者应被拒识）

| # | 验收项 | 操作 | 通过标准 |
|---|---|---|---|
| 1 | 首登建号 | 首次进入小程序 → 自动登录 → 拍一张判读 → 杀掉小程序 → 再次进入 | 第二次进入仍进入同一账号（「我的」里账号与记录不变），没有新建账号 |
| 2 | 40100 静默重登 | 登录后在服务端删掉 `mp:token:*`（或在「我的」里手动退出后再请求），继续点判读/发送 | 自动重登一次并继续原操作；不出现「连接中断」这类误报；不出现重复的用户消息 |
| 3 | >2MB 压缩上传 | 选一张 >2MB 的 JPG，另选一张 >2MB 的 PNG | 都能压到 2MB 以内并成功判读；PNG 若 `compressImage` 失败，应走画布兜底而不是直接报错 |
| 4 | 画布兜底不可用 | 在低版本基础库或关闭离屏画布的机型上选 >2MB 图片 | 提示「请重拍或换一张更小的影像」，**不得**上传超限文件（服务端 2MB 硬限） |
| 5 | 取消判读 | 判读进行中点「取消本次判读」 | 如实显示三种结果之一（已取消 / 已完成 / 服务端已无此次判读）；取消后结果不落到新一次判读上 |
| 6 | 超时与网络中断 | 判读中切飞行模式；Agent 回答中切飞行模式 | 判读给出可读的错误与重试入口；Agent 30s 无分片或网络失败时提示中断，不把半截回答当完整回答 |
| 7 | 三种不给结论的结局 | 分别构造拒识（模糊图）、待复核、`patientConclusionAllowed=false` | 三者都不显示分类结论/概率/建议；显示对应原因与下一步；「已给出分类」才显示概率读数 |
| 8 | Agent 流式 | 问一个长回答的问题 | 逐字出现；`event: done` 后收起「正在思考」；只显示回答正文（推理/工具过程不进正文）；每条回复带「AI 生成，仅供参考」 |
| 9 | 断网恢复 | 流式回答中杀网络 30s 再恢复 | 提示中断；重新提问可用；会话历史里不出现重复的用户消息 |
| 10 | 历史恢复 | 同一会话聊到 >40 条后杀掉小程序再进，滚动到顶部后点「加载更早消息」，再切换会话 | 首屏包含最新的问答。更早的问答插入顶部，顺序正确、无重复，切换后显示当前会话的消息。有未落库终态时提示历史可能不完整 |
| 11 | 深浅色 + 多机型 | iOS 与 Android 各一台，系统切深/浅色 | 跟随系统、无闪烁、无硬编码色残留；严重度/结局标签始终带**文字**，颜色不是唯一信息载体 |
| 12 | 手动退出与重登 | 「我的」→ 退出登录 → 随便点一个功能 → 「重新登录」 | 退出后本次运行内不再自动登录；重新登录回到同一账号；退出前后 `web/` 端会话不受影响 |
| 13 | 隐私授权 | 首次点判读时拒绝隐私授权 → 再次点判读并同意 | 拒绝时不上传、提示可重新授权；同意后走平台授权框并继续；弹层先于任何信息采集 |
| 14 | web/ 回归 | 浏览器登录 `web/`，以及用 curl 发一个不带令牌的 POST | Cookie 会话照常；无令牌的 POST 仍被同源校验 403（`CROSS_ORIGIN_BLOCKED`）；带无效令牌的 GET 不受影响 |

## 8. 运维说明（部署前需知）

**已注销账号不会再自动复活。** 管理员在后台删除用户是逻辑删除（`t_user.isDelete=1`），
小程序侧按密码通道的同一口径拒绝登录（`40300 账号已注销，无法登录`）。这是有意的：
原来的「重新建号并改绑」会让「封禁 → 删除」的用户拿到一枚全新的、未封禁的账号。

要让某个微信账号重新获得身份（例如误删），删除绑定行后该微信下次登录会建一个新账号
（历史记录不会跟着迁移）：

```sql
-- 先确认要处理的是哪个账号
SELECT id, userId, appid, openid, createTime FROM t_user_wechat WHERE userId = ?;
-- 解绑：下一次静默登录按 openid 重新建号
DELETE FROM t_user_wechat WHERE id = ?;
```

