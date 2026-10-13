import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 只出 ESM：组件层被 suit 直接 alias 到 src 消费，也会被 z-opc 主壳按 npm 包消费，
// 不需要 UMD/IIFE 那套
export default defineConfig({
  plugins: [react()],
  build: {
    lib: { entry: 'src/index.js', formats: ['es'], fileName: () => 'index.js' },
    rollupOptions: {
      external: [
        'react', 'react-dom', 'react-router-dom',
        'antd', '@ant-design/icons', 'axios', 'dayjs',
        '@yuku123/z-frontend-common',
      ],
    },
  },
})
