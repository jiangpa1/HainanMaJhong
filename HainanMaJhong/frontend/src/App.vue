<script setup>
/*
 * 应用根。
 *
 * ── 为什么这里要包一层 #game-app ──
 * 旧版 static/landscape.js 会在移动端用 wrapStage() 把 body 的所有子元素搬进
 * #game-app，再给 <html> 加 virtual-landscape，由 landscape.css 把这一层整体
 * rotate(90deg)。这里不需要 JS 搬 DOM —— 直接把 #game-app 写在模板里，
 * 顺带避免了 wrapStage 破坏 `body > xxx` 选择器的问题（旧版 records.html 就得
 * 专门补 `#game-app > header/main` 的规则）。
 *
 * ── 谁负责什么 ──
 *   #game-app（本文件 + landscape.css）—— 旋转 + 安全区 + 铺满屏幕
 *   .scale-layer（LandscapeLayer.vue）—— 仅牌桌：1280x720 等比缩放
 *   登录/大厅/房间/战绩是普通文档流，套 1280x720 缩放反而会出现
 *   "内容比屏幕大却无法滚动"，所以它们只吃 #game-app 的旋转与铺满。
 */
import { useRoute } from 'vue-router'
import { useLandscapeScale } from './composables/useLandscapeScale.js'

// 启动全局虚拟横屏管理器（模块级单例，重复调用不会重复挂监听）
useLandscapeScale()

const route = useRoute()
</script>

<template>
  <!--
    key 必须带上 fullPath，不能只靠组件名。
    vue-router 默认在「同一个组件、不同 query」时【不重建】组件实例 ——
    于是 /game?code=A 跳到 /game?code=B 时，GameTable 的 onMounted 不会再跑，
    store.connect() 不会被重新调用，牌桌还连着旧房间。
    用 fullPath 作 key 就会强制重新挂载，连接与牌局状态一起重置。
  -->
  <div id="game-app">
    <router-view :key="route.fullPath" />
  </div>
</template>
