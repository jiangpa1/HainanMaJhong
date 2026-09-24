#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
用无头 Chrome 渲染【真实构建产物】并量出关键元素的像素坐标。

为什么需要它：
  frontend/tools/smoke.mjs 跑在 happy-dom 上，happy-dom 没有排版引擎，
  getBoundingClientRect() 全返回 0 —— 它能证明"交互链路通"，但证明不了
  "排得下、没溢出"。而布局恰恰是唯一被用户反复退回的问题，
  所以必须有一个能真正量像素的手段。

做法：
  1. 读 src/main/resources/static/index.html（Vite 构建产物）
  2. 在入口 module 之前插入一段 shim：替换 window.WebSocket，
     连上后灌入合成的服务端消息，让牌桌进入"四家都有手牌+副露"的状态
  3. Chrome --headless 打开这个临时 HTML，把测量结果写进 <pre id="probe">
  4. --dump-dom 取出 DOM，解析 JSON，打印 + 断言

用法：
  python docs/browser-probe.py [--keep] [--shot out.png]

纯标准库，不依赖 selenium / playwright。
"""

import argparse
import functools
import http.server
import json
import math
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
STATIC = os.path.join(REPO, "src", "main", "resources", "static")

# Windows 上 stdout 默认跟控制台代码页走（cp936），断言消息里有 emoji（📋）时
# print 会直接抛 UnicodeEncodeError，探针"看起来"是崩了、其实只是打印失败。
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

CHROME_CANDIDATES = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    os.path.expandvars(r"%LOCALAPPDATA%\Google\Chrome\Application\chrome.exe"),
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
]

# ---------------------------------------------------------------------------
# 灌进页面的 shim
#
# 合成一组消息，正好覆盖用户这几轮提的所有布局问题：
#   · 我是 NORTH（下方），另外三家是 EAST(左) / SOUTH(上) / WEST(右)
#   · 三家各 13 张牌背 -> 验证"牌背不重叠"且排得下
#   · SOUTH 有一副碰(来自 WEST)、一副吃(来自 EAST)，EAST 有两副、
#     WEST 有三副 -> 验证"副露贴着手牌"且不把座位撑爆
# ---------------------------------------------------------------------------
SHIM = r"""
<script>
(function () {
  // 先把任何早期错误抓下来：文件协议下模块加载失败/异常时页面会是全空的，
  // 没有这个就完全看不到原因。
  window.__hn_errs = [];
  window.addEventListener('error', function (e) {
    window.__hn_errs.push('error: ' + (e.message || e.type) + ' @ ' + (e.filename || '') + ':' + (e.lineno || 0));
  }, true);
  window.addEventListener('unhandledrejection', function (e) {
    window.__hn_errs.push('reject: ' + (e.reason && e.reason.message ? e.reason.message : String(e.reason)));
  });
  var _ce = console.error;
  console.error = function () {
    window.__hn_errs.push('console.error: ' + Array.prototype.map.call(arguments, String).join(' '));
    _ce.apply(console, arguments);
  };

  /*
   * 语音合成打桩。
   * 包三道/包四道 现在【没有录音素材】，走的是 Web Speech API 兜底；
   * 无头 Chrome 里没有这个 API（调了也是静默失败），所以这里换成桩，
   * 把"本来会念什么"记下来，断言才有东西可查。
   *
   * ⚠️ 必须用 defineProperty：window.speechSynthesis 是只读访问器，
   *    直接 `window.speechSynthesis = {...}` 在非严格模式下【静默失败】，
   *    桩根本没装上（第一次就是这么踩的）。
   */
  window.__hn_spoken = [];
  Object.defineProperty(window, 'SpeechSynthesisUtterance', {
    value: function (text) { this.text = text; },
    writable: true,
    configurable: true,
  });
  Object.defineProperty(window, 'speechSynthesis', {
    value: {
      speak: function (u) { window.__hn_spoken.push(u && u.text ? u.text : String(u)); },
      cancel: function () {},
    },
    writable: true,
    configurable: true,
  });

  var seat = 'NORTH';
  var seats = ['EAST', 'SOUTH', 'WEST', 'NORTH'];

  function mk() {
    /*
     * 关键：src/game/socket.js 用的是【属性赋值】—— ws.onopen / ws.onmessage /
     * ws.onerror / ws.onclose，不是 addEventListener。
     * 第一版 shim 只实现了 addEventListener，结果一条消息都没进到应用里
     * （牌背 0 张、金币 0），排查了半天。两种都要支持。
     */
    var s = { readyState: 0, sent: [], onopen: null, onmessage: null, onerror: null, onclose: null };
    s.send = function (d) { s.sent.push(d); };
    s.close = function () {};
    s.addEventListener = function () {};
    s.removeEventListener = function () {};
    s._fire = function (name, ev) {
      var h = s['on' + name];
      if (typeof h === 'function') h(ev);
    };
    setTimeout(function () {
      s.readyState = 1;
      s._fire('open', {});
      feed();
    }, 30);
    return s;
  }

  function S(type, o) {
    var m = { type: type };
    for (var k in o) m[k] = o[k];
    window.__hn_feed(m);
  }

  var HAND = [0,1,2,9,10,11,18,19,20,27,27,31,33];

  function feed() {
    S('welcome', { seat: seat, roomId: 'PROBE1', players: seats.map(function (x, i) {
      return { userId: 100 + i, nickname: '玩家' + (i + 1), seat: x };
    })});
    S('match', { total: -1 });
    S('board', { discards: {}, melds: {}, flowers: {} });
    S('hand_start', { hand: 1, total: -1, dealer: 'EAST', wind: 3, windName: '北', bottom: 0,
                      coins: { EAST: 100, SOUTH: 100, WEST: 100, NORTH: 100 } });
    S('counts', { counts: { EAST: 13, SOUTH: 13, WEST: 13, NORTH: 13 }, wall: 55 });
    S('hand', { hand: HAND, melds: [], flowers: [] });
    S('turn', { seat: 'SOUTH', kind: 'discard', timeoutMs: 30000 });
    S('discard', { seat: 'SOUTH', tile: 3 });
    S('discard', { seat: 'EAST', tile: 5 });
    S('discard', { seat: 'WEST', tile: 12 });
    S('discard', { seat: seat, tile: 20 });
    // SOUTH: 碰 WEST 的 + 吃 EAST 的
    S('meld', { seat: 'SOUTH', meld: { type: 'PENG', tiles: [3, 3, 3], from: 'WEST' } });
    S('meld', { seat: 'SOUTH', meld: { type: 'CHI', tiles: [9, 10, 11], from: 'EAST' } });
    // SOUTH: 碰后补杠 —— 同一张牌的碰要被杠【替换掉】，不是再挂一副
    S('meld', { seat: 'SOUTH', meld: { type: 'BU_GANG', tiles: [3, 3, 3, 3], from: 'WEST' } });
    /*
     * 三道/四道是吃碰杠那一刻成立的，后端当场带上 notice。
     * 用户要求这一步【只给语音、不弹文字】—— 所以这条消息不该产生任何文字提示。
     * 这里刻意用一个和结算 note 不同的名字（包三道 vs 包四道），
     * 才能分辨出"文字是结算那条给的"还是"这次吃碰杠给的"。
     */
    S('meld', { seat: 'EAST', meld: { type: 'PENG', tiles: [12, 12, 12], from: seat }, notice: '包三道' });
    // EAST: 明杠（4 张，横放）+ 碰 + 暗杠（对"我"应当不可见）
    S('meld', { seat: 'EAST', meld: { type: 'GANG', tiles: [18, 18, 18, 18], from: 'SOUTH' } });
    S('meld', { seat: 'EAST', meld: { type: 'PENG', tiles: [27, 27, 27], from: seat } });
    S('meld', { seat: 'EAST', meld: { type: 'AN_GANG', hidden: true, tiles: [0, 0, 0, 0] } });
    // WEST: 三副露，把座位撑到最满
    S('meld', { seat: 'WEST', meld: { type: 'CHI', tiles: [0, 1, 2], from: 'SOUTH' } });
    S('meld', { seat: 'WEST', meld: { type: 'PENG', tiles: [9, 9, 9], from: 'EAST' } });
    S('meld', { seat: 'WEST', meld: { type: 'PENG', tiles: [18, 18, 18], from: 'SOUTH' } });
    // 我自己的一副暗杠：手牌消息里带真实牌面（自己当然看得见）
    S('hand', { hand: HAND, melds: [{ type: 'AN_GANG', tiles: [5, 5, 5, 5] }], flowers: [] });
    // 花牌也要占位置
    S('flower', { seat: 'EAST', tile: 34 });
    S('flower', { seat: 'WEST', tile: 35 });
    // 聊天：三家对手各一条 + 我自己一条（后端是广播，自己发的也会回来）
    S('chat', { seat: 'SOUTH', text: '碰！' });
    S('chat', { seat: 'WEST', text: '等一下嘛' });
    S('chat', { seat: 'EAST', text: '快点出牌' });
    S('chat', { seat: seat, text: '我来了' });
    /*
     * 胡牌 + 结算。coins 里带 notes —— 后端结算会把"代付原因"放这里
     * （包三道 / 包四道 / 包尾墙 / 包抢杠），前端要在胡牌横幅和局内战绩里显示。
     */
    S('hu', {
      seat: 'EAST', selfDraw: true, tile: 30, from: null,
      fans: ['碰碰胡'], baoTing: 0, tianHu: false,
    });
    S('coins', {
      hand: 1,
      coins: { EAST: 3, SOUTH: -1, WEST: -1, NORTH: -1 },
      notes: {
        EAST: ['自摸(碰碰胡)'],
        SOUTH: [],
        // 花分：真花/对花，后端会拼在一条 note 里（用「、」分隔）
        WEST: ['包四道·代付三家'],
        NORTH: ['真花·四季、真对花'],
      },
    });
  }

  var Real = window.WebSocket;
  window.__hn_socks = [];
  window.WebSocket = function (url) {
    var s = mk();
    s.url = typeof url === 'string' ? url : String(url);
    window.__hn_socks.push(s);
    return s;
  };
  window.WebSocket.CONNECTING = 0;
  window.WebSocket.OPEN = 1;
  window.WebSocket.CLOSING = 2;
  window.WebSocket.CLOSED = 3;
  void Real;

  try {
    // 真实登录态：api.js 的 TOKEN_KEY 就是 'accessToken'。
    // 没有它 GameTable 会在 onMounted 里直接跳回 /login，永远进不了牌桌。
    localStorage.setItem('accessToken', 'probe-token');
    localStorage.setItem('refreshToken', 'probe-refresh');
    localStorage.setItem('tokenExpiresAt', String(Date.now() + 3600000));
    localStorage.setItem('tokenTtl', '3600');
    localStorage.setItem('userId', '42');
    localStorage.setItem('nickname', '我');
    localStorage.setItem('profile', JSON.stringify({ id: 42, nickname: '我', username: 'probe' }));
  } catch (e) {}

  // 把一条"服务端消息"发给所有 socket 的 onmessage
  window.__hn_feed = function (m) {
    var data = JSON.stringify(m);
    window.__hn_socks.forEach(function (s) {
      try { s._fire('message', { data: data }); } catch (e) { console.error(e); }
    });
  };

  // ---------------- 测量 ----------------
  function rect(el) {
    var r = el.getBoundingClientRect();
    return { x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), h: Math.round(r.height) };
  }

  function measure() {
    var out = { ua: navigator.userAgent, viewport: [window.innerWidth, window.innerHeight], seats: {}, tiles: [], problems: [], errs: window.__hn_errs, sockets: (window.__hn_socks || []).length, hash: location.hash, text: document.body.innerText };

    // 把 store 状态一起带出来：牌背数量直接取决于 counts，看不到状态就没法排错
    try {
      var pinia = window.__hn_pinia;
      var st = pinia && pinia.state && pinia.state.value && pinia.state.value.game;
      if (st) {
        out.store = {
          mySeat: st.mySeat, counts: st.counts, coins: st.coins, hand: st.hand,
          boardMelds: st.boardMelds, boardFlowers: st.boardFlowers,
          boardDiscards: st.boardDiscards, opponents: st.opponents, players: st.players,
          wall: st.wall, turnSeat: st.turnSeat,
        };
      }
    } catch (e) { out.storeErr = String(e); }

    ['top', 'left', 'right'].forEach(function (pos) {
      var seatEl = document.querySelector('.seat--' + pos);
      if (!seatEl) { out.problems.push('缺少 .seat--' + pos); return; }
      // 用显式标记类，不靠 :first-child —— 三家的 DOM 顺序不同（下家是镜像的）
      var backs = seatEl.querySelector('.seatpanel__backs');
      var meta = seatEl.querySelector('.seatpanel__meta');
      var meld = seatEl.querySelector('.meldrow');
      var rec = { seat: rect(seatEl) };
      var scs = window.getComputedStyle(seatEl);
      rec.seatGrid = {
        cols: scs.gridTemplateColumns, rows: scs.gridTemplateRows,
        justifyItems: scs.justifyItems, alignItems: scs.alignItems,
        direction: scs.direction, writingMode: scs.writingMode,
        display: scs.display, width: scs.width,
        padding: scs.padding, border: scs.borderWidth,
      };
      // 座位网格里每个直接子项实际落在哪一行/哪一列（排查"哪一项跑到哪去了"）
      rec.gridItems = Array.prototype.map.call(seatEl.children, function (ch) {
        var c = window.getComputedStyle(ch);
        return {
          cls: ch.className,
          col: c.gridColumnStart, row: c.gridRowStart,
          r: rect(ch),
        };
      });
      if (backs) rec.backs = rect(backs);
      if (backs) {
        var bcs = window.getComputedStyle(backs);
        rec.backsStyle = {
          pos: bcs.position, left: bcs.left, right: bcs.right, top: bcs.top,
          transform: bcs.transform, origin: bcs.transformOrigin,
          offset: { l: backs.offsetLeft, t: backs.offsetTop, w: backs.offsetWidth, h: backs.offsetHeight },
        };
      }
      if (meta) rec.meta = rect(meta);
      rec.metaText = meta ? meta.innerText.replace(/\s+/g, ' ') : null;
      if (meld) rec.meld = rect(meld);

      var tiles = seatEl.querySelectorAll('.seatpanel__back');
      rec.backCount = tiles.length;
      rec.backRects = Array.prototype.map.call(tiles, rect);

      // 整排牌背的真实包围盒（不能拿 first/last 相减：180° 那家顺序是反的）
      if (rec.backRects.length) {
        var xs = rec.backRects.map(function (r) { return r.x; });
        var ys = rec.backRects.map(function (r) { return r.y; });
        var xe = rec.backRects.map(function (r) { return r.x + r.w; });
        var ye = rec.backRects.map(function (r) { return r.y + r.h; });
        rec.backBox = {
          x: Math.min.apply(null, xs), y: Math.min.apply(null, ys),
          w: Math.max.apply(null, xe) - Math.min.apply(null, xs),
          h: Math.max.apply(null, ye) - Math.min.apply(null, ys),
        };
      }

      // 相邻牌背是否重叠
      rec.overlaps = [];
      for (var i = 1; i < rec.backRects.length; i++) {
        var a = rec.backRects[i - 1], b = rec.backRects[i];
        var ovX = Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x);
        var ovY = Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y);
        if (ovX > 1 && ovY > 1) {
          rec.overlaps.push({ i: i - 1, overlapArea: ovX * ovY });
        }
      }

      // 副露区里的文字（用户要求：吃碰杠不要显示"上家/对家/下家"）
      if (meld) {
        rec.meldText = (meld.innerText || '').replace(/\s+/g, ' ').trim();
        rec.meldGroups = meld.querySelectorAll('.meldrow__group').length;
        rec.meldTiles = meld.querySelectorAll('.tile').length;
        rec.meldTileTransforms = Array.prototype.map.call(meld.querySelectorAll('.tile'), function (t) {
          return window.getComputedStyle(t).transform;
        });
        var mcs = window.getComputedStyle(meld);
        var rotEl = meld.querySelector('.meldrow__rot'); // 旧实现有这一层，新实现没有
        rec.meldBoxStyle = {
          w: mcs.width, h: mcs.height,
          flexDir: mcs.flexDirection,
          display: mcs.display,
          meldRect: rect(meld),
          meldComputedCol: mcs.gridColumnStart,
          meldJustifySelf: mcs.justifySelf,
          meldPos: mcs.position,
          rectOfTiles: Array.prototype.map.call(meld.querySelectorAll('.tile'), rect).slice(0, 3),
          offsetBox: { l: meld.offsetLeft, t: meld.offsetTop, w: meld.offsetWidth, h: meld.offsetHeight },
          rotEl: rotEl ? rect(rotEl) : null,
        };
      }
      // 副露内容的真实视觉边界。
      //
      // .meldrow 自己（和它内部的 .meldrow__rot）是带 rotate() 的，布局盒仍是
      // 【旋转前】的尺寸，所以 .meldrow 的包围盒会横跨整个座位、完全不能用来算间距。
      // 办法：给每一张副露牌加 0 尺寸标记点，取所有标记点的【并集】。
      // （只取首尾两点做对角线是不行的：180° 那家的首尾是斜对角，包围盒会缩水一半。）
      if (meld) {
        var mt = meld.querySelectorAll('.tile');
        var box = null;
        for (var ti = 0; ti < mt.length; ti++) {
          var host = mt[ti];
          if (!host || host.nodeType !== 1) {
            out.problems.push('副露第 ' + ti + ' 个 .tile 不是 Element: ' + host);
            continue;
          }
          var mk = document.createElement('i');
          mk.style.cssText = 'position:absolute;width:0;height:0;';
          host.appendChild(mk);
          var r = mk.getBoundingClientRect();
          if (!box) box = { x: r.left, y: r.top, x2: r.right, y2: r.bottom };
          else {
            box.x = Math.min(box.x, r.left);
            box.y = Math.min(box.y, r.top);
            box.x2 = Math.max(box.x2, r.right);
            box.y2 = Math.max(box.y2, r.bottom);
          }
          mk.remove();
        }
        if (box) {
          rec.meldContent = {
            x: Math.round(box.x), y: Math.round(box.y),
            w: Math.round(box.x2 - box.x), h: Math.round(box.y2 - box.y),
          };
        }
      }
      // 名字浮层的定位方式：必须不在主流里（否则又会把副露顶开）
      if (meta) {
        var cs = window.getComputedStyle(meta);
        rec.metaPos = cs.position;
        rec.metaTransform = cs.transform;
        rec.metaOffset = { l: meta.offsetLeft, t: meta.offsetTop, w: meta.offsetWidth, h: meta.offsetHeight };
        rec.metaParent = (meta.offsetParent && meta.offsetParent.className) || null;
        if (cs.position === 'absolute') rec.metaIsOverlay = true;
      }

      // 手牌内沿 → 副露内容起点的距离
      if (rec.backBox && rec.meldContent) {
        var hb = rec.backBox, mc = rec.meldContent;
        rec.meldGapContent = (pos === 'top')
          ? mc.y - (hb.y + hb.h)
          : (pos === 'left' ? mc.x - (hb.x + hb.w) : (hb.x - (mc.x + mc.w)));
      }

      // 名字条不能压到副露牌上（对家那一侧名字在副露附近，最容易叠上）
      if (meta && rec.meldContent) {
        var rn = rect(meta), q = rec.meldContent;
        var ox = Math.min(rn.x + rn.w, q.x + q.w) - Math.max(rn.x, q.x);
        var oy = Math.min(rn.y + rn.h, q.y + q.h) - Math.max(rn.y, q.y);
        rec.nameMeldOverlap = (ox > 2 && oy > 2) ? { w: ox, h: oy } : null;
      }
      // 名字条也不能压到自己的手牌牌背上（贴着手牌 ≠ 骑在牌上）
      if (meta && rec.backBox) {
        var rn2 = rect(meta), b2 = rec.backBox;
        var ox2 = Math.min(rn2.x + rn2.w, b2.x + b2.w) - Math.max(rn2.x, b2.x);
        var oy2 = Math.min(rn2.y + rn2.h, b2.y + b2.h) - Math.max(rn2.y, b2.y);
        rec.nameBackOverlap = (ox2 > 2 && oy2 > 2) ? { w: ox2, h: oy2 } : null;
      }
      out.seats[pos] = rec;
    });

    // 所有牌背：是否越出桌面 / 是否互相重叠
    var table = document.querySelector('.table');
    out.table = table ? rect(table) : null;
    var all = document.querySelectorAll('.tile');
    out.tileCount = all.length;
    Array.prototype.forEach.call(all, function (t, i) {
      var r = rect(t);
      out.tiles.push({ i: i, cls: t.className, ...r });
      if (out.table) {
        if (r.x < out.table.x - 1 || r.y < out.table.y - 1 ||
            r.x + r.w > out.table.x + out.table.w + 1 ||
            r.y + r.h > out.table.y + out.table.h + 1) {
          out.problems.push('牌越界: ' + t.className + ' ' + JSON.stringify(r));
        }
      }
    });

    var pre = document.createElement('pre');
    pre.id = 'probe';
    // 聊天相关：消息列表条目、各座位气泡、我的名字条、顶部信息条
    out.chatLogItems = Array.prototype.map.call(
      document.querySelectorAll('.chatlog__item'),
      function (li) { return (li.innerText || '').replace(/\s+/g, ' ').trim(); }
    );
    out.bubbles = {};
    ['top', 'left', 'right'].forEach(function (pos) {
      var b = document.querySelector('.seat--' + pos + ' .seatpanel__bubble');
      if (b) out.bubbles[pos] = (b.innerText || '').trim();
    });
    var mb = document.querySelector('.table__mybubble');
    out.myBubble = mb ? (mb.innerText || '').trim() : '';
    var sn = document.querySelector('.selfname');
    out.selfName = sn ? (sn.innerText || '').replace(/\s+/g, ' ').trim() : '';
    var hd = document.querySelector('.table__top-left');
    out.headerText = hd ? (hd.innerText || '').replace(/\s+/g, ' ').trim() : '';

    // 暗杠可见性：数每个座位副露区里的牌背张数
    out.anGang = {};
    ['left', 'right'].forEach(function (pos) {
      var seatEl = document.querySelector('.seat--' + pos);
      if (!seatEl) return;
      var tiles = seatEl.querySelectorAll('.meldrow .tile');
      var backs = seatEl.querySelectorAll('.meldrow .tile.is-back');
      out.anGang[pos] = { tiles: tiles.length, backs: backs.length };
    });
    var mineWrap = document.querySelector('.table__mymelds');
    if (mineWrap) {
      out.anGang.mine = {
        tiles: mineWrap.querySelectorAll('.tile').length,
        backs: mineWrap.querySelectorAll('.tile.is-back').length,
      };
    }

    /*
     * 各家副露的结构。生产构建里没有 window.__hn_pinia，所以只能从 DOM 反推：
     *   组数 = 副露副数；每组的牌数 = 3(吃/碰) 或 4(杠)；
     *   每组里 .is-called 的数量 = 横放（指向来源）的张数，0 表示没有来源。
     */
    out.meldsBySeat = {};
    ['top', 'left', 'right'].forEach(function (pos) {
      var seatEl = document.querySelector('.seat--' + pos);
      if (!seatEl) return;
      out.meldsBySeat[pos] = Array.prototype.map.call(
        seatEl.querySelectorAll('.meldrow__group'),
        function (g) {
          return {
            tiles: g.querySelectorAll('.tile').length,
            called: g.querySelectorAll('.tile.is-called').length,
            backs: g.querySelectorAll('.tile.is-back').length,
          };
        }
      );
    });
    var mineWrap = document.querySelector('.table__mymelds');
    if (mineWrap) {
      out.meldsBySeat.mine = Array.prototype.map.call(
        mineWrap.querySelectorAll('.meldrow__group'),
        function (g) {
          return {
            tiles: g.querySelectorAll('.tile').length,
            called: g.querySelectorAll('.tile.is-called').length,
            backs: g.querySelectorAll('.tile.is-back').length,
          };
        }
      );
    }

    // 组内缝隙：取某一组里相邻两张牌在纵向上的【有符号】间距
    // （正数=有缝，负数=重叠。用 abs 会把两者混为一谈，看不出真实情况。）
    (function () {
      var grp = document.querySelector('.seat--left .meldrow__group');
      if (!grp) return;
      var ts = grp.querySelectorAll('.tile');
      if (ts.length < 2) return;
      out.groupGaps = [];
      for (var i = 1; i < ts.length; i++) {
        var a = ts[i - 1].getBoundingClientRect(), b = ts[i].getBoundingClientRect();
        out.groupGaps.push(Math.round((b.top - a.bottom) * 10) / 10);
      }
    })();

    // 房间消息列表：点之前是收起的，点之后才展开
    out.chatLogHiddenByDefault = window.__logHiddenByDefault;
    out.chatLogVisible = !!document.querySelector('.table__chatlog');
    out.chatLogButton = !!Array.prototype.find.call(
      document.querySelectorAll('.table__bottom button'),
      function (b) { return (b.textContent || '').indexOf('📋') >= 0; }
    );

    // 常用语 / 消息按钮必须排在【发送按钮上方】
    (function () {
      var sendBtn = Array.prototype.find.call(
        document.querySelectorAll('.table__bottom button'),
        function (b) { return (b.textContent || '').trim() === '发送'; }
      );
      var tools = document.querySelector('.chat__tools');
      if (!sendBtn || !tools) return;
      var s = sendBtn.getBoundingClientRect(), t = tools.getBoundingClientRect();
      out.toolsAboveSend = t.bottom <= s.top + 1;
      out.toolsRect = { y: Math.round(t.top), bottom: Math.round(t.bottom) };
      out.sendRect = { y: Math.round(s.top), bottom: Math.round(s.bottom) };
    })();

    // 包牌提示：胡牌横幅里应当出现"包三道·代付三家"
    var bao = document.querySelector('.hu-banner__bao');
    out.baoText = bao ? (bao.innerText || '').trim() : '';
    // 独立提示（局内一发生就闪一下，配播报语音）
    var toast = document.querySelector('.bao-toast');
    out.baoToast = toast ? (toast.innerText || '').trim() : '';

    // 提示不能和"胡牌 + 番型"那块横幅重叠
    var banner = document.querySelector('.hu-banner');
    if (banner && toast) {
      var rb = banner.getBoundingClientRect(), rt = toast.getBoundingClientRect();
      var ox = Math.min(rb.right, rt.right) - Math.max(rb.left, rt.left);
      var oy = Math.min(rb.bottom, rt.bottom) - Math.max(rb.top, rt.top);
      out.noticeOverlapsBanner = ox > 1 && oy > 1;
      out.bannerRect = { y: Math.round(rb.top), bottom: Math.round(rb.bottom) };
      out.toastRect = { y: Math.round(rt.top), bottom: Math.round(rt.bottom) };
    }

    // 播报语音：记录是否调用了语音合成（素材缺失时的兜底路径）
    out.spoken = window.__hn_spoken || [];

    out.html = (document.getElementById('app') || {}).innerHTML || '(no #app)';
    // 注意：这里【不写】pre。写完牌桌那一轮之后还要跑"界面 phase"
    // （大厅/规则弹窗/战绩两级），最后统一输出，见最下面的 writeProbe()。
    return out;
  }

  function writeProbe(out) {
    var pre = document.createElement('pre');
    pre.id = 'probe';
    pre.textContent = JSON.stringify(out);
    document.body.appendChild(pre);
  }

  /* ==================== 界面 phase ====================
   *
   * 牌桌那一轮只验布局；大厅/规则弹窗/战绩两级这三块界面完全是另一条链路
   * （走 REST，不碰 WebSocket），所以单独走一轮：打桩 fetch 喂固定数据，
   * 真实挂载这些页面并检查渲染出来的东西。
   */
  var FIXTURE_LIST = {
    code: 200,
    data: {
      records: [
        { sessionId: 9, roomId: 'ROOM1', startTime: '2026-09-24T10:00:00',
          endTime: '2026-09-24T11:20:00', status: 2, totalScore: 7, rank: 1,
          /* 一级列表要在房号后面直接列四家名字（后端按 player_ids 的座位顺序给） */
          players: ['阿东', '阿南', '阿西', '我'] },
        { sessionId: 8, roomId: 'ROOM0', startTime: '2026-09-23T20:00:00',
          endTime: '2026-09-23T21:10:00', status: 2, totalScore: -4, rank: 3,
          players: ['电脑·南', '阿南', '电脑·北', '我'] },
      ],
      total: 2,
      pages: 1,
      pageNum: 1,
    },
  };

  var FIXTURE_DETAIL = {
    code: 200,
    data: {
      roomId: 'ROOM1',
      /*
       * ⚠️ overview.players 必须与后端 PlayerTotalVO 完全一致：
       *    {userId, seat, totalScore, nickname} —— 【没有 isMe】。
       * 这里曾经被我加上 isMe:true，前端照着 isMe 找人也就"能跑通"，
       * 于是"本场我的总分恒为 0"这个真 bug 被夹具自己掩盖了。
       * 夹具一旦比真实接口"更友好"，测试就失去意义了。
       */
      overview: {
        winCount: 3,
        highestFan: '碰碰胡',
        players: [
          { userId: 11, nickname: '阿东', seat: 0, totalScore: -3 },
          { userId: 12, nickname: '阿南', seat: 1, totalScore: -2 },
          { userId: 13, nickname: '阿西', seat: 2, totalScore: -2 },
          { userId: 42, nickname: '我', seat: 3, totalScore: 7 },
        ],
      },
      rounds: [
        {
          roundNum: 1, isDraw: false, winType: 0, winner: '我', winTile: 30,
          fanTypes: ['碰碰胡'],
          winHand: [30, 30, 30, 5, 5, 5, 9, 9, 9, 18, 18, 18, 27, 27],
          winMelds: [{ type: 'PENG', tiles: [31, 31, 31] }],
          dealerSeat: 'EAST', windIdx: 0, dealerNickname: '阿东',
          participants: [
            { userId: 42, nickname: '我', seat: 3, scoreChange: 6, isWinner: true, isMe: true,
              detail: { notes: ['真花·四季', '包三道·代付三家'], flowers: [34, 35, 36, 37],
                        gangs: [{ type: 'AN_GANG', tiles: [27, 27, 27, 27] }] } },
            { userId: 13, nickname: '阿西', seat: 2, scoreChange: -6, isWinner: false, isMe: false,
              detail: { notes: ['明杠'], flowers: [], gangs: [{ type: 'GANG', tiles: [20, 20, 20, 20] }] } },
          ],
        },
        { roundNum: 2, isDraw: true, participants: [] },
      ],
    },
  };

  /** 打桩 fetch：只拦战绩/用户接口，其余放行（牌桌那条链路的请求照旧） */
  var realFetch = window.fetch ? window.fetch.bind(window) : null;
  window.fetch = function (url, opts) {
    var u = String(url);
    function jsonResp(body) {
      return Promise.resolve({
        ok: true,
        status: 200,
        json: function () { return Promise.resolve(body); },
        text: function () { return Promise.resolve(JSON.stringify(body)); },
      });
    }
    if (u.indexOf('/api/records/byRoom') >= 0) return jsonResp(FIXTURE_DETAIL);
    if (/\/api\/records\/\d+/.test(u)) return jsonResp(FIXTURE_DETAIL);
    if (u.indexOf('/api/records') >= 0) return jsonResp(FIXTURE_LIST);
    if (u.indexOf('/api/user/me') >= 0) {
      return jsonResp({ code: 200, data: { id: 42, nickname: '我', username: 'probe' } });
    }
    if (u.indexOf('/api/room/pending') >= 0) return jsonResp({ code: 200, data: { exists: false } });
    /*
     * 加入房间的预检：只有 123456 这个码"存在"，其余一律按不存在返回。
     * 顺便把调用记下来，用来断言"格式不对时连请求都不该发"。
     */
    if (u.indexOf('/api/room/check') >= 0) {
      window.__hn_checkCalls = window.__hn_checkCalls || [];
      window.__hn_checkCalls.push(u);
      var mc = /[?&]code=([^&]*)/.exec(u);
      var code = mc ? decodeURIComponent(mc[1]) : '';
      if (code === '123456') return jsonResp({ code: 200, message: '操作成功', data: {} });
      return jsonResp({ code: 400, message: '房间不存在或已解散', data: null });
    }
    return realFetch ? realFetch(url, opts) : jsonResp({ code: 200, data: null });
  };

  var screens = {};

  function clickByText(sel, text) {
    var el = Array.prototype.find.call(document.querySelectorAll(sel), function (b) {
      return (b.textContent || '').indexOf(text) >= 0;
    });
    if (el) el.click();
    return !!el;
  }

  /**
   * 轮询等待某个选择器出现。
   *
   * 不能只写死 setTimeout 延时：改 hash 会让 router-view 重新挂载，
   * 上一步的 DOM 会被整块替换掉，期间去查/点元素就是"点了个已经不存在的按钮"
   * （第一次就是这么失败的：设置和规则弹窗全没打开）。
   */
  function waitFor(sel, cb, tries) {
    var n = tries == null ? 40 : tries;
    (function poll() {
      var el = document.querySelector(sel);
      if (el || n <= 0) return cb(el);
      n--;
      setTimeout(poll, 50);
    })();
  }

  function screenLobby(done) {
    location.hash = '#/lobby';
    waitFor('.lobby__topbtns button', function () {
      var lo = document.querySelector('.lobby');
      screens.lobbyMounted = !!lo;
      screens.lobbyText = (document.body.innerText || '').replace(/\s+/g, ' ');
      screens.hasBeian = !!(lo && lo.querySelector('.beian'));
      screens.beianText = lo && lo.querySelector('.beian') ? lo.querySelector('.beian').innerText.trim() : '';
      /*
       * 这里只【断言存在】不点击：「战绩查询」一点就会导航到战绩页，
       * 大厅随即卸载，后面两步（设置/规则弹窗）就没得点了。
       * 战绩页本身在后面的 screenRecords/screenList 里单独验。
       */
      screens.hasRecordsEntry = !!Array.prototype.find.call(
        lo ? lo.querySelectorAll('.lobby__dock button') : [],
        function (b) { return (b.textContent || '').indexOf('战绩查询') >= 0; }
      );

      clickByText('.lobby__topbtns button', '设置');
      waitFor('.sd__dlg', function (sd) {
        screens.settingsMounted = !!sd;
        screens.settingsTabs = sd
          ? Array.prototype.map.call(sd.querySelectorAll('.sd__tab'), function (b) { return b.innerText.trim(); })
          : [];
        var close = sd && sd.querySelector('.sd__head button');
        if (close) close.click();
        setTimeout(function () {
          waitFor('.lobby__main button', function () { screenRule(done); });
        }, 120);
      }, 20);
    });
  }

  function screenRule(done) {
    clickByText('.lobby__main button', '创建新房间');
    waitFor('.rd__dlg', function (dlg) {
      screens.ruleMounted = !!dlg;
      screens.ruleInputs = dlg ? dlg.querySelectorAll('input[type="number"]').length : 0;
      screens.ruleRadios = dlg ? dlg.querySelectorAll('input[name="fanGate"]').length : 0;
      screens.ruleText = dlg ? dlg.textContent.replace(/\s+/g, ' ') : '';
      var close = dlg && dlg.querySelector('.rd__head button');
      if (close) close.click();
      setTimeout(done, 150);
    }, 20);
  }

  /** 往 Vue 的 v-model 输入框里写值：必须派发 input 事件，直接改 value 不会同步 */
  function setInput(el, v) {
    el.value = v;
    el.dispatchEvent(new Event('input', { bubbles: true }));
  }

  /**
   * 加入房间：格式错 / 房间不存在都必须在【大厅原地】报错，不能跳走。
   * 最后再用一个"存在的房间"验证正常路径确实会跳。
   */
  function screenJoin(done) {
    location.hash = '#/lobby';
    waitFor('.joinrow__input', function (input) {
      if (!input) { screens.joinInput = false; return done(); }
      screens.joinInput = true;
      var btn = document.querySelector('.joinrow button');
      window.__hn_checkCalls = [];

      // ① 格式不对（2 位）：本地就该拦住，连预检请求都不发
      setInput(input, '12');
      btn.click();
      setTimeout(function () {
        screens.joinBadFormat = {
          hash: location.hash,
          err: (document.querySelector('.card__error') || {}).innerText || '',
          calls: (window.__hn_checkCalls || []).slice(),
        };

        // ② 格式对、但房间不存在（打桩只认 123456）：后端拦下，仍然不跳
        setInput(input, '999999');
        btn.click();
        setTimeout(function () {
          screens.joinNotFound = {
            hash: location.hash,
            err: (document.querySelector('.card__error') || {}).innerText || '',
            calls: (window.__hn_checkCalls || []).slice(),
          };

          // ③ 存在的房间：这次应当真的跳到房间页
          setInput(input, '123456');
          btn.click();
          setTimeout(function () {
            screens.joinOk = { hash: location.hash };
            done();
          }, 400);
        }, 400);
      }, 250);
    }, 40);
  }

  function screenGameHistory(done) {
    /*
     * 回到牌桌，点「局内战绩」——它现在应该拉 /api/records/byRoom 并用
     * 与大厅战绩查询二级界面【同一个组件】渲染，而不是只显示内存里那几条。
     */
    location.hash = '#/game?code=PROBE1&seat=NORTH';
    waitFor('.rh__btn', function (btn) {
      screens.gameHistoryBtn = !!btn;
      if (!btn) return done();
      screens.gameHistoryLabel = (btn.innerText || '').replace(/\s+/g, ' ').trim();
      btn.click();
      waitFor('.rh__modal .ov__total', function (tot) {
        screens.gameHistoryHasDetail = !!tot;
        screens.gameHistoryTotal = tot ? (tot.innerText || '').trim() : '';
        var modal = document.querySelector('.rh__modal');
        screens.gameHistoryRounds = modal ? modal.querySelectorAll('.round').length : 0;
        screens.gameHistoryPlayers = modal ? modal.querySelectorAll('.ptag').length : 0;
        screens.gameHistoryIsFallback = !!(modal && modal.querySelector('.rh__msg--warn'));
        var close = modal && modal.querySelector('.rh__head button');
        if (close) close.click();
        setTimeout(done, 150);
      }, 30);
    });
  }

  function screenRecords(done) {
    location.hash = '#/records?roomId=ROOM1';
    waitFor('.records .round', function () {
      var r = document.querySelector('.records');
      screens.recordsMounted = !!r;
      screens.recordsRoomChip = r && r.querySelector('.chip--gold') ? r.querySelector('.chip--gold').innerText.trim() : '';
      screens.roundCount = r ? r.querySelectorAll('.round').length : 0;
      screens.ovPlayers = r ? r.querySelectorAll('.ptag').length : 0;
      screens.notesShown = r
        ? Array.prototype.map.call(r.querySelectorAll('.prow__notes'), function (n) { return n.innerText.trim(); })
        : [];
      screens.gangMelds = r ? r.querySelectorAll('.prow .meld').length : 0;
      screens.detailText = r ? r.innerText.replace(/\s+/g, ' ') : '';

      // 「本场我的总分」必须是真实数字，不能恒为 0
      var tot = r && r.querySelector('.ov__total');
      screens.myTotalText = tot ? (tot.innerText || '').trim() : '';
      screens.meTagRows = r ? r.querySelectorAll('.ptag.is-me').length : 0;

      // 胡牌那张牌应当是小尺寸（size=sm → 32x44），不能撑成原图大小
      var wt = r && r.querySelector('.round__tile .tile');
      if (wt) {
        var wr = wt.getBoundingClientRect();
        var wcs = window.getComputedStyle(wt);
        screens.winTileBox = { w: Math.round(wr.width), h: Math.round(wr.height) };
        screens.winTileCls = wt.className;
        screens.winTileTw = wcs.getPropertyValue('--tw').trim();
        screens.winTileTh = wcs.getPropertyValue('--th').trim();
      }
      // 顺带量一张明细里的小牌做对照
      var xt = r && r.querySelector('.prow__tiles .tile');
      if (xt) {
        var xr = xt.getBoundingClientRect();
        screens.smallTileBox = { w: Math.round(xr.width), h: Math.round(xr.height) };
      }
      done();
    });
  }

  function screenList(done) {
    location.hash = '#/records';
    waitFor('.recrow', function () {
      var r = document.querySelector('.records');
      screens.listRows = r ? r.querySelectorAll('.recrow').length : 0;
      screens.listText = r ? r.innerText.replace(/\s+/g, ' ') : '';
      // 一级列表每行房号后面要列四家名字（打桩数据里给了 players）
      screens.listPlayers = r
        ? Array.prototype.map.call(r.querySelectorAll('.recrow__players'), function (e) {
            return e.innerText.replace(/\s+/g, ' ').trim();
          })
        : [];
      var row = r && r.querySelector('.recrow');
      if (!row) return done();
      row.click();
      waitFor('.records .round', function () {
        screens.detailFromList = document.querySelectorAll('.round').length;
        done();
      });
    });
  }

  // 等 Vue 挂载后进牌桌。用 location.hash 而不是 router.push：
  // 这里拿不到 router 实例，而应用是 hash 路由，改 hash 就是完整的导航。
  setTimeout(function () {
    try { location.hash = '#/game?code=PROBE1&seat=NORTH'; } catch (e) {}
  }, 300);

  // 等 Vue 挂载 + 路由切到牌桌 + 消息走完一轮
  //
  // 房间消息列表面板默认是【收起】的，所以先点一下按钮把它展开，
  // 再等一拍让 Vue 把列表渲染出来，最后才测量。
  setTimeout(function () {
    // 先记下"点之前"的状态：默认必须是收起的
    window.__logHiddenByDefault = !document.querySelector('.table__chatlog');
    var btn = Array.prototype.find.call(
      document.querySelectorAll('.table__bottom button'),
      function (b) { return (b.textContent || '').indexOf('📋') >= 0; }
    );
    if (btn) btn.click();
  }, 1400);

  setTimeout(function () {
    /*
     * 手机适配探针（docs/mobile-probe.py）会复用这份 shim，但它有自己的
     * 导航/测量节奏。两套 hash 导航同时跑会互相打断，量到的是"对方刚切走"
     * 的中间态，所以这里让路。
     */
    if (window.__HN_MOBILE__) return;
    try {
      var out = measure();
      /*
       * 牌桌那一轮测完，接着跑"界面 phase"：
       *   大厅（备案/战绩入口/设置弹窗）→ 规则弹窗 → 战绩两级（?roomId= 与列表点入）
       * 全部结果并进同一个 out，最后统一写 pre。
       */
      screenLobby(function () {
        screenGameHistory(function () {
          screenRecords(function () {
            screenList(function () {
              // 最后一步：加入房间的三种情况（格式错/不存在/存在）。
              // 放最后是因为成功那一档会真的跳到 /room，把大厅卸掉。
              screenJoin(function () {
                out.screens = screens;
                writeProbe(out);
              });
            });
          });
        });
      });
    } catch (e) {
      // 探针自己炸了也要留证据，否则只会看到"没拿到测量结果"
      var pre = document.createElement('pre');
      pre.id = 'probe';
      pre.textContent = JSON.stringify({
        probeError: String(e && e.stack ? e.stack : e),
        errs: window.__hn_errs,
        text: document.body.innerText,
      });
      document.body.appendChild(pre);
    }
  }, 1800);
})();
</script>
"""


def find_chrome():
    for c in CHROME_CANDIDATES:
        if c and os.path.isfile(c):
            return c
    return None


def build_probe_html(dest):
    with open(os.path.join(STATIC, "index.html"), "r", encoding="utf-8") as f:
        html = f.read()
    # 在第一个 <script type="module"> 之前插入 shim，保证它先执行
    m = re.search(r'<script type="module"', html)
    if not m:
        raise SystemExit("index.html 里找不到 module 入口")
    html = html[: m.start()] + SHIM + "\n" + html[m.start():]
    with open(dest, "w", encoding="utf-8") as f:
        f.write(html)


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a):
        pass


def start_server(root):
    """
    为什么要起 HTTP 服务而不是直接 file:// 打开：

    index.html 的入口是 <script type="module" crossorigin>。在 file:// 协议下，
    Chrome 会对模块脚本做同源检查并直接拒绝（页面全空，只报一个
    没有文件名的 "error @ :0"），根本进不到应用代码。
    用 HTTP 托管同一份 static/ 就完全没有这个问题。
    """
    port = free_port()
    handler = functools.partial(QuietHandler, directory=root)
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", port), handler)
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    return srv, port


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep", action="store_true", help="保留临时 HTML")
    ap.add_argument("--shot", default=None, help="同时截图到指定 png")
    ap.add_argument("--wait", type=int, default=3000, help="虚拟时间预算 ms")
    ap.add_argument("--dump-body", action="store_true", help="没测到结果时打印 body HTML")
    ap.add_argument("--dump-dom", action="store_true", help="打印完整 DOM 后退出")
    args = ap.parse_args()

    chrome = find_chrome()
    if not chrome:
        print("[probe] 找不到 Chrome/Edge，跳过")
        return 2

    # 临时目录里放：probe.html（改造过的入口）+ static/ 的全部资源副本，
    # 这样 /assets/... 与 /img/... 这些绝对路径都能解析。
    tmpdir = tempfile.mkdtemp(prefix="hn-probe-")
    page = os.path.join(tmpdir, "index.html")
    build_probe_html(page)
    for sub in os.listdir(STATIC):
        if sub in ("index.html",):
            continue
        s = os.path.join(STATIC, sub)
        d = os.path.join(tmpdir, sub)
        if os.path.isdir(s):
            shutil.copytree(s, d, dirs_exist_ok=True)
        else:
            shutil.copy2(s, d)

    srv, port = start_server(tmpdir)
    url = "http://127.0.0.1:%d/index.html" % port
    profile = os.path.join(tmpdir, "profile")

    cmd = [
        chrome,
        "--headless=new",
        "--disable-gpu",
        "--no-sandbox",
        "--hide-scrollbars",
        "--no-proxy-server",
        "--window-size=1280,720",
        "--force-device-scale-factor=1",
        "--user-data-dir=" + profile,
        "--virtual-time-budget=%d" % args.wait,
        "--dump-dom",
        url,
    ]
    if args.shot:
        cmd.insert(-2, "--screenshot=" + os.path.abspath(args.shot))

    print("[probe] " + chrome)
    print("[probe] " + url)
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=args.wait / 1000.0 + 90)
    finally:
        srv.shutdown()
    dom = r.stdout or ""
    if args.dump_dom:
        b = re.search(r"<body.*?>(.*)</body>", dom, re.S)
        body = b.group(1) if b else dom
        body = re.sub(r"<script[^>]*>.*?</script>", "[script]", body, flags=re.S)
        print(body[:6000])
        return 0

    m = re.search(r'<pre id="probe">(.*?)</pre>', dom, re.S)
    if not m:
        print("[probe] 没拿到测量结果；DOM 长度=%d" % len(dom))
        print((r.stderr or "")[:2000])
        if args.dump_body:
            b = re.search(r"<body.*?>(.*)</body>", dom, re.S)
            body = b.group(1) if b else dom
            body = re.sub(r"<script.*?</script>", "[script]", body, flags=re.S)
            print(body[:4000])
        if args.keep:
            print("[probe] 临时文件: " + page)
        return 1

    data = json.loads(m.group(1).replace("&quot;", '"').replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">"))
    if data.get("probeError"):
        print("[probe] 探针自己报错：")
        print(data["probeError"])
        print("[probe] 页面错误 = %s" % json.dumps(data.get("errs"), ensure_ascii=False)[:1500])
        print("[probe] 页面文字 = %r" % (data.get("text") or "")[:300])
        return 1
    print("[probe] viewport = %s  hash=%s  sockets=%s" % (data["viewport"], data.get("hash"), data.get("sockets")))
    if data.get("errs"):
        print("[probe] 页面错误 %d 条:" % len(data["errs"]))
        for e in data["errs"][:12]:
            print("  " + e)
        # 页面报错必须直接判失败。
        # 曾经的教训：MeldRow 里引用了一个已删除的变量，Vue 在渲染时抛错，
        # 但错误只是进 console.error，页面其余部分照常渲染，
        # 所有布局断言全绿 —— 探针"通过"了，实际功能是坏的。
        fails.append("页面有 %d 条运行时错误" % len(data["errs"]))
    print("[probe] 桌面 = %s" % (data["table"],))
    print("[probe] .tile 总数 = %s" % data["tileCount"])

    # ---- 断言 ----
    #
    # 隐藏的坑：应用会按视口把 1280x720 的桌面整体 scale 到窗口尺寸，
    # 所以这里的像素不是设计像素（本次实测 scale≈0.789）。所有阈值都按
    # 屏上像素给，并且用"是否越出桌面包围盒"这种不依赖 scale 的判断为主。
    fails = []

    def check(cond, msg, extra=""):
        if cond:
            print("  [ok]   " + msg)
        else:
            fails.append(msg)
            print("  [FAIL] " + msg + ("  " + extra if extra else ""))

    table = data.get("table") or {}
    scale_hint = ""
    if table.get("w"):
        scale_hint = "（桌面 %dx%d，设计 1280x720 → scale≈%.3f）" % (
            table["w"], table["h"], table["w"] / 1280.0)

    for pos in ("top", "left", "right"):
        s = data["seats"].get(pos)
        if not s:
            check(False, "存在座位 %s" % pos)
            continue
        print("\n--- %s ---" % pos)
        print("  座位盒 %s   牌背盒 %s" % (s.get("seat"), s.get("backBox")))
        print("  座位 grid: %s" % s.get("seatGrid"))
        print("  backs 面板: %s  %s" % (s.get("backs"), s.get("backsStyle")))
        for gi in (s.get("gridItems") or []):
            print("    网格子项 col=%s row=%s  %s  %s" % (gi["col"], gi["row"], gi["r"], gi["cls"]))
        print("  名字盒 %s   副露盒 %s" % (s.get("meta"), s.get("meld")))
        print("  名字文本 = %r" % s.get("metaText"))
        print("  副露区文字 = %r（组数 %s，牌数 %s）" % (s.get("meldText"), s.get("meldGroups"), s.get("meldTiles")))
        print("  名字盒 = %s   副露内容 = %s   名字/副露重叠 = %s" % (
            s.get("meta"), s.get("meldContent"), s.get("nameMeldOverlap")))
        print("  名字 computed: pos=%s transform=%s offset=%s parent=%s" % (
            s.get("metaPos"), s.get("metaTransform"), s.get("metaOffset"), s.get("metaParent")))
        print("  meldrow 样式: %s" % s.get("meldBoxStyle"))
        print("  副露牌 transform: %s" % s.get("meldTileTransforms"))
        rr = s["backRects"]
        print("  牌背 %d 张，相邻重叠 %d 处" % (s["backCount"], len(s["overlaps"])))
        print("  前 4 张 = %s" % (json.dumps(rr[:4]) if rr else "(无牌背)"))

        check(s["backCount"] == 13, "%s 家手牌 13 张牌背（实际 %d）" % (pos, s["backCount"]))
        check(len(s["overlaps"]) == 0, "%s 家牌背互不重叠（重叠 %d 处）" % (pos, len(s["overlaps"])))

        # 间距：用"相邻牌中心步长 − 牌在该轴上的尺寸"。
        # 不要用包围盒相减 —— 元素带 transform，包围盒是轴对齐的，会算出负数。
        steps = []
        dims = []
        for i, r in enumerate(rr):
            # 沿排列轴，这张牌真正占的尺寸 = 包围盒在该轴上的长度
            dims.append(r["w"] if pos == "top" else r["h"])
            if i:
                a = rr[i - 1]
                if pos == "top":
                    steps.append(abs((r["x"] + r["w"] / 2.0) - (a["x"] + a["w"] / 2.0)))
                else:
                    steps.append(abs((r["y"] + r["h"] / 2.0) - (a["y"] + a["h"] / 2.0)))
        if steps and dims:
            axis_dim = max(dims)  # 实际占位取包围盒在该轴上的长度
            gaps = [round(st - axis_dim, 1) for st in steps]
            print("  步长 %s → 间距 %s（牌在该轴占 %s px）" % (
                sorted(set(round(x, 1) for x in steps)), sorted(set(gaps)), axis_dim))
            check(-0.5 <= min(gaps) and max(gaps) <= 3.0,
                  "%s 家牌背紧挨着且不重叠（间距 %.1f~%.1f px）" % (pos, min(gaps), max(gaps)))
        check(s.get("meldText") == "", "%s 家副露区没有任何文字（上家/对家/下家 角标已去掉）" % pos,
              "实际 %r" % s.get("meldText"))
        check((s.get("meldGroups") or 0) > 0, "%s 家渲染出了副露组（实际 %s）" % (pos, s.get("meldGroups")))
        if s.get("meldContent") is not None:
            print("  副露内容视觉边界 = %s" % s["meldContent"])
        if s.get("meldGapContent") is not None:
            # 允许 ±2px：牌有 1px 圆角，1px 的贴合在视觉上就是"贴住"
            check(-2 <= s["meldGapContent"] <= 10,
                  "%s 家副露紧贴手牌（内容起点到牌背内沿 %s px）" % (pos, s["meldGapContent"]))
        check(s.get("metaIsOverlay") is True,
              "%s 家名字条是浮层（不占主流，否则会把副露顶开）" % pos)
        check(s.get("nameMeldOverlap") is None,
              "%s 家名字条没有压在副露牌上" % pos,
              "重叠 %s" % s.get("nameMeldOverlap"))
        check(s.get("nameBackOverlap") is None,
              "%s 家名字条没有压在手牌牌背上" % pos,
              "重叠 %s" % s.get("nameBackOverlap"))

        # 副露要比手牌更靠近桌心（从桌边到中心：手牌 → 名字 → 副露 → 牌河）
        bb, mb = s.get("backBox"), s.get("meld")
        if bb and mb:
            if pos == "top":
                d_back = abs((bb["y"] + bb["h"] / 2) - (table["y"] + table["h"] / 2))
                d_meld = abs((mb["y"] + mb["h"] / 2) - (table["y"] + table["h"] / 2))
            elif pos == "left":
                d_back = abs((bb["x"] + bb["w"] / 2) - (table["x"] + table["w"] / 2))
                d_meld = abs((mb["x"] + mb["w"] / 2) - (table["x"] + table["w"] / 2))
            else:
                d_back = abs((bb["x"] + bb["w"] / 2) - (table["x"] + table["w"] / 2))
                d_meld = abs((mb["x"] + mb["w"] / 2) - (table["x"] + table["w"] / 2))
            check(d_meld < d_back, "%s 家副露比手牌更靠桌心（%d < %d）" % (pos, d_meld, d_back))

        # 用户定的顺序：从屏幕最外侧到桌心 = 名字 → 手牌 → 副露
        #
        # 注意不能写成"名字在最左 / 最右"——三家方向不同（下家是镜像的）。
        # 正确判据是【离屏幕外边界的距离】，三家统一。
        nm, hb2 = s.get("meta"), s.get("backBox")
        mc = s.get("meldContent")
        if nm and hb2 and mc:

            def edge_dist(r):
                if pos == "top":
                    return r["y"]                    # 上边：y 越小越靠外
                if pos == "left":
                    return r["x"]                    # 左边：x 越小越靠外
                return -(r["x"] + r["w"])            # 右边：右边界越大越靠外（取负统一比较方向）

            d_name, d_hand, d_meld2 = edge_dist(nm), edge_dist(hb2), edge_dist(mc)
            print("  离屏幕边（越小越靠外）: 名字=%d 手牌=%d 副露=%d" % (d_name, d_hand, d_meld2))
            check(d_name <= d_hand, "%s 家名字比手牌更靠屏幕外侧" % pos)
            check(d_hand < d_meld2, "%s 家手牌比副露更靠屏幕外侧（副露在里侧）" % pos)

        # 副露牌的整体朝向必须和该家手牌一致 —— 对家漏了 180° 就是"方向反了"
        want = {"top": 180, "left": 90, "right": -90}[pos]
        angles = []
        for m in (s.get("meldTileTransforms") or []):
            nums = [float(x) for x in re.findall(r"-?\d+\.?\d*", m)]
            if len(nums) < 4:
                continue
            angles.append(round(math.degrees(math.atan2(nums[1], nums[0]))) % 360)
        if angles:
            # 被叫的那张会再垂直一次（+90°）。归一化到 0/90 两个等价角上比较，
            # 免得 270 和 -90 被当成不同的角度。
            def norm(a):
                a %= 180
                return a if a in (0, 90) else round(a)

            want_a, want_b = norm(want % 180), norm((want + 90) % 180)
            bad = [a for a in angles if norm(a) not in (want_a, want_b)]
            check(not bad,
                  "%s 家副露牌朝向与手牌一致（期望 %d°/%d°，实际样本 %s）" % (pos, want_a, want_b, sorted(set(angles))),
                  "异常角度 %s" % bad)

    if data.get("problems"):
        print("\n[probe] 越界/缺元素 %d 条:" % len(data["problems"]))
        for p in data["problems"][:20]:
            print("  " + p)
        fails.append("有元素越出桌面包围盒")
    else:
        print("\n[probe] 没有元素越出桌面 %s" % scale_hint)

    t = data.get("text") or ""
    check("上家" not in t and "对家" not in t and "下家" not in t,
          "整个页面不再出现「上家/对家/下家」文字")

    # ---- 聊天相关的界面 ----
    print("\n--- 聊天 / 文案 ---")
    log_items = data.get("chatLogItems") or []
    print("  消息列表 %d 条: %s" % (len(log_items), json.dumps(log_items, ensure_ascii=False)[:300]))
    check(len(log_items) == 4, "消息列表收集到 4 条（实际 %d）" % len(log_items))
    check(any("碰" in x for x in log_items), "消息列表含对手发的「碰！」")
    check(any("我来了" in x for x in log_items), "消息列表含我自己发的消息")
    check(data.get("chatLogButton") is True, "底部有「房间消息」按钮（📋，在常用语按钮旁）")
    check(data.get("chatLogHiddenByDefault") is True,
          "消息列表默认收起（点按钮才展开）",
          "实际 点之前 visible=%s" % data.get("chatLogVisible"))
    check(data.get("chatLogVisible") is True, "点按钮后消息列表展开")

    # ---- 包牌提示 ----
    print("\n--- 包牌提示 / 按钮位置 ---")
    print("  胡牌横幅内提示 = %r（应为空）" % data.get("baoText"))
    check(not (data.get("baoText") or ""),
          "包牌文字不再塞进胡牌横幅（不与番型重叠）",
          "实际 %r" % data.get("baoText"))
    print("  包牌 toast = %r" % data.get("baoToast"))
    check("包四道" in (data.get("baoToast") or ""),
          "结算时包三四道有独立文字提示",
          "实际 %r" % data.get("baoToast"))
    check("包三道" not in (data.get("baoToast") or ""),
          "吃碰杠那一刻的包牌提示【只出声不出文字】",
          "实际 %r" % data.get("baoToast"))
    print("  横幅 %s / 提示 %s / 重叠=%s"
          % (data.get("bannerRect"), data.get("toastRect"), data.get("noticeOverlapsBanner")))
    check(data.get("noticeOverlapsBanner") is False,
          "提示与胡牌横幅（含番型）不重叠")

    spoken = data.get("spoken") or []
    print("  播报语音 = %s" % json.dumps(spoken, ensure_ascii=False))
    check(any("包三道" in s for s in spoken),
          "吃碰杠时包三道有语音播报",
          "实际 %s" % json.dumps(spoken, ensure_ascii=False))
    check(any("包四道" in s for s in spoken),
          "结算时包四道也有语音播报",
          "实际 %s" % json.dumps(spoken, ensure_ascii=False))
    check(any("真花" in s for s in spoken),
          "花分（真花）仍有语音播报",
          "实际 %s" % json.dumps(spoken, ensure_ascii=False))
    # 旧版行为：花分只出声【不出字】，避免每把都往牌桌中间糊提示
    check("真花" not in (data.get("baoToast") or ""),
          "花分不在屏幕上显示文字（照旧版只读语音）",
          "实际 %r" % data.get("baoToast"))

    print("  工具按钮行 y=%s，发送按钮 y=%s" % (data.get("toolsRect"), data.get("sendRect")))
    check(data.get("toolsAboveSend") is True,
          "常用语/消息按钮排在发送按钮上方")

    # ---- 补杠：不能留着原来的碰 ----
    print("\n--- 副露 ---")
    ms = data.get("meldsBySeat") or {}
    print("  各家副露（tiles/called/backs）: %s" % json.dumps(ms, ensure_ascii=False))
    south = ms.get("top") or []   # 我把 SOUTH 摆在 top 位
    check(len(south) == 2,
          "补杠是【替换】不是新增：该家只剩 2 副（吃 + 杠）（实际 %d 副：%s）"
          % (len(south), json.dumps(south, ensure_ascii=False)))
    check(any(g["tiles"] == 4 for g in south), "该家有 1 副 4 张的杠")
    check(all(g["tiles"] != 3 or g["called"] == 1 for g in south),
          "3 张那副是吃（有横放指向来源）")
    gang = [g for g in south if g["tiles"] == 4]
    if gang:
        print("  补杠组: %s" % gang[0])
        check(gang[0]["called"] == 1,
              "补杠仍指向原来点碰的那家（有 1 张横放）",
              "实际 called=%s" % gang[0]["called"])

    # ---- 暗杠可见性 ----
    ag = data.get("anGang") or {}
    print("  暗杠牌背统计: %s" % json.dumps(ag, ensure_ascii=False))
    # 别人家的暗杠：在副露里找那副"4 张且没有来源"的组，它必须 4 张全是牌背
    right_groups = ms.get("right") or []
    hidden_gang = [g for g in right_groups if g["tiles"] == 4 and g["called"] == 0]
    check(bool(hidden_gang), "别人家有 1 副暗杠（4 张、无来源）")
    if hidden_gang:
        print("  别人家的暗杠组: %s" % hidden_gang[0])
        check(hidden_gang[0]["backs"] == 4,
              "别人家的暗杠 4 张全是牌背（对'我'不可见）",
              "实际 backs=%s" % hidden_gang[0]["backs"])
    mine_groups = ms.get("mine") or []
    an = [g for g in mine_groups if g["tiles"] == 4]
    if an:
        print("  我的暗杠组: %s" % an[0])
        check(an[0]["backs"] == 3,
              "自己的暗杠是 3 张牌背 + 1 张牌面（backs=%s）" % an[0]["backs"])

    # ---- 侧边副露组内无缝 ----
    gaps = data.get("groupGaps") or []
    print("  侧边组内相邻牌纵向间距（正=缝，负=叠）: %s px" % gaps)
    check(bool(gaps) and all(g <= 1 for g in gaps),
          "上家副露组内没有缝隙（实测 %s px，≤1px 视为贴死）" % gaps)

    bubbles = data.get("bubbles") or {}
    print("  气泡: %s" % json.dumps(bubbles, ensure_ascii=False))
    check(bool(bubbles.get("top")), "对家位置出现消息气泡")
    check(bool(bubbles.get("left")), "上家位置出现消息气泡")
    check(bool(data.get("myBubble")), "我自己的位置出现消息气泡")

    self_name = data.get("selfName") or ""
    print("  我的名字条: %r" % self_name)
    check("币" in self_name, "自己的名字旁边显示了金币")

    header = data.get("headerText") or ""
    print("  顶部信息条: %r" % header)
    check("令" in header, "顶部显示「X令 · 底X」")
    check("可吃碰杠胡" not in t, "不再展示「轮到你 · 可吃碰杠胡」")

    # ---- 大厅 / 规则弹窗 / 战绩两级 ----
    scr = data.get("screens") or {}
    print("\n--- 大厅 / 规则 / 战绩 ---")
    check(scr.get("lobbyMounted") is True, "大厅页能挂载")
    print("  备案元素: %s  文本=%r" % (scr.get("hasBeian"), scr.get("beianText")))
    check(scr.get("hasBeian") is True and "ICP备" in (scr.get("beianText") or ""),
          "大厅底部有备案号", "实际 %r" % scr.get("beianText"))
    check(scr.get("hasRecordsEntry") is True, "大厅底栏有「战绩查询」入口")

    print("  设置弹窗: mounted=%s tabs=%s" % (scr.get("settingsMounted"), scr.get("settingsTabs")))
    check(scr.get("settingsMounted") is True, "设置弹窗能打开")
    tabs = scr.get("settingsTabs") or []
    check(any("昵称" in x for x in tabs) and any("密码" in x for x in tabs),
          "设置弹窗含「修改昵称」「修改密码」两个页签", "实际 %s" % tabs)

    print("  规则弹窗: mounted=%s 数值项=%s 模式选项=%s"
          % (scr.get("ruleMounted"), scr.get("ruleInputs"), scr.get("ruleRadios")))
    check(scr.get("ruleMounted") is True, "点「创建新房间」弹出规则设置")
    check(scr.get("ruleInputs") == 18, "规则弹窗 18 个数值项（实际 %s）" % scr.get("ruleInputs"))
    check(scr.get("ruleRadios") == 2, "规则弹窗有「有番/无番」两个模式")
    rt = scr.get("ruleText") or ""
    for kw in ("底分", "明杠", "补杠", "暗杠", "真花", "假花", "真对花", "假对花", "平胡", "十三幺"):
        check(kw in rt, "规则弹窗含「%s」" % kw)

    print("  二级战绩(?roomId=): mounted=%s 房间=%r 把数=%s 四家=%s 杠/花块=%s"
          % (scr.get("recordsMounted"), scr.get("recordsRoomChip"), scr.get("roundCount"),
             scr.get("ovPlayers"), scr.get("gangMelds")))
    check(scr.get("recordsMounted") is True, "房间页的二级战绩页能挂载")
    check("ROOM1" in (scr.get("recordsRoomChip") or ""), "二级战绩标出了本房间号")
    check(scr.get("roundCount") == 2, "二级战绩渲染出 2 把（实际 %s）" % scr.get("roundCount"))
    check(scr.get("ovPlayers") == 4, "概览渲染出四家总分（实际 %s）" % scr.get("ovPlayers"))
    check(scr.get("gangMelds", 0) >= 1, "明细里画出了杠/副露牌")
    notes = scr.get("notesShown") or []
    print("  明细备注: %s" % json.dumps(notes, ensure_ascii=False))
    check(any("真花" in n for n in notes) and any("包三道" in n for n in notes),
          "明细里展示了真花/包三道等备注", "实际 %s" % notes)
    dtxt = scr.get("detailText") or ""
    check("最高番" in dtxt and "碰碰胡" in dtxt, "概览显示最高番型")
    check("荒庄" in dtxt, "流局那一把显示「荒庄」")

    # 「本场我的总分」——夹具里我(userId=42)本场 +7
    print("  本场我的总分 = %r   标'我'的行数=%s" % (scr.get("myTotalText"), scr.get("meTagRows")))
    check(scr.get("myTotalText") == "+7",
          "「本场我的总分」按 userId 找到自己并显示真实分数（期望 +7）",
          "实际 %r" % scr.get("myTotalText"))
    check(scr.get("meTagRows") == 1, "四家概览里只标出一个「我」")

    wb = scr.get("winTileBox") or {}
    sb = scr.get("smallTileBox") or {}
    print("  胡牌那张牌尺寸=%s cls=%r --tw=%s --th=%s   小牌=%s"
          % (wb, scr.get("winTileCls"), scr.get("winTileTw"), scr.get("winTileTh"), sb))
    check(wb.get("w", 999) <= 60 and wb.get("h", 999) <= 80,
          "胡牌那张牌按 size=sm 渲染（32x44），没有撑成原图大小",
          "实际 %sx%s" % (wb.get("w"), wb.get("h")))

    print("  一级列表: 行数=%s 点入后把数=%s" % (scr.get("listRows"), scr.get("detailFromList")))
    check(scr.get("listRows") == 2, "战绩列表渲染出 2 场（实际 %s）" % scr.get("listRows"))
    check(scr.get("detailFromList") == 2, "点列表某一场能进二级明细")

    # ---- 一级列表：房号后面要有四家名字 ----
    print("\n--- 一级战绩列表的玩家名 ---")
    print("  每行名字: %s" % json.dumps(scr.get("listPlayers"), ensure_ascii=False))
    players = scr.get("listPlayers") or []
    check(len(players) == 2, "两行都渲染了玩家名（实际 %s 行）" % len(players))
    if players:
        check(players[0] == "阿东、阿南、阿西、我",
              "第一行房号后面是四家名字（按座位 东南西北）", "实际 %r" % players[0])
    if len(players) > 1:
        check("电脑·南" in players[1] and "电脑·北" in players[1],
              "机器人也显示成「电脑·X」而不是「玩家-1」", "实际 %r" % players[1])

    # ---- 加入房间：三种情况都不该乱跳 ----
    print("\n--- 加入房间（预检）---")
    bad = scr.get("joinBadFormat") or {}
    miss = scr.get("joinNotFound") or {}
    okj = scr.get("joinOk") or {}
    print("  格式错 : hash=%s err=%r 预检调用=%s" % (bad.get("hash"), bad.get("err"), bad.get("calls")))
    print("  不存在 : hash=%s err=%r 预检调用=%s" % (miss.get("hash"), miss.get("err"), miss.get("calls")))
    print("  存在   : hash=%s" % okj.get("hash"))
    check(bad.get("hash") == "#/lobby", "房间号格式不对时【不跳转】，留在大厅", "实际 %s" % bad.get("hash"))
    check("6 位数字" in (bad.get("err") or ""), "格式不对时给提示", "实际 %r" % bad.get("err"))
    check(not (bad.get("calls") or []), "格式不对时连预检请求都不发（本地就拦住了）",
          "实际发了 %s" % bad.get("calls"))
    check(miss.get("hash") == "#/lobby", "房间不存在/已解散时【不跳转】", "实际 %s" % miss.get("hash"))
    check("不存在" in (miss.get("err") or ""), "房间不存在时把后端原因显示出来", "实际 %r" % miss.get("err"))
    check(bool(miss.get("calls")), "房间不存在时确实走了预检接口")
    check(str(okj.get("hash") or "").startswith("#/room"), "房间存在时才跳到房间页",
          "实际 %s" % okj.get("hash"))

    # ---- 局内战绩 = 本房间二级战绩（同一套渲染）----
    print("\n--- 局内战绩弹窗 ---")
    print("  按钮=%r 有明细=%s 我的总分=%r 把数=%s 四家=%s 走了内存兜底=%s"
          % (scr.get("gameHistoryLabel"), scr.get("gameHistoryHasDetail"), scr.get("gameHistoryTotal"),
             scr.get("gameHistoryRounds"), scr.get("gameHistoryPlayers"), scr.get("gameHistoryIsFallback")))
    check(scr.get("gameHistoryBtn") is True, "牌桌右下角有「局内战绩」按钮")
    check(scr.get("gameHistoryHasDetail") is True,
          "点开后显示的是服务端本房间战绩（概览在）",
          "label=%r" % scr.get("gameHistoryLabel"))
    check(scr.get("gameHistoryRounds") == 2,
          "弹窗里渲染出本房间的 2 把（与大厅二级界面同源）")
    check(scr.get("gameHistoryPlayers") == 4, "弹窗概览渲染出四家")
    check(scr.get("gameHistoryIsFallback") is False, "没有退化成内存兜底列表")

    if not t.strip():
        print("\n[probe] !! body.innerText 为空 → 应用没渲染出来")
        print("[probe] errs = %s" % json.dumps(data.get("errs"), ensure_ascii=False)[:1200])
        fails.append("应用未渲染")

    print("")
    if fails:
        print("[probe] ==== 有 %d 项不符 ====" % len(fails))
        for f in fails:
            print("  - " + f)
        if args.keep:
            print("[probe] 临时文件: " + page)
        return 1
    print("[probe] ==== 布局断言全部通过 ====")
    if args.keep:
        print("[probe] 临时文件: " + page)
    return 0

if __name__ == "__main__":
    sys.exit(main())
