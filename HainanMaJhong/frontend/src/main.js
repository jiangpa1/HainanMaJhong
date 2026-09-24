import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router/index.js'
import './styles/base.css'
// 必须排在 base.css 之后：body / #app 的兜底规则要覆盖 base.css 里的同名声明
import './styles/landscape.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
