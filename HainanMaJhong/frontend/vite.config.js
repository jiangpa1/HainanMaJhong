import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

/*
 * 构建产物直接写进后端模块的 static 目录，由 Spring Boot 原样托管。
 *
 * 几个关键取舍（都是踩过的坑，改之前先读）：
 *
 * 1. emptyOutDir: false —— outDir 在 frontend/ 之外，Vite 默认会警告并
 *    【清空目标目录】。static/ 里还有 img/、music/、apk/、landscape.* 这些
 *    不在前端构建范围内的文件，一旦被清掉就是数据丢失。所以必须显式关掉。
 *    代价：旧构建产物（assets/ 下的 hash 文件名）会残留累积，需要时手工清理。
 *
 * 2. base: './' 没必要，服务端挂在根路径 / 下，用默认的 '/' 即可，
 *    否则 hash 路由 + 相对路径容易在子路径下 404。
 *
 * 3. 不用 history 路由而用 hash 路由（见 router/index.js）：
 *    Spring 的静态资源没有 SPA fallback，history 模式下刷新 /game 会 404。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: false,
    // 出问题时要能在浏览器里读懂报错，不要压成一行
    sourcemap: false,
    chunkSizeWarningLimit: 1200,
  },
  server: {
    port: 5173,
    // 开发时前端独立跑，接口与 WebSocket 都代理到后端 8080
    proxy: {
      '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true },
      '/room': { target: 'ws://127.0.0.1:8080', ws: true },
      '/game': { target: 'ws://127.0.0.1:8080', ws: true },
      '/img': { target: 'http://127.0.0.1:8080', changeOrigin: true },
      '/music': { target: 'http://127.0.0.1:8080', changeOrigin: true },
    },
  },
})
