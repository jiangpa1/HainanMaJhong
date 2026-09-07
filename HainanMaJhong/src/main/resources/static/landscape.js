/* ============================================================
   LandscapeManager —— 虚拟横屏统一管理（v2）

   核心思路：
   - 用 JS 自动创建一个全屏容器 #game-stage 包裹页面所有元素；
   - 移动端点击“登录/开始”后，给 <html> 加 virtual-landscape 类；
   - CSS 在「竖屏持握」时把 #game-stage 旋转 90°（translate(-50%,-50%) rotate(90deg)），
     让游戏始终横屏显示，即便用户锁定了竖屏；
   - 物理转回横屏时 @media 不匹配，transform 自动恢复，无需 JS。

   桌面端：不包裹、不加类、不旋转，保持原样。
   触控坐标：浏览器会自动把 pointer 事件经 transform 映射到正确元素，无需手工修正。

   用法：
     <link rel="stylesheet" href="landscape.css">
     <script src="landscape.js"></script>
     <button data-landscape>进入游戏</button>   <!-- 绑定 enterGameMode -->
   ============================================================ */
(function (global, document) {
  'use strict';

  var DEFAULTS = {
    guideMessage: '请横放手机以获得最佳体验',
    guideSubMessage: '当前已使用虚拟横屏，旋转设备效果更佳',
    bindSelector: '[data-landscape]',
    storageKey: 'landscape-mode',
    extremeRatio: 100      // 竖屏高/宽比超过此值视为“极长屏”，提示横放
  };

  var ua = (global.navigator && global.navigator.userAgent) || '';
  var platform = (global.navigator && global.navigator.platform) || '';
  var maxTouch = (global.navigator && global.navigator.maxTouchPoints) || 0;

  var isIOS = /iPhone|iPad|iPod/i.test(ua) || (platform === 'MacIntel' && maxTouch > 1);
  var isAndroid = /Android/i.test(ua);

  function isMobile() {
    if (isIOS || isAndroid) { return true; }
    if (/Mobile|Android|Silk|webOS|BlackBerry/i.test(ua)) { return true; }
    return ('ontouchstart' in global) && Math.min(global.screen.width, global.screen.height) < 1024;
  }

  function isPortrait() {
    return global.innerHeight > global.innerWidth;
  }

  // 当前“横屏模式”下的逻辑视口尺寸（竖屏时旋转 90° 后宽高互换）
  function logicalViewport() {
    var vw = global.visualViewport ? global.visualViewport.width : global.innerWidth;
    var vh = global.visualViewport ? global.visualViewport.height : global.innerHeight;
    if (isPortrait()) { return { w: vh, h: vw }; }
    return { w: vw, h: vh };
  }

  // 计算缩放：scale = min(逻辑宽/设计宽, 逻辑高/设计高)，核心区等比例完整装入
  function applyScale(designW, designH) {
    var layer = document.querySelector('.scale-layer');
    if (!layer) { return; }
    var rootStyle = global.getComputedStyle(document.documentElement);
    designW = designW || parseFloat(rootStyle.getPropertyValue('--design-w')) || 1280;
    designH = designH || parseFloat(rootStyle.getPropertyValue('--design-h')) || 720;
    var vp = logicalViewport();
    var scale = Math.min(vp.w / designW, vp.h / designH);
    scale = Math.max(0.3, Math.min(scale, 1.5));
    layer.style.setProperty('--scale', scale.toFixed(4));
  }

  // 竖屏虚拟横屏时用像素显式给出 #game-app 尺寸/旋转，
  // 避免完全依赖 100dvh/100dvw（旧版 iOS Safari 不支持时会导致只显示部分画面且刷新也不恢复）。
  function sizeVirtualApp() {
    var app = document.getElementById('game-app');
    if (!app) { return; }
    var vl = document.documentElement.classList.contains('virtual-landscape');
    if (vl && isPortrait()) {
      var w = global.visualViewport ? global.visualViewport.height : global.innerHeight;
      var h = global.visualViewport ? global.visualViewport.width : global.innerWidth;
      app.style.left = '50%';
      app.style.top = '50%';
      app.style.width = Math.round(w) + 'px';
      app.style.height = Math.round(h) + 'px';
      app.style.webkitTransformOrigin = app.style.transformOrigin = 'center';
      app.style.webkitTransform = app.style.transform = 'translate(-50%,-50%) rotate(90deg)';
    } else {
      app.style.left = '';
      app.style.top = '';
      app.style.width = '';
      app.style.height = '';
      app.style.webkitTransform = '';
      app.style.transform = '';
      app.style.transformOrigin = '';
    }
  }

  // 统一重算：缩放 + 显式尺寸。入口跳转/全屏变化后多跑几次，避免“首次进页面算错、刷新才正常”。
  function refreshLayout() {
    applyScale();
    sizeVirtualApp();
  }

  function isFullscreen() {
    return !!(document.fullscreenElement ||
      document.webkitFullscreenElement ||
      document.mozFullScreenElement ||
      document.msFullscreenElement);
  }

  // ---------- 全屏 API（含前缀） ----------
  function requestFullscreen(el) {
    el = el || document.documentElement;
    if (el.requestFullscreen) { return el.requestFullscreen(); }
    if (el.webkitRequestFullscreen) { return el.webkitRequestFullscreen(); }
    if (el.webkitRequestFullScreen) { return el.webkitRequestFullScreen(); }
    if (el.mozRequestFullScreen) { return el.mozRequestFullScreen(); }
    if (el.msRequestFullscreen) { return el.msRequestFullscreen(); }
    return Promise.reject(new Error('fullscreen unsupported'));
  }

  function exitFullscreen() {
    if (document.exitFullscreen) { return document.exitFullscreen(); }
    if (document.webkitExitFullscreen) { return document.webkitExitFullscreen(); }
    if (document.webkitCancelFullScreen) { return document.webkitCancelFullScreen(); }
    if (document.mozCancelFullScreen) { return document.mozCancelFullScreen(); }
    if (document.msExitFullscreen) { return document.msExitFullscreen(); }
    return Promise.resolve();
  }

  // ---------- 方向锁定（可选增强，失败无妨） ----------
  function lockLandscape() {
    var so = global.screen.orientation ||
      global.screen.msOrientation ||
      global.screen.mozOrientation;
    if (so && typeof so.lock === 'function') {
      try { return so.lock('landscape'); } catch (e) { return Promise.reject(e); }
    }
    if (typeof global.screen.lockOrientation === 'function') {
      try {
        return global.screen.lockOrientation('landscape')
          ? Promise.resolve() : Promise.reject(new Error('lockOrientation failed'));
      } catch (e) { return Promise.reject(e); }
    }
    if (typeof global.screen.mozLockOrientation === 'function') {
      try {
        return global.screen.mozLockOrientation('landscape')
          ? Promise.resolve() : Promise.reject(new Error('mozLockOrientation failed'));
      } catch (e) { return Promise.reject(e); }
    }
    return Promise.reject(new Error('orientation lock unsupported'));
  }

  function unlockOrientation() {
    var so = global.screen.orientation ||
      global.screen.msOrientation ||
      global.screen.mozOrientation;
    if (so && typeof so.unlock === 'function') { try { so.unlock(); } catch (e) {} }
    if (typeof global.screen.unlockOrientation === 'function') { try { global.screen.unlockOrientation(); } catch (e) {} }
    if (typeof global.screen.mozUnlockOrientation === 'function') { try { global.screen.mozUnlockOrientation(); } catch (e) {} }
  }

  // ---------- 自动包裹：把 body 直接子元素搬进 #game-app ----------
  function wrapStage() {
    if (document.getElementById('game-app')) { return; }
    var body = document.body;
    var stage = document.createElement('div');
    stage.id = 'game-app';

    var nodes = [];
    for (var i = 0; i < body.childNodes.length; i++) { nodes.push(body.childNodes[i]); }

    for (var j = 0; j < nodes.length; j++) {
      var node = nodes[j];
      if (node.nodeType === 1 && node.tagName === 'SCRIPT') { continue; } // 脚本留在 body
      if (node === stage) { continue; }
      stage.appendChild(node);
    }
    body.appendChild(stage);
  }

  // ---------- localStorage 标记 ----------
  function hasMode() {
    try { return global.localStorage.getItem(DEFAULTS.storageKey) === '1'; } catch (e) { return false; }
  }
  function setMode(on) {
    try {
      if (on) { global.localStorage.setItem(DEFAULTS.storageKey, '1'); }
      else { global.localStorage.removeItem(DEFAULTS.storageKey); }
    } catch (e) {}
  }

  // ---------- 核心入口 ----------
  function enterGameMode() {
    if (!isMobile()) { return; }             // 桌面端保持原样
    wrapStage();
    setMode(true);
    document.documentElement.classList.add('virtual-landscape');
    // 试验：跳转/点按进入新页时不再向系统申请全屏或锁横屏。
    // 虚拟横屏(CSS 旋转)在页面加载时由 init() 自动生效，本函数只补一次重算。
    // 之前在此调用 requestFullscreen/lockLandscape → 用户手势发起全屏后立刻跳转，
    // 部分安卓上会卡住不跳转/新页不刷新。如有需要可改回：
    //   requestFullscreen(document.documentElement).catch(function () {});
    //   lockLandscape().catch(function () {});
    global.requestAnimationFrame(function () { refreshLayout(); });
    global.setTimeout(refreshLayout, 300);
  }

  function reset() {
    document.documentElement.classList.remove('virtual-landscape');
    unlockOrientation();
    exitFullscreen().catch(function () {});
    setMode(false);
    global.requestAnimationFrame(function () {
      global.dispatchEvent(new Event('resize'));
    });
  }

  // ---------- 事件 ----------
  function bind() {
    var els = document.querySelectorAll(DEFAULTS.bindSelector);
    for (var i = 0; i < els.length; i++) {
      (function (el) {
        el.addEventListener('click', function () { enterGameMode(); });
      })(els[i]);
    }
  }

  function init(options) {
    if (options) {
      for (var k in options) {
        if (options.hasOwnProperty(k)) { DEFAULTS[k] = options[k]; }
      }
    }
    if (isMobile()) {
      wrapStage();
      // 移动端强制开启虚拟横屏（无需用户点击）
      document.documentElement.classList.add('virtual-landscape');
    }
    bind();
    global.addEventListener('resize', refreshLayout);
    global.addEventListener('orientationchange', refreshLayout);
    if (global.visualViewport) {
      global.visualViewport.addEventListener('resize', refreshLayout);
      global.visualViewport.addEventListener('scroll', refreshLayout);
    }
    global.addEventListener('load', refreshLayout);
    global.addEventListener('fullscreenchange', refreshLayout);
    global.addEventListener('webkitfullscreenchange', refreshLayout);
    refreshLayout();
    global.requestAnimationFrame(function () { refreshLayout(); });
    global.setTimeout(refreshLayout, 250);
    global.setTimeout(refreshLayout, 800);
  }

  global.LandscapeManager = {
    init: init,
    enterGameMode: enterGameMode,
    reset: reset,
    wrapStage: wrapStage,
    applyScale: applyScale,
    refreshLayout: refreshLayout,
    isMobile: isMobile,
    isIOS: function () { return isIOS; },
    isPortrait: isPortrait,
    isFullscreen: isFullscreen
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () { init(); });
  } else {
    init();
  }

})(window, document);
