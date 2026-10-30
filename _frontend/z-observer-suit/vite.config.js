import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import {safeEmptyOutDir} from '../../build-tools/safe-vite-plugin.mjs'

// 相对路径的 alias replacement 在 build 能解析、dev 不解析（dev 侧按 importer 目录找），
// 统一用绝对路径，两边行为才一致
const componentSrc = fileURLToPath(new URL('../z-observer-component/src', import.meta.url))

// LOCAL_SIBLINGS=1 走源码 alias，不用先 npm link / 发版就能改
export default defineConfig({
  plugins: [safeEmptyOutDir(), react()],
  resolve: {
    dedupe: ['react', 'react-dom', 'react-router-dom', 'antd', '@ant-design/icons', 'axios'],
    alias: process.env.LOCAL_SIBLINGS === '1'
      ? [
          // 精确到文件：只把包名 alias 到 src 目录时，'./pages' 会解析成
          // src/pages/ 这个目录而不是 src/pages.js，Vite 直接报 EISDIR
          { find: '@yuku123/z-observer-component/pages', replacement: `${componentSrc}/pages.js` },
          { find: '@yuku123/z-observer-component', replacement: componentSrc },
        ]
      : [],
  },
  server: {
    port: 3020,
    fs: { allow: ['..'] },
    // 只代理 /api/：SPA 路由是 /z-observer/*，与后端前缀彻底分开。
    // 早前踩过的坑是代理 '/logs' 这种裸路径，结果刷新页面拿到的永远是 JSON。
    proxy: {
      // 目标端口 = 收端 HTTP 端口（z-observer-admin/src/main/resources/application.yml 的 server.port，
      // 默认 8080）。写死成别的端口 ⇒ 代理指向一个没人监听的端口 ⇒ 看板所有 /api/ 请求全 502。
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
})
