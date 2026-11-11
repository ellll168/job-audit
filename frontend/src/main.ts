import { createApp } from 'vue'
// 按需引入样式：只加载 ElMessage 用到的基础变量、message 组件与暗色变量，
// 不再整包引入 element-plus/dist/index.css（350KB+）和全量组件注册。
import 'element-plus/theme-chalk/base.css'
import 'element-plus/theme-chalk/el-message.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './style.css'
import App from './App.vue'

// 整个应用固定使用深色主题，ElMessage 等弹层随之变深
document.documentElement.classList.add('dark')

createApp(App).mount('#app')
