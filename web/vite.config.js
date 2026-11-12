import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发代理：前端 5173 → Java 8080，避免跨域
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/auth': 'http://127.0.0.1:8080',
      '/sessions': 'http://127.0.0.1:8080',
      '/agent': 'http://127.0.0.1:8080',
      '/qa': 'http://127.0.0.1:8080',
      '/search': 'http://127.0.0.1:8080',
      '/documents': 'http://127.0.0.1:8080',
      '/charts': 'http://127.0.0.1:8080'
    }
  }
})
