/** 后端地址。构建时由 config/index.ts 的 defineConstants 注入，默认本机 8081。 */
export const API_BASE = (process.env.TARO_APP_API_BASE ?? 'http://127.0.0.1:8081/api').replace(/\/+$/, '')
