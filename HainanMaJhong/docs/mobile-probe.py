#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
手机适配（虚拟横屏）探针：用无头 Chrome 以【手机 UA + 竖屏窗口】渲染真实构建产物，
量出 #game-app 的旋转、固定框是否完整装入、以及触点能否落到正确的元素上。

为什么必须单独有它：
  docs/browser-probe.py 用的是 1280x720 桌面窗口 —— 桌面端【不旋转、不包裹】，
  虚拟横屏那条分支一次都没被跑到。而"旋转画面 + 固定框"恰恰是手机上唯一会走的路径，
  没有量过就等于没验过（用户反复退回的原因就在这）。

它验什么（都是真实像素，不是推理）：
  1. <html> 是否带上 virtual-landscape；
  2. #game-app 是否被显式像素尺寸 + rotate(90deg) 摆成"铺满物理屏幕"；
  3. 1280x720 的 .scale-layer 缩放后是否完整落在屏幕内（不裁切、不越界）；
  4. 页面根元素是否自动填满固定框（没被 100vh 顶出去）、内部滚动区是否还能滚；
  5. 触点命中：拿元素的视觉中心去 elementFromPoint，必须命中它自己 ——
     这条直接证明"浏览器把触摸坐标经 transform 反算"，手机上点得到。

复用 browser-probe.py 的 SHIM（WebSocket 打桩 + 登录态 + 合成牌局），
只是把它的桌面测量整段让开（SHIM 里 if (window.__HN_MOBILE__) return）。

用法：
  python docs/mobile-probe.py [--keep] [--shot out.png] [--wait 8000]
"""

import argparse
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))

# Windows 上 stdout 默认跟控制台代码页走（cp936），断言消息里有 emoji 时
# print 会抛 UnicodeEncodeError，看起来像探针崩了、其实只是打印失败。
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

# 文件名是 browser-probe.py（带连字符），不能直接 import，只能按路径加载。
# 里面的 main() 有 __main__ 守卫，加载它不会跑探针。
_spec = importlib.util.spec_from_file_location("browser_probe", os.path.join(HERE, "browser-probe.py"))
bp = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(bp)

STATIC = bp.STATIC

# 手机 UA：必须让 useLandscapeScale.isMobile() 判定为移动端，
# 否则走的是桌面分支（不旋转），探针会误判为"没有适配"。
MOBILE_UA = (
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
)

# 探针预置的"记住我"存档：Python 注入给 shim，断言再用同一份常量的值比对
REMEMBERED_USER = "hnremember001"
REMEMBERED_PASS = "Test123456"
# 工信部备案号（登录页/大厅底部都要有）
BEIAN_TEXT = "琼ICP备2026013323号"
BEIAN_HREF = "https://beian.miit.gov.cn/"
# 手机竖屏用的紧凑设计高度（与 useLandscapeScale.js 的 COMPACT_DESIGN_H 一致）
COMPACT_H = 590
# 桌面/物理横屏的标准设计高度
FULL_H = 720

# ---------------------------------------------------------------------------
# 手机测量脚本（插在 SHIM 之后）
# ---------------------------------------------------------------------------
MOBILE_SHIM = r"""
<script>
(function () {
  // 让 browser-probe 的桌面测量整段让开（它在 shim 里读这个标记）
  window.__HN_MOBILE__ = true;

  /*
   * 预置"记住我"的存档，用来验登录页的两条需求：
   *   1. 用户名默认就记住（进来应当已经预填）
   *   2. 勾了「记住密码」时密码也预填
   * 值由 Python 侧通过 window.__HN_REMEMBER__ 注入（保证两边是同一份数据），
   * 且必须在应用启动前写，登录页 setup 时才会读到。
   */
  try {
    localStorage.setItem('hnLoginRemember', JSON.stringify(window.__HN_REMEMBER__));
  } catch (e) {}
  function rect(el) {
    var r = el.getBoundingClientRect();
    return {
      x: Math.round(r.left), y: Math.round(r.top),
      w: Math.round(r.width), h: Math.round(r.height),
      right: Math.round(r.right), bottom: Math.round(r.bottom),
    };
  }

  function cls(el) { return String((el && el.className) || (el && el.tagName) || ''); }

  /*
   * 牌桌各功能区的【设计坐标】范围（1280x720 那套）。
   *
   * 目的：判断"手机上能不能把设计框压矮一点"——牌桌是等比缩放装进固定框的，
   * 竖屏手机的框比 16:9 更宽，所以缩放系数是被【设计高度】卡住的
   * （scale = 框高 / 720）。只要设计高度能压到更小，整体就能等比变大，
   * 且不裁切、不变形。这里先量清楚 720 的高度到底花在哪。
   *
   * 90° 顺时针旋转下的换算：screen_y ↔ 设计 u（宽方向），screen_x ↔ 设计 v（高方向）。
   */
  function designZones(layer, scale) {
    if (!layer || !scale) return null;
    var l = layer.getBoundingClientRect();
    function box(sel) {
      var el = layer.querySelector(sel);
      if (!el) return null;
      var r = el.getBoundingClientRect();
      if (r.width <= 0 || r.height <= 0) return null;
      var u0 = (r.top - l.top) / scale;
      var u1 = (r.bottom - l.top) / scale;
      var v0 = (l.right - r.right) / scale;
      var v1 = (l.right - r.left) / scale;
      return { u: [Math.round(u0), Math.round(u1)], v: [Math.round(v0), Math.round(v1)] };
    }
    var out = {};
    ['.table__top', '.seat--top', '.table__center', '.seat--left', '.seat--right',
     '.table__mymelds', '.table__hand', '.table__bottom', '.table__actions',
     '.table__chatlog', '.table__chat'].forEach(function (sel) {
      var b = box(sel);
      if (b) out[sel] = b;
    });
    // 牌河里的牌（散在中央区，是"能不能把设计框压矮"的主要风险点：
    // 压矮会让我的副露/手牌上移，可能压到牌河上）。
    // ⚠️ 逐张返回，不要只给外接矩形 —— 牌河是四组散牌，外接框里大部分是空的，
    //    拿外接框判相交会假报（第一版就是这么误报了 2 项）。
    var river = layer.querySelectorAll('.table__center .tile');
    var tiles = [];
    for (var i = 0; i < river.length; i++) {
      var r = river[i].getBoundingClientRect();
      if (r.width <= 0) continue;
      tiles.push({
        u: [Math.round((r.top - l.top) / scale), Math.round((r.bottom - l.top) / scale)],
        v: [Math.round((l.right - r.right) / scale), Math.round((l.right - r.left) / scale)],
      });
    }
    if (tiles.length) out['.table__center .tile'] = tiles;
    return out;
  }

  /* --zoom：把算好的 --scale 乘一个倍数，用来"预览"放大会裁掉什么（不改应用代码）。
     必须用带 !important 的样式表写，不能改内联样式 —— 截图时 Chrome 会重排一次页面，
     应用收到 resize 后会自己重算 --scale，把内联覆盖冲掉（第一次就是这么白跑一趟的）。 */
  function applyZoom() {
    var z = window.__HN_ZOOM__;
    if (!z || z === 1) return;
    var base = parseFloat(window.getComputedStyle(document.documentElement).getPropertyValue('--scale')) || 1;
    var next = (base * z).toFixed(4);
    var st = document.createElement('style');
    st.textContent = ':root{--scale:' + next + ' !important}.scale-layer{--scale:' + next + ' !important}';
    document.head.appendChild(st);
  }

  function currentScale() {
    return parseFloat(window.getComputedStyle(document.documentElement).getPropertyValue('--scale')) || 1;
  }

  /*
   * 屏幕坐标 → 设计坐标（1280 x --design-h 那一套）。
   * 90° 顺时针旋转下：screen y ↔ 设计 u，screen x ↔ 设计 v（从右边往左递增）。
   * 判断"谁在谁上面/左边"必须用设计坐标 —— 旋转后屏幕的上下左右和设计坐标是错位的。
   */
  function toDesign(r, layer, scale) {
    if (!r || !layer || !scale) return null;
    var l = layer.getBoundingClientRect();
    return {
      u: [Math.round((r.y - l.top) / scale), Math.round((r.bottom - l.top) / scale)],
      v: [Math.round((l.right - r.right) / scale), Math.round((l.right - r.x) / scale)],
    };
  }

  /* 牌桌上几个"会互相挤"的模块：同时给屏幕矩形和设计坐标，用来断言互不重叠 */
  function tableExtras() {
    var layer = document.querySelector('.scale-layer');
    var scale = currentScale();
    function box(sel) {
      var el = document.querySelector(sel);
      if (!el) return null;
      var r = rect(el);
      r.design = toDesign(r, layer, scale);
      return r;
    }
    var hand = document.querySelector('.table__hand .tile');
    var hu = document.querySelector('.hu-banner');
    var self = document.querySelector('.selfname');
    var ht = hand ? rect(hand) : null;
    if (ht) ht.design = toDesign(ht, layer, scale);
    // 操作栏每个键的宽度：手机上要求"过/返回"和吃碰杠胡一律等宽
    var btns = [];
    var list = document.querySelectorAll('.table__actions .actbtn');
    for (var i = 0; i < list.length; i++) {
      var br = rect(list[i]);
      br.design = toDesign(br, layer, scale);
      br.label = (list[i].textContent || '').replace(/\s+/g, '').slice(0, 4);
      btns.push(br);
    }
    return {
      handTile: ht,
      handBox: box('.table__hand'),
      meldsBox: box('.table__mymelds'),
      chatBox: box('.table__bottom'),
      chatLogBox: box('.table__chatlog'),
      actionBar: box('.table__actions .actionbar'),
      actBtns: btns,
      huText: hu ? (hu.textContent || '').replace(/\s+/g, ' ').trim() : null,
      selfName: self ? (self.textContent || '').replace(/\s+/g, ' ').trim() : null,
    };
  }

  /* 改 hash 会让 router-view 整块重新挂载，等 DOM 真的出现再量/再点，
     否则量到的是上一屏、点到的是已经不存在的按钮。 */
  function waitFor(sel, cb, tries) {
    var n = tries == null ? 30 : tries;
    (function poll() {
      var el = document.querySelector(sel);
      if (el || n <= 0) return cb(el);
      n--;
      setTimeout(poll, 50);
    })();
  }

  /*
   * 牌桌内容实际占了固定框的多少（用来决定"手机上还能放大多少"）。
   *
   * 只统计【有信息量的元素】（牌、副露、名牌、按钮/输入框），不含 .table 这种撑满
   * 整个框的背景容器 —— 拿背景算就永远是 100%，看不出余量。
   *
   * 注意：这里比的是【屏幕坐标】。固定框和内容被同一个 transform 缩放/旋转，
   * 所以两者的比例与缩放系数无关，直接就能换算成"还能放大几倍"。
   */
  function contentBox(layer) {
    if (!layer) return null;
    var sel = '.tile, .meldrow, .meldrow__group, .seatpanel__meta, .seatpanel__backs,' +
              ' .seatpanel__bubble, .table button, .table__bottom button, .table input,' +
              ' .table__mybubble, .center-pop, .hu-banner, .bao-toast';
    var els = layer.querySelectorAll(sel);
    if (!els.length) return null;
    var l = layer.getBoundingClientRect();
    var x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    for (var i = 0; i < els.length; i++) {
      var r = els[i].getBoundingClientRect();
      if (r.width <= 0 || r.height <= 0) continue;
      if (r.left < x0) x0 = r.left;
      if (r.top < y0) y0 = r.top;
      if (r.right > x1) x1 = r.right;
      if (r.bottom > y1) y1 = r.bottom;
    }
    if (!isFinite(x0)) return null;
    var cw = x1 - x0, ch = y1 - y0;
    var cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
    var lcx = l.left + l.width / 2, lcy = l.top + l.height / 2;
    // 以框中心为基准放大 z 倍时，内容四边都不能越过框：
    //   左/上限制 = 2 * (内容中心 - 框左边) / 内容宽，右/下同理，取最小
    var limL = cw <= 0 ? 99 : 2 * (cx - l.left) / cw;
    var limR = cw <= 0 ? 99 : 2 * (l.right - cx) / cw;
    var limT = ch <= 0 ? 99 : 2 * (cy - l.top) / ch;
    var limB = ch <= 0 ? 99 : 2 * (l.bottom - cy) / ch;
    return {
      layer: { w: Math.round(l.width), h: Math.round(l.height) },
      content: { w: Math.round(cw), h: Math.round(ch), x: Math.round(x0), y: Math.round(y0) },
      fillX: Number((cw / l.width).toFixed(4)),
      fillY: Number((ch / l.height).toFixed(4)),
      maxZoom: Number(Math.min(limL, limR, limT, limB).toFixed(4)),
      count: els.length,
    };
  }

  function clickByText(sel, text) {
    var el = Array.prototype.find.call(document.querySelectorAll(sel), function (b) {
      return (b.textContent || '').indexOf(text) >= 0;
    });
    if (el) el.click();
    return !!el;
  }

  /* 触点是按【视觉坐标】给的：浏览器自己会把 pointer 事件经 transform 反算。
     拿元素视觉中心去 elementFromPoint，命中自己（或自己的后代）才算点得到。

     例外：元素被某个 overflow 祖先裁掉（登录卡片在矮屏上要滚动就是这种情况），
     此时视觉中心落在裁切区外，elementFromPoint 命中的是别的东西 ——
     这不是布局错，所以标 skipped 让调用方跳过，而不是判失败。 */
  function clippedByAncestor(el, x, y) {
    var p = el.parentElement;
    while (p && p !== document.body) {
      var cs = window.getComputedStyle(p);
      if (/hidden|auto|scroll/.test(cs.overflowY + ' ' + cs.overflowX)) {
        var r = p.getBoundingClientRect();
        if (x < r.left || x > r.right || y < r.top || y > r.bottom) return true;
      }
      p = p.parentElement;
    }
    return false;
  }

  function hitTest(sel) {
    var el = document.querySelector(sel);
    if (!el) return { sel: sel, missing: true };
    var r = el.getBoundingClientRect();
    var x = r.left + r.width / 2;
    var y = r.top + r.height / 2;
    if (clippedByAncestor(el, x, y)) {
      return { sel: sel, visual: [Math.round(x), Math.round(y)], skipped: true, why: '被滚动容器裁掉' };
    }
    var got = document.elementFromPoint(x, y);
    return {
      sel: sel,
      visual: [Math.round(x), Math.round(y)],
      hit: got ? cls(got) : null,
      ok: !!(got && (got === el || el.contains(got) || got.contains(el))),
    };
  }

  var steps = [];

  function snap(tag, hits, boxes, extra) {
    var app = document.getElementById('game-app');
    var layer = document.querySelector('.scale-layer');
    var rootCs = window.getComputedStyle(document.documentElement);
    var appCs = app ? window.getComputedStyle(app) : null;

    var rec = {
      tag: tag,
      hash: location.hash,
      viewport: [window.innerWidth, window.innerHeight],
      visualViewport: window.visualViewport
        ? [Math.round(window.visualViewport.width), Math.round(window.visualViewport.height)]
        : null,
      docClass: document.documentElement.className,
      scaleVar: (rootCs.getPropertyValue('--scale') || '').trim(),
      frameVars: [
        (rootCs.getPropertyValue('--frame-w') || '').trim(),
        (rootCs.getPropertyValue('--frame-h') || '').trim(),
      ],
      safeVars: ['--safe-top', '--safe-bottom', '--safe-left', '--safe-right'].map(function (k) {
        return (rootCs.getPropertyValue(k) || '').trim();
      }),
      bodyBg: window.getComputedStyle(document.body).backgroundColor,
      bodyOverflow: window.getComputedStyle(document.body).overflow,
      appInline: app
        ? { left: app.style.left, top: app.style.top, width: app.style.width, height: app.style.height, transform: app.style.transform }
        : null,
      appRect: app ? rect(app) : null,
      appComputed: appCs
        ? { transform: appCs.transform, padding: appCs.padding, overflow: appCs.overflow, position: appCs.position, display: appCs.display }
        : null,
      appChildren: [],
      layerRect: layer ? rect(layer) : null,
      contentBox: tag === 'table' ? contentBox(layer) : null,
      zones: tag === 'table' ? designZones(layer, currentScale()) : null,
      boxes: [],
      hits: [],
    };

    // 页面根元素是否被撑满、是否还能滚（虚拟横屏下最典型的坏法就是被 100vh 顶出去）
    if (app) {
      rec.appChildren = Array.prototype.map.call(app.children, function (el) {
        var c = window.getComputedStyle(el);
        return {
          cls: cls(el),
          rect: rect(el),
          minHeight: c.minHeight,
          overflowY: c.overflowY,
          clientH: el.clientHeight,
          clientW: el.clientWidth,
          scrollH: el.scrollHeight,
          scrollable: el.scrollHeight > el.clientHeight + 1 && /auto|scroll/.test(c.overflowY),
        };
      });
    }

    // 弹窗：矩形 + 内容是否溢出 + 是否给自己留了滚动
    (boxes || []).forEach(function (sel) {
      var el = document.querySelector(sel);
      if (!el) { rec.boxes.push({ sel: sel, missing: true }); return; }
      var c = window.getComputedStyle(el);
      rec.boxes.push({
        sel: sel,
        rect: rect(el),
        maxHeight: c.maxHeight,
        overflowY: c.overflowY,
        clientH: el.clientHeight,
        scrollH: el.scrollHeight,
      });
    });

    (hits || []).forEach(function (sel) { rec.hits.push(hitTest(sel)); });
    // 额外字段（登录页要带出输入框预填值、勾选状态、localStorage 内容等）
    if (extra) { for (var k in extra) { if (extra.hasOwnProperty(k)) rec[k] = extra[k]; } }
    steps.push(rec);
  }

  function done() {
    var out = { ua: navigator.userAgent, steps: steps, errs: window.__hn_errs };
    var pre = document.createElement('pre');
    pre.id = 'probe-mobile';
    pre.textContent = JSON.stringify(out);
    document.body.appendChild(pre);
  }

  /* 依次量：牌桌（有 .scale-layer 这个固定框）→ 大厅 → 大厅的两个弹窗 → 战绩。
     每一步都等路由重新挂载完再量，否则量到的是上一屏的 DOM。
     --only 时提前收工，方便配合 --shot 给某一屏留一张真实截图。 */
  /* 登录页：验「记住用户名（默认）/ 记住密码（可选）」与备案号。
     注意要先把 token 清掉 —— 不清的话 isLoggedIn() 为真，访问 #/ 会直接被
     重定向到 /lobby，登录页根本挂不上。 */
  function screenLogin() {
    try {
      ['accessToken', 'refreshToken', 'tokenExpiresAt', 'tokenTtl', 'profile', 'userId', 'nickname', 'username']
        .forEach(function (k) { localStorage.removeItem(k); });
    } catch (e) {}
    location.hash = '#/';
    waitFor('.login__card', function () {
      var u = document.querySelector('.login input[type="text"]');
      var p = document.querySelector('.login input[type="password"]');
      var cb = document.querySelector('.login__remember input[type="checkbox"]');
      var beian = document.querySelector('.login .beian');
      var info = {
        username: u ? u.value : null,
        password: p ? p.value : null,
        checked: cb ? !!cb.checked : null,
        beianText: beian ? (beian.textContent || '').trim() : null,
        beianHref: beian ? beian.getAttribute('href') : null,
        storedBefore: localStorage.getItem('hnLoginRemember'),
      };
      /* 取消勾选「记住密码」→ 已存的密码必须立刻从 localStorage 消失 */
      if (cb) cb.click();
      setTimeout(function () {
        info.storedAfterUncheck = localStorage.getItem('hnLoginRemember');
        snap('login', ['.login__submit', '.login .beian'],
             ['.login__card', '.login__foot'], { login: info });
        done();
      }, 200);
    }, 40);
  }

  function afterRule() {
    if (window.__HN_ONLY__) return done();
    location.hash = '#/records';
    setTimeout(function () {
      snap('records', ['.records__head button']);
      setTimeout(screenLogin, 250);
    }, 900);
  }

  /* 局内战绩弹窗：点牌桌右下角「局内战绩」，量它占可见框多大（要求 ≈80%）。 */
  function screenHistory() {
    var btn = document.querySelector('.table__history button');
    if (!btn) { snap('history', [], ['.rh__modal']); return screenLobbyStart(); }
    btn.click();
    waitFor('.rh__modal', function (dlg) {
      snap('history', ['.rh__head button'], ['.rh__modal', '.rh__body']);
      if (window.__HN_ONLY__ === 'history') return done();
      var close = dlg && dlg.querySelector('.rh__head button');
      if (close) close.click();
      setTimeout(screenLobbyStart, 200);
    }, 30);
  }

  function screenLobbyStart() {
    location.hash = '#/lobby';
    setTimeout(screenLobby, 900);
  }

  /* 用户实测的问题就在这里：点「创建新房间」弹出的规则窗口，上界和下界都在屏幕外。
     原来它的 max-height 用的是 88vh —— vh 是物理视口高，竖屏虚拟横屏下比可见框高近一倍。 */
  function screenRule() {
    clickByText('.lobby__main button', '创建新房间');
    waitFor('.rd__dlg', function (dlg) {
      snap('rule',
        ['.rd__btns .btn--primary'],
        ['.rd__dlg', '.rd__head', '.rd__body', '.rd__btns']);
      if (window.__HN_ONLY__ === 'rule') return done();  // 不关闭，方便截图
      var close = dlg && dlg.querySelector('.rd__head button');
      if (close) close.click();
      setTimeout(afterRule, 200);
    }, 30);
  }

  function screenSettings() {
    clickByText('.lobby__topbtns button', '设置');
    waitFor('.sd__dlg', function (dlg) {
      snap('settings',
        ['.sd__head button', '.sd__tab'],
        ['.sd__dlg', '.sd__head', '.sd__body']);
      if (window.__HN_ONLY__ === 'settings') return done();  // 不关闭，方便截图
      var close = dlg && dlg.querySelector('.sd__head button');
      if (close) close.click();
      setTimeout(screenRule, 250);
    }, 30);
  }

  function screenLobby() {
    snap('lobby', ['.lobby__topbtns button', '.lobby__dock button']);
    if (window.__HN_ONLY__ === 'table') return done();
    // 两个弹窗属于"大厅阶段"，--only lobby 也要量（用户实测的问题就在规则弹窗）
    setTimeout(screenSettings, 250);
  }

  setTimeout(function () {
    applyZoom();
    /*
     * 量牌桌之前补两条消息，专为验证这一轮的界面改动：
     *   1. dealer = 我（NORTH）→ 我自己的名字后面应当出现"庄"
     *   2. 一条 kind=action 的 request → 吃碰杠胡操作栏出现（否则它不渲染，没法量位置）
     * 只喂给手机探针，不动 browser-probe 的桌面夹具（那边有一整套逐像素断言）。
     */
    try {
      window.__hn_feed({ type: 'dealer', dealer: 'NORTH' });
      window.__hn_feed({
        type: 'request',
        kind: 'action',
        reqId: 'probe-act-1',
        tile: 5,
        timeoutMs: 30000,
        options: [
          { type: 'PENG', tiles: [5, 5, 5] },
          { type: 'GANG', tiles: [5, 5, 5, 5] },
          { type: 'HU' },
        ],
      });
      /*
       * 再补一把"结算"，让胡牌横幅上的得分有确定的期望值。
       * 主夹具里 hand_start 带 coins=100、结算却是 3/-1，两者不自洽（那是给别的断言用的），
       * 所以这里再走一次 hu → coins：结算前 3 → 结算后 6，赢家差额正好 +3。
       */
      window.__hn_feed({
        type: 'hu', seat: 'EAST', selfDraw: true, tile: 30, from: null,
        fans: ['碰碰胡'], baoTing: 0, tianHu: false, ganKai: false,
      });
      window.__hn_feed({
        type: 'coins', hand: 2,
        coins: { EAST: 6, SOUTH: -2, WEST: -2, NORTH: -2 },
      });
    } catch (e) {}
    setTimeout(function () {
      snap('table', ['.table__history button', '.table__bottom button'],
           ['.table__actions'], tableExtras());
      if (window.__HN_ONLY__ === 'table') return done();
      screenHistory();
    }, 220);
  }, 1500);
})();
</script>
"""


def build_page(dest, only=None, zoom=1.0):
    with open(os.path.join(STATIC, "index.html"), "r", encoding="utf-8") as f:
        html = f.read()
    m = re.search(r'<script type="module"', html)
    if not m:
        raise SystemExit("index.html 里找不到 module 入口")
    # --only 时只走到指定那一屏（配合 --shot 留真实截图），标记要在 SHIM 之前注入
    flag = "<script>window.__HN_ONLY__=%s;window.__HN_REMEMBER__=%s;window.__HN_ZOOM__=%s;</script>" % (
        json.dumps(only) if only else "null",
        json.dumps({
            "username": REMEMBERED_USER,
            "rememberPassword": True,
            "password": REMEMBERED_PASS,
        }),
        json.dumps(zoom),
    )
    # SHIM（打桩）先执行，手机测量脚本随后；两者都在应用入口之前，但测量靠 setTimeout 排队
    html = html[: m.start()] + flag + bp.SHIM + MOBILE_SHIM + "\n" + html[m.start():]
    with open(dest, "w", encoding="utf-8") as f:
        f.write(html)

def parse_matrix(t):
    m = re.match(r"matrix\(([-\d.e, ]+)\)", t or "")
    if not m:
        return None
    return [float(x) for x in m.group(1).split(",")]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep", action="store_true", help="保留临时 HTML")
    ap.add_argument("--shot", default=None, help="同时截图到指定 png")
    ap.add_argument("--wait", type=int, default=9000, help="虚拟时间预算 ms")
    # 为什么默认是 518x844 而不是 390x844：
    #   无头 Chrome 的窗口有 18px 边框/152px 标题栏开销，而且【最小窗口宽度被钳到
    #   500px】—— 传 390 实际得到的是 500x692 的布局视口（与截图尺寸对不上，
    #   截图看起来会错位）。518x844 正好换算出 500x692 的布局视口，
    #   截图尺寸也几乎等于布局尺寸，看到的就是真实渲染。
    ap.add_argument("--size", default="518,1232", help="窗口尺寸 W,H（布局视口 ≈ W-18 x H-152）")
    ap.add_argument("--only", choices=["table", "history", "lobby", "settings", "rule"], default=None,
                    help="只走到该屏并停在那里（settings/rule 会保持弹窗打开），方便配合 --shot 截图")
    # 手机比例：518x1232 → 布局视口 500x1080，旋转后固定框 1080x500 ≈ 2.16:1，
    # 与真机（390x844 → 2.16:1）一致。用更方的窗口会算出不一样的留白，图会失真。
    ap.add_argument("--zoom", type=float, default=1.0,
                    help="把牌桌的 --scale 乘这个倍数，用来预览「放大后会裁掉什么」（不改应用代码）")
    ap.add_argument("--orientation", choices=["portrait", "landscape"], default="portrait",
                    help="手机持握方向：portrait=竖屏（要虚拟横屏旋转），"
                         "landscape=物理横屏（@media 不匹配，必须自动恢复、不能双重旋转）")
    args = ap.parse_args()

    land = args.orientation == "landscape"
    if args.size == "518,1232" and land:
        args.size = "1232,518"  # 横屏窗口默认值（布局视口 ≈ 1214x366）

    chrome = bp.find_chrome()
    if not chrome:
        print("[mobile] 找不到 Chrome/Edge，跳过")
        return 2

    tmpdir = tempfile.mkdtemp(prefix="hn-mobile-")
    page = os.path.join(tmpdir, "index.html")
    build_page(page, args.only, args.zoom)
    for sub in os.listdir(STATIC):
        if sub == "index.html":
            continue
        s = os.path.join(STATIC, sub)
        d = os.path.join(tmpdir, sub)
        if os.path.isdir(s):
            shutil.copytree(s, d, dirs_exist_ok=True)
        else:
            shutil.copy2(s, d)

    srv, port = bp.start_server(tmpdir)
    url = "http://127.0.0.1:%d/index.html" % port
    profile = os.path.join(tmpdir, "profile")

    cmd = [
        chrome,
        "--headless=new",
        "--disable-gpu",
        "--no-sandbox",
        "--hide-scrollbars",
        "--no-proxy-server",
        "--window-size=" + args.size,
        "--force-device-scale-factor=1",
        "--user-agent=" + MOBILE_UA,
        "--user-data-dir=" + profile,
        "--virtual-time-budget=%d" % args.wait,
        "--dump-dom",
        url,
    ]
    if args.shot:
        cmd.insert(-2, "--screenshot=" + os.path.abspath(args.shot))

    print("[mobile] " + chrome)
    print("[mobile] " + url + "  窗口 " + args.size + "（%s + 手机 UA）"
          % ("竖屏持握" if not land else "物理横屏"))
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                           errors="replace", timeout=args.wait / 1000.0 + 90)
    finally:
        srv.shutdown()

    dom = r.stdout or ""
    m = re.search(r'<pre id="probe-mobile">(.*?)</pre>', dom, re.S)
    if not m:
        print("[mobile] 没拿到测量结果；DOM 长度=%d" % len(dom))
        print((r.stderr or "")[:1500])
        if args.keep:
            print("[mobile] 临时文件: " + page)
        return 1

    data = json.loads(
        m.group(1).replace("&quot;", '"').replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    )

    fails = []

    def check(cond, msg, extra=""):
        if cond:
            print("  [ok]   " + msg)
        else:
            fails.append(msg)
            print("  [FAIL] " + msg + ("  " + extra if extra else ""))

    ua = data.get("ua", "")
    print("[mobile] UA = %s" % ua[:90])
    check("iPhone" in ua or "Android" in ua, "浏览器以手机 UA 渲染（否则走的是桌面分支）")

    errs = data.get("errs") or []
    if errs:
        print("[mobile] 页面错误 %d 条:" % len(errs))
        for e in errs[:10]:
            print("  " + e)
        fails.append("页面有 %d 条运行时错误" % len(errs))

    steps = {s.get("tag"): s for s in (data.get("steps") or [])}
    want_tags = ["table", "history", "lobby", "settings", "rule", "records", "login"]
    if args.only == "table":
        want_tags = ["table"]
    elif args.only == "history":
        want_tags = ["table", "history"]
    elif args.only == "lobby":
        want_tags = ["table", "history", "lobby", "settings", "rule"]
    elif args.only == "settings":
        want_tags = ["table", "history", "lobby", "settings"]
    elif args.only == "rule":
        want_tags = ["table", "history", "lobby", "settings", "rule"]
    if len(steps) != len(want_tags):
        fails.append("只量到 %d 屏（应为 %s）" % (len(steps), "/".join(want_tags)))
        print("[mobile] 实际量到的屏: %s" % list(steps))

    for tag in want_tags:
        s = steps.get(tag)
        print("\n--- %s ---" % tag)
        if not s:
            check(False, "量到 %s 屏" % tag)
            continue
        vw, vh = s["viewport"]
        print("  viewport=%dx%d  hash=%s  --scale=%s" % (vw, vh, s.get("hash"), s.get("scaleVar")))
        print("  #game-app inline=%s" % json.dumps(s.get("appInline"), ensure_ascii=False))
        print("  #game-app rect=%s computed=%s" % (json.dumps(s.get("appRect")), json.dumps(s.get("appComputed"), ensure_ascii=False)))
        print("  .scale-layer rect=%s" % json.dumps(s.get("layerRect")))
        if s.get("contentBox"):
            print("  内容占框比例 = %s" % json.dumps(s["contentBox"]))
        if s.get("zones"):
            print("  各功能区设计坐标（u=宽方向, v=高方向, 设计框 1280x720）:")
            for sel, b in s["zones"].items():
                if isinstance(b, list):
                    # 牌河是逐张的（见 designZones 里的说明）
                    print("    %-18s 共 %d 张，v 范围 %s ~ %s"
                          % (sel, len(b),
                             min(t["v"][0] for t in b), max(t["v"][1] for t in b)))
                else:
                    print("    %-18s u=%s  v=%s" % (sel, b["u"], b["v"]))
        for c in s.get("appChildren") or []:
            print("  根元素 %-28s rect=%s minH=%s overflowY=%s clientH=%s scrollH=%s"
                  % (c["cls"][:28], json.dumps(c["rect"]), c["minHeight"], c["overflowY"], c["clientH"], c["scrollH"]))
        for h in s.get("hits") or []:
            print("  触点 %-28s 视觉坐标=%s 命中=%s" % (h.get("sel"), h.get("visual"), h.get("hit")))

        check(vh > vw if not land else vw > vh,
              "%s：窗口是%s（%dx%d）" % (tag, "竖屏" if not land else "物理横屏", vw, vh))
        check("virtual-landscape" in (s.get("docClass") or ""),
              "%s：<html> 带上了 virtual-landscape" % tag,
              "class=%r" % s.get("docClass"))

        inline = s.get("appInline") or {}
        mat = parse_matrix((s.get("appComputed") or {}).get("transform"))

        if not land:
            # 旋转：matrix(a,b,c,d,...) 中 a≈0、b≈1 就是 rotate(90deg)
            check(mat is not None and abs(mat[0]) < 0.01 and abs(mat[1] - 1) < 0.01,
                  "%s：#game-app 被 rotate(90deg)（matrix=%s）" % (tag, mat))

            # 显式像素：sizeVirtualApp 里 width=物理高、height=物理宽
            want_w = str(vh) + "px"
            want_h = str(vw) + "px"
            check(inline.get("width") == want_w and inline.get("height") == want_h,
                  "%s：#game-app 用显式像素尺寸（%s x %s，期望 %s x %s）"
                  % (tag, inline.get("width"), inline.get("height"), want_w, want_h))
            check(inline.get("left") == "50%" and inline.get("top") == "50%",
                  "%s：#game-app 用 left/top 50%% + translate 居中" % tag)
            expect_h = vw  # 旋转后容器的可视高 = 物理宽
        else:
            # 物理转回横屏：@media (orientation: portrait) 不再匹配，
            # 自动恢复原样，且 JS 必须把内联尺寸/旋转清空（否则会双重旋转/尺寸错）
            check(mat is None or mat == "none",
                  "%s：物理横屏时 #game-app 不带旋转（transform=%s）" % (tag, mat))
            check(not inline.get("transform") and not inline.get("width") and not inline.get("height"),
                  "%s：物理横屏时内联尺寸/旋转已清空，交回 CSS（inline=%s）"
                  % (tag, json.dumps(inline, ensure_ascii=False)))
            expect_h = vh

        # 铺满物理屏幕（竖屏时是旋转后覆盖整屏；横屏时就是 100vw x 100vh）
        ar = s.get("appRect") or {}
        check(abs(ar.get("x", -99)) <= 2 and abs(ar.get("y", -99)) <= 2
              and abs(ar.get("w", 0) - vw) <= 2 and abs(ar.get("h", 0) - vh) <= 2,
              "%s：#game-app 正好铺满物理屏幕（rect=%s，屏 %dx%d）"
              % (tag, json.dumps(ar), vw, vh))

        # 固定框必须完整落在屏幕内：不裁切、不越界
        # （--zoom 预览模式故意让它溢出，这条跳过 —— 看的就是溢出多少）
        lr = s.get("layerRect")
        if lr and args.zoom == 1:
            check(lr["x"] >= -1 and lr["y"] >= -1 and lr["right"] <= vw + 1 and lr["bottom"] <= vh + 1,
                  "%s：1280x720 固定框缩放后完整落在屏幕内（%s，屏 %dx%d）"
                  % (tag, json.dumps(lr), vw, vh))

        # 页面根元素必须填满固定框：布局高度 == 旋转后容器的可视高。
        # ⚠️ 不能拿 getBoundingClientRect().height 判 —— 竖屏旋转后它是【视觉】高度，
        #    任何横向铺满的元素都会得到物理高，恒等于 vh，等于没测。
        #    布局像素要看 clientHeight（不受 transform 影响）：
        #    留着 min-height:100vh 时它会变成物理高(692) 而不是物理宽(500)。
        roots = s.get("appChildren") or []
        check(len(roots) == 1, "%s：#game-app 里只有一个页面根元素" % tag)
        if roots:
            check("vh" not in str(roots[0]["minHeight"]),
                  "%s：页面根元素没有残留 vh 单位的 min-height（%s）" % (tag, roots[0]["minHeight"]))
            rec0 = roots[0]
            if str(rec0["cls"]).startswith("scale-layer"):
                # 牌桌的根就是设计分辨率的固定框。
                # 手机竖屏（虚拟横屏）时用【紧凑设计高】换取整体放大（720 → 590 ≈ +22%）；
                # 桌面/物理横屏必须还是 720（桌面布局有逐像素断言）。
                want_h = COMPACT_H if (not land) else FULL_H
                check(rec0["clientH"] == want_h,
                      "%s：.scale-layer 设计高=%d（%s）"
                      % (tag, rec0["clientH"], "手机紧凑" if want_h == COMPACT_H else "标准 720"))
            else:
                check(rec0["clientH"] == expect_h,
                      "%s：页面根元素布局高=%d（应为 %d）" % (tag, rec0["clientH"], expect_h),
                      "clientH=%s clientW=%s" % (rec0["clientH"], rec0["clientW"]))

        # 安全区变量必须存在（无头 Chrome 里取值为 0px，但不能是空）
        sv = s.get("safeVars") or []
        check(len(sv) == 4 and all(x != "" for x in sv),
              "%s：安全区变量已定义 %s" % (tag, sv))

        # 触点：视觉中心必须命中元素本身（被滚动容器裁掉的跳过，见 hitTest）
        for h in s.get("hits") or []:
            if h.get("missing"):
                print("  [info] %s 在当前屏不存在，跳过触点检查" % h.get("sel"))
                continue
            if h.get("skipped"):
                print("  [info] %s 视觉中心 %s 被滚动容器裁掉，跳过触点检查"
                      % (h.get("sel"), h.get("visual")))
                continue
            check(h.get("ok") is True,
                  "%s：视觉坐标 %s 能点到 %s（命中 %s）" % (tag, h.get("visual"), h.get("sel"), h.get("hit")))

    # 大厅/战绩：内容超出时根元素要能滚，不能被 overflow:hidden 裁掉
    for tag in ("lobby", "records"):
        s = steps.get(tag)
        if not s:
            continue
        for c in s.get("appChildren") or []:
            if c["scrollH"] > c["clientH"] + 1:
                check(c["scrollable"], "%s：内容超出时根元素可滚动（scrollH=%d clientH=%d overflowY=%s）"
                      % (tag, c["scrollH"], c["clientH"], c["overflowY"]))

    # ---------------- 弹窗必须整个落在可见框内 ----------------
    #
    # 用户实测："竖屏虽然是横屏展示，但点创建房间出现的规则窗口上界和下界都不在屏幕内"。
    # 成因是弹窗写的是 max-height: 88vh —— vh 是【物理视口高】(692)，可见框高只有 500。
    for tag in ("rule", "settings"):
        s = steps.get(tag)
        if not s:
            continue
        print("\n--- %s 弹窗位置 ---" % tag)
        vw, vh = s["viewport"]
        for b in s.get("boxes") or []:
            print("  %-16s missing=%s rect=%s maxHeight=%s overflowY=%s clientH=%s scrollH=%s"
                  % (b.get("sel"), b.get("missing"), json.dumps(b.get("rect")),
                     b.get("maxHeight"), b.get("overflowY"), b.get("clientH"), b.get("scrollH")))
            if b.get("missing"):
                check(False, "%s：找得到 %s" % (tag, b.get("sel")))
                continue
            r = b["rect"]
            check(r["x"] >= -1 and r["y"] >= -1 and r["right"] <= vw + 1 and r["bottom"] <= vh + 1,
                  "%s：%s 完整落在屏幕内（rect=%s，屏 %dx%d）"
                  % (tag, b["sel"], json.dumps(r), vw, vh))
            # 内容超出时必须自己可滚，否则底部按钮永远点不到
            if b["scrollH"] > b["clientH"] + 1:
                check(b["overflowY"] in ("auto", "scroll"),
                      "%s：%s 内容超出时可滚动（overflowY=%s）" % (tag, b["sel"], b["overflowY"]))
        # 注意：弹窗里按钮的触点检查已经在上面通用循环里做过（hits），这里不重复

    # ---------------- 登录页：记住用户名 / 记住密码 / 备案号 ----------------
    lg = steps.get("login")
    if lg:
        info = lg.get("login") or {}
        print("\n--- 登录页 ---")
        print("  用户名框=%r  密码框=%r  勾选=%s" % (info.get("username"), info.get("password"), info.get("checked")))
        print("  备案号=%r  href=%r" % (info.get("beianText"), info.get("beianHref")))
        print("  localStorage(取消勾选前) = %s" % info.get("storedBefore"))
        print("  localStorage(取消勾选后) = %s" % info.get("storedAfterUncheck"))

        check(info.get("beianText") == BEIAN_TEXT,
              "登录页显示备案号「%s」" % BEIAN_TEXT, "实际 %r" % info.get("beianText"))
        check(info.get("beianHref") == BEIAN_HREF,
              "备案号链到工信部查询站", "实际 %r" % info.get("beianHref"))

        # 用户名：默认就记住（预置的存档应当被自动填进输入框）
        check(info.get("username") == REMEMBERED_USER,
              "默认记住用户名：进来自动填好 %r" % REMEMBERED_USER, "实际 %r" % info.get("username"))
        # 密码：勾了「记住密码」才预填
        check(info.get("checked") is True, "「记住密码」勾选状态被还原（checked=%s）" % info.get("checked"))
        check(info.get("password") == REMEMBERED_PASS,
              "勾了记住密码时密码也预填", "实际 %r" % info.get("password"))
        # 取消勾选后，密码必须立刻从 localStorage 抹掉，用户名留着
        after = {}
        try:
            after = json.loads(info.get("storedAfterUncheck") or "{}")
        except Exception as e:
            fails.append("取消勾选后的 localStorage 不是合法 JSON：%s" % e)
        check("password" not in after and "rememberPassword" not in after,
              "取消勾选后密码立刻从本机抹掉", "实际 %s" % info.get("storedAfterUncheck"))
        check(after.get("username") == REMEMBERED_USER,
              "取消勾选不会把记住的用户名一起清掉")

        for b in lg.get("boxes") or []:
            if b.get("missing"):
                check(False, "登录页找得到 %s" % b.get("sel"))
                continue
            r = b["rect"]
            vw, vh = lg["viewport"]
            # 竖屏（手机正常持握）才断言"卡片完整在屏幕内"；
            # 物理横屏的框只有 366 高，登录卡片本来就比它高、由卡片区自己滚 —— 那不是错。
            if land:
                print("  [info] 横屏下登录卡片可滚动，跳过整卡适配断言（rect=%s）" % json.dumps(r))
                continue
            check(r["x"] >= -1 and r["y"] >= -1 and r["right"] <= vw + 1 and r["bottom"] <= vh + 1,
                  "登录页 %s 完整落在屏幕内（rect=%s，屏 %dx%d）" % (b["sel"], json.dumps(r), vw, vh))

    # ---------------- 紧凑设计框不能把内容压重叠 ----------------
    #
    # 手机上把设计高从 720 压到 590、手牌放大到 lg，靠的是各功能区的锚点自己让位：
    # 我的手牌/副露是【下对齐】，压矮后会整体上移 —— 所以最大的风险是压到牌河上。
    # 这里就量这个：牌河牌的外接矩形，不能和我的副露/手牌、对家手牌相交。
    #
    # ⚠️ 这套换算假定"整页被旋转 90°"（竖屏虚拟横屏）。物理横屏不旋转，
    #    换算出来的设计坐标是错的，所以只在竖屏跑。
    tb = steps.get("table")
    if tb and tb.get("zones") and not land:
        z = tb["zones"]
        def inter(a, b):
            return not (a["u"][1] <= b["u"][0] or b["u"][1] <= a["u"][0]
                        or a["v"][1] <= b["v"][0] or b["v"][1] <= a["v"][0])
        river = z.get(".table__center .tile") or []
        if river:
            print("\n--- 紧凑布局是否压重叠（牌河共 %d 张，逐张判相交）---" % len(river))
            for other in (".table__mymelds", ".table__hand", ".table__bottom", ".seat--top"):
                o = z.get(other)
                if not o:
                    continue
                hits = [t for t in river if inter(t, o)]
                check(not hits,
                      "牌河与 %s 不相交（%s v=%s，撞上的牌 %d 张）" % (other, other, o["v"], len(hits)),
                      "撞上的牌: %s" % json.dumps(hits[:3]))

            # 牌河框被压矮了（320 → 290），牌不能溢出这个框：
            # .river 是 1fr:1.9fr:1fr 的网格，框太矮时会直接把牌挤出去。
            c = z.get(".table__center")
            if c:
                out = [t for t in river
                       if t["u"][0] < c["u"][0] - 2 or t["u"][1] > c["u"][1] + 2
                       or t["v"][0] < c["v"][0] - 2 or t["v"][1] > c["v"][1] + 2]
                check(not out,
                      "牌河里的牌都在牌河框内（框 u=%s v=%s）" % (c["u"], c["v"]),
                      "溢出的牌: %s" % json.dumps(out[:3]))

    # ---------------- 局内战绩弹窗要占屏幕 ≈80% ----------------
    hs = steps.get("history")
    if hs:
        print("\n--- 局内战绩弹窗尺寸 ---")
        vw, vh = hs["viewport"]
        for b in hs.get("boxes") or []:
            if b.get("missing"):
                check(False, "局内战绩弹窗存在（找不到 %s）" % b.get("sel"))
                continue
            r = b["rect"]
            print("  %-12s rect=%s 占可见框 %.0f%% x %.0f%%"
                  % (b["sel"], json.dumps(r), 100.0 * r["w"] / vw, 100.0 * r["h"] / vh))
            if b["sel"] == ".rh__modal":
                # 弹窗尺寸的口径是"占【设计框】80%"（设备无关）。
                # 横屏/桌面下设计框在可见框里是留白的（层比屏窄），那时弹窗按屏幕算
                # 就只有 40% 多 —— 那是几何决定的，不是弹窗小了。
                lr = hs.get("layerRect") or {}
                if lr.get("w") and lr.get("h"):
                    wp = 100.0 * r["w"] / lr["w"]
                    hp = 100.0 * r["h"] / lr["h"]
                    print("     占设计框 %.0f%% x %.0f%%" % (wp, hp))
                    check(70 <= wp <= 88, "弹窗宽度占设计框 ≈80%%（实际 %.0f%%）" % wp)
                    # 高度是 max-height:80% + min-height:55%：内容多就顶到 80%，
                    # 只有一两把时按内容走（不低于 55%），所以这里给的是区间
                    check(50 <= hp <= 88, "弹窗高度占设计框 55%%~80%%（实际 %.0f%%）" % hp)
            if not land:
                check(r["x"] >= -1 and r["y"] >= -1 and r["right"] <= vw + 1 and r["bottom"] <= vh + 1,
                      "局内战绩弹窗完整落在屏幕内（%s）" % json.dumps(r))
        dlg = next((b for b in (hs.get("boxes") or [])
                    if b.get("sel") == ".rh__modal" and not b.get("missing")), None)
        if dlg and not land:
            wpct = 100.0 * dlg["rect"]["w"] / vw
            hpct = 100.0 * dlg["rect"]["h"] / vh
            check(70 <= wpct <= 92, "弹窗宽度约占屏幕 80%%（实际 %.0f%%）" % wpct)
            check(62 <= hpct <= 92, "弹窗高度约占屏幕 80%%（实际 %.0f%%）" % hpct)

    # ---------------- 手牌放大 / 操作栏位置 / 胡牌得分 / 庄标记 ----------------
    tb = steps.get("table")
    if tb:
        print("\n--- 手牌、操作栏、胡牌横幅、庄标记 ---")

        def dv(b):
            """设计坐标盒子（{u:[..], v:[..]}）"""
            return (b or {}).get("design") or {}

        def dsize(b):
            """设计尺寸：u 是设计宽方向、v 是设计高方向（旋转只影响映射到屏幕哪根轴）"""
            d = dv(b)
            if not d:
                return (0, 0)
            return (d["u"][1] - d["u"][0], d["v"][1] - d["v"][0])  # (宽, 高)

        def doverlap(a, b):
            da, db = dv(a), dv(b)
            if not da or not db:
                return False
            return not (da["u"][1] <= db["u"][0] or db["u"][1] <= da["u"][0]
                        or da["v"][1] <= db["v"][0] or db["v"][1] <= da["v"][0])

        for name in ("handTile", "handBox", "meldsBox", "chatBox", "actionBar"):
            b = tb.get(name)
            if b:
                w, h = dsize(b)
                print("  %-10s 设计 u=%s v=%s（%dx%d）"
                      % (name, dv(b)["u"], dv(b)["v"], w, h))
        print("  胡牌横幅=%r  自己名字=%r" % (tb.get("huText"), tb.get("selfName")))

        # ① 手牌放大到 lg（设计 58x79）—— 只在手机竖屏（虚拟横屏）成立
        ht = tb.get("handTile")
        if ht and not land:
            w, h = dsize(ht)
            check(abs(w - 58) <= 1 and abs(h - 79) <= 1,
                  "手机上手牌用 lg 档（设计 %dx%d，期望 58x79）" % (w, h))

        # ② 手牌不能压到右下角聊天框/自己的副露（用户明确的约束）
        #    设计坐标换算只在竖屏成立，横屏跳过
        if not land:
            check(not doverlap(tb.get("handBox"), tb.get("chatBox")),
                  "手牌与右下角聊天框不重叠")
            check(not doverlap(tb.get("handBox"), tb.get("meldsBox")),
                  "手牌与自己的副露不重叠")

        # ③ 操作栏：聊天框左上方那一片空当，且不压手牌/副露/聊天框
        ab = tb.get("actionBar")
        check(ab is not None, "操作栏渲染出来了（kind=action 的 request）")
        if ab and not land:
            dab, dch = dv(ab), dv(tb.get("chatBox"))
            print("  操作栏设计位置 u=%s v=%s" % (dab["u"], dab["v"]))
            check(not doverlap(ab, tb.get("chatBox")), "操作栏不压聊天框")
            check(not doverlap(ab, tb.get("handBox")), "操作栏不压手牌")
            check(not doverlap(ab, tb.get("meldsBox")), "操作栏不压自己的副露")
            if dch:
                check(dab["v"][1] <= dch["v"][0] + 1,
                      "操作栏在聊天框【上方】（操作栏 v 下沿 %d ≤ 聊天框 v 上沿 %d）"
                      % (dab["v"][1], dch["v"][0]))
                # 屏幕中偏右下：设计 u 在右半、v 在下半
                check(dab["u"][0] >= 640 and dab["v"][1] >= 300,
                      "操作栏位于屏幕中间偏右下（u=%s v=%s）" % (dab["u"], dab["v"]))

            # 按键：一律等宽（过/返回 不能比吃碰杠胡窄），且比原来大
            btns = tb.get("actBtns") or []
            print("  操作键：%s"
                  % json.dumps([(b["label"], dsize(b)[0]) for b in btns], ensure_ascii=False))
            if btns:
                widths = [dsize(b)[0] for b in btns]
                heights = [dsize(b)[1] for b in btns]
                check(len(set(widths)) == 1,
                      "操作键宽度一律相同（%s）" % sorted(set(widths)))
                check(min(widths) >= 95,
                      "操作键比原来大（宽 %d 设计 px，原来 50~70）" % min(widths))
                check(min(heights) >= 34,
                      "操作键高度也够点（%d 设计 px）" % min(heights))

        # ④ 胡牌横幅要带赢家得分（补的第二把：3 → 6，差额 +3）
        hu = tb.get("huText") or ""
        check("胡牌" in hu, "胡牌横幅显示了胡牌者", "实际 %r" % hu)
        check("+3" in hu, "胡牌横幅带上了赢家得分（+3）", "实际 %r" % hu)

        # ⑤ 我是庄 → 自己名字后面要有"庄"
        sn = tb.get("selfName") or ""
        check("庄" in sn, "自己坐庄时名字后面显示「庄」", "实际 %r" % sn)

    print("")
    if fails:
        print("[mobile] ==== 有 %d 项不符 ====" % len(fails))
        for f in fails:
            print("  - " + f)
        if args.keep:
            print("[mobile] 临时文件: " + page)
        return 1
    print("[mobile] ==== 手机适配断言全部通过 ====")
    if args.keep:
        print("[mobile] 临时文件: " + page)
    return 0


if __name__ == "__main__":
    sys.exit(main())
