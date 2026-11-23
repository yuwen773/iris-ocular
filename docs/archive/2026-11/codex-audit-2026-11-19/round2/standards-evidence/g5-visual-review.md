# Iris RC G-5 独立视觉复核

> **入库说明（2026-11-22）**：本文引用的截图（`candidate-visual/`、`system-states/`、`workbench/*.png`）与 `workbench.log` 未随报告入库；入库的是文字结论与 `workbench/*.json` 读数。


**G-5 OPEN**。候选源码 `03b14efd8f9bcc8d6326a104396d8b68a30d5e8b`。新增图来自 Windows Chromium 的 Vite dev 本地运行及合成接口响应。发布构建缺少对应视觉证据链。首轮素材含加载中的页面，不能覆盖发布界面的稳定态。

## 首轮 72 张

逐张查看首屏区域，八张联系表与含像素尺寸、文件 SHA-256 的 [图像清单](visual-sheets/round1-inventory.csv) 保存在本目录。首轮报告与旧 Vite 日志可定位到 `7dc3422b`。测试源断言了 390、768、820、900、1440、1920 × 亮暗，实际只在 **390、900、1440** 记录 PNG，视口高度 900，原图全页输出，旧构建未发现内容摘要。

- 首屏、Agent 空态、资讯、判读、记录、个人资料及管理端六个入口均逐张检查。首轮 Agent 是欢迎页，缺少打开的资源侧栏。
- Dashboard 亮色三图均停在全页加载层，暗色只显示壳和加载文案。管理数据分析有全页加载图，用户、诊断和知识库多图仍是占位骨架。缺少打开的 PROVENANCE 和协作工作台。现有 freeze 脚本固定等待 400ms，断言主题、溢出和异常，缺少业务组件稳定标志。
- 首轮亮色 1440 首页未见棱镜，且原 72 张缺少 E-09 必看 820、1920 的像素。不能据缩略图把此视为当前候选的回归。

## 候选重点场景

1. E-09 首页。补齐 390、820、1440、1920 × 亮暗。逐档查看代表主视觉截图，棱镜具有可辨认轮廓，手机端棱镜置于信息卡和入口之间，桌面棱镜与主 CTA、信息卡分离。第一次暗色 1920 等待超时，独立重试成功。合成登录和软件 WebGL，不形成生产构建一致性结论。[截图与视口清单](candidate-visual/manifest.json)
2. 管理端 PROVENANCE。亮暗均查看 390、1440 的真实打开抽屉，授权长句在手机端正常折行，抽屉区分草稿审核、患者授权、账号角色来源，凭据详情隐藏。使用受限授权的合成记录。[亮色 390](candidate-visual/admin-provenance-light-390.png) · [暗色 1440](candidate-visual/admin-provenance-dark-1440.png)
3. Agent 资源侧栏。查看 1440 亮暗与 375 亮色抽屉，知识卡、文档编号、相关度、定位执行步骤与计数可见。原 R3 采图的历史附件断言使用旧数据形状，截图仅证明相应侧栏状态可渲染。候选帧与身份归属按主审查报告记证。[亮色桌面](../browser-resources/light-resources-panel-1440.jpg) · [暗色桌面](../browser-resources/dark-resources-panel-1440.jpg) · [移动抽屉](../browser-resources/light-resources-drawer-375.jpg)
4. System States。亮暗的 375 登录 5xx 可辨识表单级提示、重试和保留输入。资讯首次 502 将故障限制于内容区，导航保留。自动化 14 条与交付 6 条通过。[亮色 375](system-states/login-5xx-light-375.png) · [暗色 375](system-states/login-5xx-dark-375.png) · [暗色资讯 1440](system-states/news-first-502-dark-1440.png)
5. 多 Agent 工作台。临时 Vite dev 仅为夹具开启 `VITE_COLLABORATION_ENABLED=true`。18/18 通过，亮暗 × 360、390、768、820、900、1024、1280、1440、1920。查看完成态的移动和桌面、以及移动证据面板，桌面有成员、会话、证据三栏，移动将证据放入可关闭的面板，明确区分 AI 协作完成和医生待复核。[执行记录](workbench.log) · [亮色移动](workbench/completed-light-390.png) · [暗色桌面](workbench/completed-dark-1440.png) · [暗色移动证据](workbench/inspector-dark-390.png)

G-5 的剩余证据应在发布构建上采集，记录源码与构建摘要，等待数据就绪并断言关键组件，保留冻结基线和候选的同视口稳定态。浏览器溢出与断言通过只支持这些已执行的检查。
