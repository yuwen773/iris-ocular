# Iris 小程序（患者端）

Taro 4 + React + TypeScript 的微信小程序，覆盖「拍照 → 判读 → 结果 → 记录 → 咨询」。
范围、约束、架构、联调步骤与未决风险只有一个入口：`../docs/product/wechat-miniapp/README.md`。
命令看 `package.json`（`gen:tokens` / `dev:weapp` / `build:weapp` / `typecheck` / `test`），
本文件不转抄它们。

## 本目录自己的约定

- `src/styles/tokens.scss` 是生成文件（`scripts/gen-tokens.mjs` 从 `web/src/styles/tokens.css` 生成），
  不要手改；改令牌去 `web/src/styles/tokens.css`。CI 会核对生成结果与提交是否一致。
- 设计按 Clinical 场景：白纸、墨字、发丝线、零圆角；颜色只做语义、只描边不铺底，严重度永远带文字。
- `src/lib/sse.ts`、`src/lib/utf8.ts`、`src/lib/domain.ts` 是纯函数，改动必须补 `tests/` 单测。
- `.npmrc` 的 `legacy-peer-deps=true` 是为了绕开 Taro 与 vitest 的 vite peer 冲突；
  `webpack` 钉在 `5.91.0` 是 Taro 的精确依赖，升级前先确认 Taro 侧同步（否则 `ProgressPlugin` 报未知属性）。
- `project.config.json` 里的 AppID 是占位值，真机联调前替换。
