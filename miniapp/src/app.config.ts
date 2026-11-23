export default defineAppConfig({
  pages: [
    'pages/diagnosis/index',
    'pages/records/index',
    'pages/agent/index',
    'pages/profile/index',
    'pages/diagnosis/result',
  ],
  window: {
    backgroundTextStyle: 'dark',
    // 这里的色值是「浅色兜底」：`darkmode: true` 时以生成的 `src/theme.json` 为准
    //（`npm run gen:tokens` 从 web/src/styles/tokens.css 生成，勿手改 theme.json）。
    navigationBarBackgroundColor: '#f3f4f1',
    navigationBarTitleText: 'Iris 眼底影像判读',
    navigationBarTextStyle: 'black',
    backgroundColor: '#f3f4f1',
  },
  tabBar: {
    color: '#5c6367',
    selectedColor: '#14171a',
    backgroundColor: '#ffffff',
    borderStyle: 'white',
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
