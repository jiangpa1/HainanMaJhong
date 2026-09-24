/*
 * 冒烟测试用的两个 esbuild 插件（只服务于 tools/smoke.mjs）。
 *
 * 1) vuePlugin —— 让 esbuild 认识 .vue
 *    不直接用 @vitejs/plugin-vue：那是 Vite 插件，接口依赖 Vite 的 transform 管线
 *    （虚拟模块、HMR、css 处理），塞进 esbuild 要写一堆适配。冒烟测试只需要
 *    「把 SFC 编译成可执行 JS」，用 vue 自带的 compiler-sfc 就够，也少一个依赖。
 *
 *    关键点（踩过坑，别改回去）：
 *    Vue 3.5 的 compileScript 在 <script setup> 下产出的是【内联对象】：
 *        export default { setup(__props, { expose }) { ... } }
 *    而不是某些资料里写的 `const _sfc_main = {...}; export default _sfc_main`。
 *    所以要把 `export default {` 就地改成 `const __sfc_main = {`，
 *    末尾挂 render 后再导出。直接覆盖默认导出会让组件没有 render，
 *    表现是「页面渲染出来是空的」。
 *
 * 2) vueShimPlugin —— 把裸模块名 "vue" 指向一个 shim
 *    目的：测试里要拿 app / router / pinia 实例来驱动导航和断言状态。
 *    替代做法是在 main.js 里写 globalThis.xxx = ...，那是为测试污染生产代码。
 *    用 shim 就能让 main.js 保持干净。
 */

import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { createRequire } from 'node:module'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'

/** 解析包的真实入口文件（cwd 必须是 frontend/）。 */
function resolvePkg(id) {
  const req = createRequire(join(process.cwd(), 'package.json'))
  return req.resolve(id)
}

/* ==================== 1. .vue 加载器 ==================== */

export function vuePlugin() {
  return {
    name: 'vue-sfc-lite',
    setup(build) {
      build.onLoad({ filter: /\.vue$/ }, (args) => {
        const source = readFileSync(args.path, 'utf8')
        const { descriptor, errors } = parse(source, { filename: args.path })
        if (errors && errors.length) {
          return { errors: errors.map((e) => ({ text: String(e.message || e) })) }
        }

        const id = args.path.replace(/[^a-zA-Z0-9]/g, '_')
        const hasScoped = !!(descriptor.styles || []).some((s) => s.scoped)
        const scopeId = hasScoped ? `data-v-${id}` : undefined

        let scriptCode = ''
        let bindings = null

        if (descriptor.script || descriptor.scriptSetup) {
          const compiled = compileScript(descriptor, {
            id,
            isProd: false,
            inlineTemplate: false,
            templateOptions: { compilerOptions: { scopeId } },
          })
          scriptCode = compiled.content
          bindings = compiled.bindings
        }

        let templateCode = ''
        if (descriptor.template) {
          const compiled = compileTemplate({
            source: descriptor.template.content,
            filename: args.path,
            id,
            scoped: hasScoped,
            compilerOptions: { bindingMetadata: bindings, scopeId },
          })
          if (compiled.errors && compiled.errors.length) {
            return { errors: compiled.errors.map((e) => ({ text: String(e.message || e) })) }
          }
          templateCode = compiled.code
        }

        return { contents: assemble(scriptCode, templateCode), loader: 'js' }
      })
    },
  }
}

/**
 * 拼出最终模块代码。
 *   - `export default {` → `const __sfc_main = {`（只改内联对象这一种，
 *     不碰 `export { X as default }` 这种重导出写法）
 *   - 模板代码去掉 export、render 改名，末了挂到 __sfc_main 上再导出
 */
function assemble(scriptCode, templateCode) {
  let script = scriptCode

  const hadInlineDefault = /^export default \{/m.test(script)
  if (hadInlineDefault) {
    script = script.replace(/^export default \{/m, 'const __sfc_main = {')
  }

  const tmpl = templateCode
    .replace(/export\s+function\s+render\b/, 'function __sfc_render')
    .replace(/^export\s+/gm, '')

  const tail = hadInlineDefault
    ? `
if (typeof __sfc_render === 'function') { __sfc_main.render = __sfc_render }
export default __sfc_main
`
    : `
/* 没匹配到内联默认导出：至少把 render 导出去，别让整个 bundle 编译失败 */
export { __sfc_render as render }
`

  return `${script}\n${tmpl}\n${tail}\n`
}

/* ==================== 2. vue shim ==================== */

/**
 * 把 vue / vue-router / pinia 解析到 shim，shim 里 re-export 真包并额外暴露实例。
 *
 * ⚠️ 三个包都要包，别只包 vue —— createRouter 来自 vue-router、createPinia 来自 pinia，
 *   只有在 vue 上包一层是拿不到任何实例的（踩过这个坑，表现为「实例未挂载」）。
 *
 * shim 必须【写在项目根目录下】。原因：
 *   - 写在临时目录：shim 与测试脚本的模块解析根不同，Node 会各自加载一份依赖，
 *     globalThis 不是同一个对象，测试里读不到挂出来的实例。
 *   - 用 file:// URL 引用真包：esbuild 解析不了这种 specifier。
 *   - 用裸包名：会被本插件的 onResolve 再截住，形成自引用。
 * 放在 frontend/ 下就简单了：两侧都从 frontend/node_modules 解析，得到同一份模块实例。
 */
export function vueShimPlugin() {
  const shimPath = join(process.cwd(), '__hn_shim__.mjs')

  const shim = `import * as v from 'vue'
import * as vr from 'vue-router'
import * as p from 'pinia'

export * from 'vue'
export * from 'vue-router'
export * from 'pinia'

/*
 * 包装三个工厂函数，把实例挂到 globalThis 供冒烟测试驱动。
 * 只加一条赋值，不改任何行为 —— 生产构建不会用到这个 shim。
 */
export function createApp(...args) {
  const app = v.createApp(...args)
  globalThis.__hn_app = app
  return app
}

export function createRouter(...args) {
  const router = vr.createRouter(...args)
  globalThis.__hn_router = router
  return router
}

export function createPinia(...args) {
  const pinia = p.createPinia(...args)
  globalThis.__hn_pinia = pinia
  return pinia
}
`

  writeFileSync(shimPath, shim, 'utf8')

  return {
    name: 'vue-shim',
    setup(build) {
      /*
       * 只拦「从应用代码来的」这三个包名。shim 自己的 import 不能再被拦，否则自引用。
       * esbuild 的 onResolve 拿得到 importer，用它区分。
       */
      const names = ['vue', 'vue-router', 'pinia']
      const filter = new RegExp('^(' + names.join('|') + ')$')
      build.onResolve({ filter }, (args) => {
        const importer = (args.importer || '').replace(/\\/g, '/')
        if (importer.endsWith('__hn_shim__.mjs')) {
          return null // 交回默认解析 → 真包
        }
        return { path: shimPath, namespace: 'file' }
      })
    },
  }
}
