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
  tabBar: {
    color: theme.light.tabFontColor,
    selectedColor: theme.light.tabSelectedColor,
    backgroundColor: theme.light.tabBgColor,
    borderStyle: theme.light.tabBorderStyle,
    list: [
      { pagePath: 'pages/diagnosis/index', text: '判读' },
      { pagePath: 'pages/records/index', text: '记录' },
      { pagePath: 'pages/agent/index', text: '咨询' },
      { pagePath: 'pages/profile/index', text: '我的' },
    ],
  },
  darkmode: true,
  themeLocation: 'theme.json',
  lazyCodeLoading: 'requiredComponents',
})
