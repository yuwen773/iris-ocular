import { defineConfig, type UserConfigExport } from '@tarojs/cli'
import devConfig from './dev'
import prodConfig from './prod'

/**
 * Taro 构建配置。
 *
 * 只构建微信小程序（weapp），不生成 H5 —— 这个客户端拿不到 web/ 的组件，
 * 多留一个平台只会多一份没人验证的产物。
 */
export default defineConfig<'webpack5'>(async (merge) => {
  const baseConfig: UserConfigExport<'webpack5'> = {
    projectName: 'iris-miniapp',
    date: '2026-11-23',
    // 微信设计稿宽度：750rpx = 屏幕宽。
    designWidth: 750,
    deviceRatio: {
      640: 2.34 / 2,
      750: 1,
      375: 2,
      828: 1.81 / 2,
    },
    sourceRoot: 'src',
    outputRoot: 'dist',
    plugins: [],
    defineConstants: {
      // 后端地址在构建时注入：小程序没有同源概念，必须写全。
      'process.env.TARO_APP_API_BASE': JSON.stringify(
        process.env.TARO_APP_API_BASE || 'http://127.0.0.1:8081/api',
      ),
    },
    // tabBar 图标只在 app.config.ts 里以字符串路径出现，走不到 webpack 的资源处理，
    // 必须原样拷进产物（app.json 里的 `assets/tabbar/*.png` 指向 dist/ 下的这一份）。
    copy: {
      patterns: [{ from: 'src/assets', to: 'dist/assets' }],
      options: {},
    },
    framework: 'react',
    compiler: {
      type: 'webpack5',
      prebundle: { enable: false },
    },
    cache: { enable: false },
    mini: {
      postcss: {
        pxtransform: {
          enable: true,
          config: {},
        },
        cssModules: {
          enable: false,
        },
      },
    },
    alias: {
      '@': require('path').resolve(__dirname, '..', 'src'),
    },
  }

  if (process.env.NODE_ENV === 'development') {
    return merge({}, baseConfig, devConfig)
  }
  return merge({}, baseConfig, prodConfig)
})
