import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 只出 ESM：组件层被 suit 直接 alias 到 src 消费，也会被 z-opc 主壳按 npm 包消费，
// 不需要 UMD/IIFE 那套
export default defineConfig({
  plugins: [react()],
  build: {
    lib: {
      // 两个 entry 对应 exports 两张脸: './dist/index.js'(.) 与 './dist/pages.js'(./pages)。
      // 只声明 index 时产物里没有 pages.js, 而 exports 还指着它 —— 宿主 import
      // '@yuku123/z-observer-component/pages' 直接 module not found。
      entry: { index: 'src/index.js', pages: 'src/pages.js' },
      formats: ['es'],
    },
    rollupOptions: {
      // rollup 的 external 是精确字符串匹配，不是前缀匹配：只写主桶的话，
      // 源码里的 '@yuku123/z-frontend-common/ui' 会被当普通依赖打包，基座 UI 组件
      // 被复制进本包 dist（打破「共享基座单一真源」+ 包体膨胀）。子路径须逐个列出。
      external: [
        'react', 'react-dom', 'react-router-dom',
        'antd', '@ant-design/icons', 'axios', 'dayjs',
        '@yuku123/z-frontend-common',
        '@yuku123/z-frontend-common/ui', '@yuku123/z-frontend-common/ui/echarts',
      ],
    },
  },
})
