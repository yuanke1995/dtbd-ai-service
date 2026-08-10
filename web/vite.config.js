import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5800,
    strictPort: true,
    proxy: {
      // 前端调 /proxy/**，转发到 AI 服务 http://localhost:8090/ai/**
      '/proxy': {
        target: 'http://localhost:8090/ai',
        changeOrigin: true,
        rewrite: path => path.replace(/^\/proxy/, '')
      }
    }
  }
})