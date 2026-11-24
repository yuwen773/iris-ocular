# Iris 小程序（患者端）

Taro 4 + React + TypeScript 的微信小程序，覆盖「拍照 → 判读 → 结果 → 记录 → 咨询」。
范围、约束、架构、联调步骤与未决风险只有一个入口：`../docs/product/wechat-miniapp/README.md`。
命令看 `package.json`（`gen:tokens` / `dev:weapp` / `build:weapp` / `typecheck` / `test`），
本文件不转抄它们。

## 本目录自己的约定

- `src/styles/tokens.scss` 是生成文件（`scripts/gen-tokens.mjs` 从 `web/src/styles/tokens.css` 生成），
  不要手改；改令牌去 `web/src/styles/tokens.css`。CI 会核对生成结果与提交是否一致。
- 设计画布是**品牌画布**（浅 `#f2f6fc` / 深 `#0b1425`），主操作钴蓝 `--brand-primary`
  （深色自动翻成 `#85b5ff`），单子是浮在画布上的一层纸；骨白 + 墨色是 `RecordSheet`
  那张报告纸的语言，不是整站底色。Clinical 的部分照旧：零圆角、颜色只做语义、
  只描边不铺底、严重度永远带文字。写样式只引用 `--brand-*`，不要用 `--field` / `--ink` 那一套纸面令牌。
- `src/theme.json` 也是生成的（同上），导航栏与 tabBar 的色值跟着 `--brand-*` 走，不要手改。
- tabBar 图标是 `src/assets/tabbar/*.png`（81×81，描边 + 实心两态），由
  `scripts/gen-tabbar-icons.py` 生成（需要 Pillow）。一套图标同色 `#66799A`，
  因为它要同时压在浅色 `#ffffff` 和深色 `#17263d` 上：`theme.json` 只覆盖颜色变量，
  图标路径不随主题切换，所以选中态靠实心/描边区分，不靠色相
  （对白底 3.1:1、对 #17263d 3.4:1，两边都过 3:1）。
- 图片两个去处：`src/assets/` 只放需要**原样拷进产物**的文件（tabBar 图标，靠 `config/index.ts` 的 copy 规则）；
  页面里 `import` 的图放 `src/images/`，交给打包器处理（现在只有判读页那张示例眼底照）。
- `src/lib/sse.ts`、`src/lib/utf8.ts`、`src/lib/domain.ts`、`src/lib/citations.ts`、
  `src/lib/recordFormat.ts` 是纯函数，改动必须补 `tests/` 单测。
- 咨询正文的证据引用：键只认 `documentId#chunkIndex`，且必须出现在本轮**真实检索到的**
  资源里（成功状态的工具终态帧 / 历史里的 tool-result 行）才画角标；核验不出来的那一处
  留在正文里标成「未核验」，不悄悄抹掉。玩家自己打的字原样显示，不做引用标注。
- `.npmrc` 的 `legacy-peer-deps=true` 是为了绕开 Taro 与 vitest 的 vite peer 冲突；
  `webpack` 钉在 `5.91.0` 是 Taro 的精确依赖，升级前先确认 Taro 侧同步（否则 `ProgressPlugin` 报未知属性）。
- `project.config.json` 里的 AppID 是占位值，真机联调前替换。
