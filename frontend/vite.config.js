import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  // .env / .env.example 放在仓库根目录而不是 frontend 下，这里显式指定加载目录
  envDir: '..',
  plugins: [
    vue(),
    tailwindcss(),
  ],
})