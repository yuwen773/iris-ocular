import themeJson from './theme.json'

interface ThemePalette {
  navBgColor: string
  navTxtStyle: 'black' | 'white'
  bgColor: string
  bgTxtStyle: 'dark' | 'light'
  tabBgColor: string
  tabBorderStyle: 'black' | 'white'
  tabFontColor: string
  tabSelectedColor: string
}

// 色值来自生成的 theme.json（`npm run gen:tokens` 从 web/src/styles/tokens.css 生成），
// 这里不再手写十六进制：手写的那一份会和 web/ 的令牌漂移，还过得了 typecheck 和构建。
const theme = themeJson as { light: ThemePalette; dark: ThemePalette }

export default defineAppConfig({
  pages: [
    'pages/diagnosis/index',
    'pages/records/index',
    'pages/agent/index',
    'pages/profile/index',
    'pages/diagnosis/result',
  ],
  window: {
    backgroundTextStyle: theme.light.bgTxtStyle,
    // 浅色兜底值；`darkmode: true` 时系统切深色以 theme.json 的 dark 段为准。
    navigationBarBackgroundColor: theme.light.navBgColor,
    navigationBarTitleText: 'Iris 眼底影像判读',
    navigationBarTextStyle: theme.light.navTxtStyle,
    backgroundColor: theme.light.bgColor,
  },
  // tabBar 图标：`theme.json` 只覆盖颜色变量（就是上面那 8 个），图标路径不在其中——
  // 一套 PNG 要同时待在浅色白底和深色 `#17263d` 上。所以四枚图标同色 `#66799A`
  // （对白底 3.1:1、对 #17263D 3.4:1，都过 3:1 的非文本对比线），
  // 选中态靠**实心 vs 描边**区分，不靠色相；文字色随主题翻转。
  tabBar: {
    color: theme.light.tabFontColor,
    selectedColor: theme.light.tabSelectedColor,
    backgroundColor: theme.light.tabBgColor,
    borderStyle: theme.light.tabBorderStyle,
    list: [
      { pagePath: 'pages/diagnosis/index', text: '判读', iconPath: 'assets/tabbar/scan.png', selectedIconPath: 'assets/tabbar/scan-on.png' },
      { pagePath: 'pages/records/index', text: '记录', iconPath: 'assets/tabbar/record.png', selectedIconPath: 'assets/tabbar/record-on.png' },
      { pagePath: 'pages/agent/index', text: '咨询', iconPath: 'assets/tabbar/chat.png', selectedIconPath: 'assets/tabbar/chat-on.png' },
      { pagePath: 'pages/profile/index', text: '我的', iconPath: 'assets/tabbar/me.png', selectedIconPath: 'assets/tabbar/me-on.png' },
    ],
  },
  darkmode: true,
  themeLocation: 'theme.json',
  lazyCodeLoading: 'requiredComponents',
})
