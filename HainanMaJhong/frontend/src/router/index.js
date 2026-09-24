import { createRouter, createWebHashHistory } from 'vue-router'

/*
 * 用 hash 路由（#/game）而不是 history 路由。
 *
 * 原因：页面由 Spring Boot 当静态资源托管，没有 SPA fallback。
 * history 模式下用户在 /game 刷新会直接 404（服务端找不到这个路径的文件）。
 * hash 模式下服务端永远只请求 index.html，路由完全由前端处理。
 */
const routes = [
  {
    path: '/',
    name: 'index',
    component: () => import('../views/Login.vue'),
    meta: { title: '登录' },
  },
  {
    path: '/lobby',
    name: 'lobby',
    component: () => import('../views/Lobby.vue'),
    meta: { title: '大厅' },
  },
  {
    path: '/room',
    name: 'room',
    component: () => import('../views/Room.vue'),
    meta: { title: '房间' },
  },
  {
    path: '/game',
    name: 'game',
    component: () => import('../views/GameTable.vue'),
    meta: { title: '牌桌' },
  },
  {
    path: '/records',
    name: 'records',
    component: () => import('../views/Records.vue'),
    meta: { title: '战绩' },
  },
  // 兜底：未知路径回登录页，避免白屏
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

router.afterEach((to) => {
  const t = to.meta && to.meta.title
  document.title = t ? `${t} · 海南麻将` : '海南麻将'
})

export default router
