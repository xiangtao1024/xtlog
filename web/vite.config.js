import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 后端 xtboot 的静态资源前缀固定为 /static（classpath:/static），
// 所以构建产物必须挂在 /static 下；SPA 回退由后端 DispatcherHandler 提供（server.spa.enabled=true），
// 故可以使用 history 路由，刷新子路由不会 404。
export default defineConfig({
  plugins: [vue()],
  base: '/static/',
  build: {
    // 直接产出到后端资源目录：mvn package 时自动打进 fat jar，无需二次拷贝
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
    chunkSizeWarningLimit: 2000
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:18080',
        changeOrigin: true
      }
    }
  }
})
