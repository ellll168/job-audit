# 会话记录：Vue3 暗黑大屏搭建与前端 Bug 修复

- 项目：job-audit（岗位反向尽调助手）
- 会话日期：2026-11-09
- 主题：Vue3 + Vite + Tailwind CSS v4 环境搭建、暗黑科技风数据大屏、ECharts 渲染 Bug 修复、动态查询与错误降级、PNG 导出
- 涉及文件：
  - `frontend/package.json`（新增 `@vitejs/plugin-vue`、`@tailwindcss/vite`、`echarts`、`axios`、`html2canvas`）
  - `frontend/vite.config.js`（注册 `vue()` + `tailwindcss()` 插件）
  - `frontend/src/style.css`（Tailwind v4 单行引入）
  - `frontend/src/App.vue`（重写为暗黑大屏）
  - `frontend/src/main.ts`（改为 Vue 挂载）
  - `frontend/src/shims-vue.d.ts`（`.vue` 模块类型声明）
  - `frontend/index.html`（标题）

---

## 一、环境搭建：Vue3 + Vite + Tailwind CSS v4

### 1.1 版本现状

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| `vite` | `^8.3.6` | 构建工具 |
| `@vitejs/plugin-vue` | `^6.0.10` | 原 `vite.config.js` 已引用但未安装，本次补装 |
| `tailwindcss` | `^4.3.5` | v4 版本 |
| `@tailwindcss/vite` | `^4.3.5` | v4 官方 Vite 插件 |
| `typescript` | `~7.0.2` | `npm run build` = `tsc && vite build` |
| `echarts` | `^6.1.0` | 仪表盘 / 雷达图 |
| `axios` | `^1.21.0` | 请求后端 |
| `html2canvas` | `^1.4.1` | 导出 PNG |

### 1.2 Tailwind v4 的关键差异

Tailwind CSS v4 废弃了 `npx tailwindcss init -p` 命令，不再生成 `tailwind.config.js` 与 `postcss.config.js`，改为**以 Vite 插件形式接入**，样式入口只需一行 `@import`。

`frontend/vite.config.js`：

```js
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [
    vue(),
    tailwindcss(),
  ],
})
```

`frontend/src/style.css`：

```css
@import "tailwindcss";
```

对比 v3 的三行指令（`@tailwind base; @tailwind components; @tailwind utilities;`），v4 用一个 `@import` 等价替代。

### 1.3 工程接入的两处补丁

1. **补装 `@vitejs/plugin-vue`**：`vite.config.js` 里已经 `import vue from '@vitejs/plugin-vue'`，但 `devDependencies` 中没有该包，直接构建会解析失败。
2. **新增 `src/shims-vue.d.ts`**：项目开启了 `verbatimModuleSyntax`，`tsc` 不认识 `.vue` 后缀，需要模块声明才能通过类型检查：

```ts
declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}
```

`src/main.ts` 同时从"手写 innerHTML 模板"改为标准 Vue 挂载：

```ts
import { createApp } from 'vue'
import './style.css'
import App from './App.vue'

createApp(App).mount('#app')
```

---

## 二、大屏 UI 构建

### 2.1 需求 Prompt 要点

> 你是一个顶级的 Vue3 + Tailwind CSS 专家。请帮我重写 `src/App.vue`，使用 `<script setup>` 语法，打造一个暗黑科技风的"岗位风险审计数据大屏"。
>
> - 暗黑质感：全局背景深空蓝黑（`bg-[#0b1120]`），所有内容卡片使用毛玻璃效果（`bg-slate-900/60 backdrop-blur-md border border-slate-700/50 rounded-2xl shadow-2xl`）。
> - 顶部：左侧渐变字体显示 `companyName`，右侧 ECharts gauge 仪表盘展示风险总分数，分数越高越红。
> - 中间：左侧雷达图（职责模糊 / 薪资坑人 / PUA话术 / 信息缺失），右侧 JD 原文可滚动。
> - 底部：信号卡片列表，HIGH 红色发光左边框、MEDIUM 橙色，`source` 用 Tag 区分（LLM 绿 / RULE 蓝）。

接口：`GET http://localhost:8080/api/audits/28`，返回 `AuditResultResponse`（`auditId / companyName / inputText / status / createdAt / signals[]`）。

### 2.2 布局结构

```
┌──────────────────────── 顶部 Header ────────────────────────┐
│ 渐变 companyName + auditId + status      │  ECharts gauge    │
│ (cyan→sky→fuchsia 渐变)                   │  (0-100 风险分)   │
├─────────────────────── 中部 ───────────────────────────────┤
│  ECharts 雷达图          │   JD 原文（灰色 · 可滚动）         │
│  职责模糊/薪资坑人/       │                                   │
│  PUA话术/信息缺失         │                                   │
├─────────────────────── 底部 ───────────────────────────────┤
│  HIGH 红发光 · MEDIUM 橙 · LOW 灰   信号卡片网格（1/2/3 列响应）│
│  每张卡：类型标签 + reason + source Tag + 置信度               │
└────────────────────────────────────────────────────────────┘
```

### 2.3 视觉规范

| 元素 | 实现 |
| --- | --- |
| 全局底色 | `bg-[#0b1120]` |
| 卡片 | `bg-slate-900/60 backdrop-blur-md border border-slate-700/50 rounded-2xl shadow-2xl` |
| 标题渐变 | `bg-gradient-to-r from-cyan-300 via-sky-300 to-fuchsia-400 bg-clip-text text-transparent` |
| 背景光斑 | 两个 `fixed` 定位的 `blur-3xl` 圆形径向渐变，`pointer-events-none` |
| 滚动条 | 自定义 `::-webkit-scrollbar` 细条样式，`rgba(148,163,184,0.35)` |
| 响应式 | `grid-cols-1 lg:grid-cols-2`、卡片 `md:grid-cols-2 xl:grid-cols-3` |

### 2.4 数据到图表的映射

**综合风险分数**（仪表盘指针值，0–100）：

```ts
const weight: Record<string, number> = { HIGH: 30, MEDIUM: 15, LOW: 5 }
const raw = audit.value.signals.reduce((acc, s) => acc + (weight[s.severity] ?? 0), 0)
return Math.min(100, raw)
```

色带：`0.3 → #22c55e`、`0.7 → #eab308`、`1 → #ef4444`，指针隐藏，只保留中间大字数值。

**雷达图四维取值**：每个维度取该维度下最严重信号的分值（`HIGH=100 / MEDIUM=60 / LOW=30`），无信号为 0。

```ts
const signalToDimension: Record<string, string> = {
  vague_duty: 'duty', contradictory_req: 'duty', broad_duty: 'duty',
  salary_opacity: 'salary', salary_mismatch: 'salary',
  pua_culture: 'pua', overwork_risk: 'pua',
  information_missing: 'info', scam_risk: 'info',
  urgency_noise: 'info', overpackaged: 'info',
}
```

`signalType` 未在映射表中时兜底到 `info`（`?? 'info'`），`severity` 缺失时兜底 30 分（`?? 30`），避免空值参与比较导致 `NaN`。

**信号卡片左边框**：按 `severity` 拼 Tailwind 类，HIGH 追加 `shadow-red-500/20` 产生红色外发光。

---

## 三、Bug 修复：雷达图空白 + 底部卡片不渲染

### 3.1 现象

页面整体渲染正常（顶部渐变公司名、仪表盘都有），但：

- 左侧"风险维度分布"卡片里是一片空白，没有雷达图；
- 底部"风险信号明细"列表为空。

### 3.2 排查过程

**第一步：确认后端数据没问题**。直接请求接口，`signals` 返回 2 条（`salary_opacity` HIGH / `salary_mismatch` MEDIUM，RULE 与 LLM 都有），排除"数据为空导致不渲染"。

**第二步：检查浏览器控制台**。`console messages: (none)` —— 没有任何报错，说明不是 ECharts 配置异常，而是**代码根本没执行到绘图**。

**第三步：量取 DOM**。关键证据：

```
{ totalCanvases: 1,              ← 只有仪表盘一个 canvas
  bigSections: [
    { cls: 'h-[220px] w-full', h: 220, hasChildCanvas: true  },   ← 仪表盘容器有 canvas
    { cls: 'h-[340px] w-full', h: 340, hasChildCanvas: false }    ← 雷达容器有高度但没 canvas
  ] }
```

雷达容器 `offsetHeight = 340`，说明 **CSS 高度没问题**；里面没有 `canvas`，说明 `echarts.init()` 压根没执行成功。

### 3.3 根因

模板用三分支控制渲染：

```html
<div v-if="loading">正在拉取审计数据 …</div>
<div v-else-if="error">加载失败 …</div>
<template v-else-if="audit">
  ...
  <div ref="radarRef" class="h-[340px] w-full" />   <!-- 雷达容器在这里面 -->
  ...
</template>
```

而原代码是：

```ts
async function loadAudit() {
  loading.value = true
  try {
    audit.value = res.data
    initGauge(riskScore.value)
    initRadar(dimensionValues.value)   // ← 此时 loading 仍为 true
  } finally {
    loading.value = false              // ← 太晚了
  }
}
```

`loading === true` 时 `v-else-if="audit"` 分支尚未渲染，`radarRef.value === null`，`initRadar` 第一行 `if (!dom) return` 直接静默退出。仪表盘之所以正常，是因为它的容器在 `header` 里，始终存在于 DOM。

底部卡片列表用同一分支，同样受这次渲染时序影响（数据已到位但 DOM 分支还未切换），表现为空列表。

**一句话总结：图表初始化发生在 Vue 完成 DOM 更新之前。**

### 3.4 修复

把 `loading = false` 提到 try/catch 之外，并在初始化图表前 `await nextTick()`，等 Vue 完成这次 DOM 更新：

```ts
async function loadAudit() {
  loading.value = true
  error.value = null
  resetState()
  try {
    const res = await axios.get<AuditResult>(
      `http://localhost:8080/api/audits/${currentAuditId.value}`,
    )
    audit.value = res.data
  } catch {
    error.value = '未找到审计记录，请检查输入 ID 是否正确'
  }
  // 先让 Vue 切到 v-else-if="audit" 分支并完成 DOM 渲染
  loading.value = false
  if (audit.value) {
    await nextTick()
    initGauge(riskScore.value)
    initRadar(dimensionValues.value)
  }
}
```

`initGauge` / `initRadar` 内部也补了防御：每次 `init` 前先 `dispose()` 旧实例，避免重复 init 产生多个 canvas。

### 3.5 验证

浏览器实测：

```
totalCanvases: 2          ← 仪表盘 + 雷达图都存在
border-l-red:    1        ← HIGH 红左边框（salary_opacity）
border-l-orange: 1        ← MEDIUM 橙左边框（salary_mismatch）
```

`npm run build`（`tsc && vite build`）退出码 0，无类型错误。

---

## 四、功能完善：动态 Audit ID 查询

### 4.1 需求

> 在页面最顶部加一个输入框和"开始审计"按钮，允许用户输入 Audit ID。默认值为 28。点击按钮后重新请求接口并更新页面。

### 4.2 实现

原代码把 URL 写死为 `/api/audits/28`，改为响应式 ID：

```ts
const auditIdInput = ref('28')
const currentAuditId = ref<number>(28)

function reloadWithCurrentId() {
  const parsed = Number.parseInt(auditIdInput.value.trim(), 10)
  if (!Number.isFinite(parsed) || parsed <= 0) {
    resetState()
    error.value = '请输入有效的正整数 Audit ID'
    return
  }
  currentAuditId.value = parsed
  loadAudit()
}
```

```ts
const res = await axios.get<AuditResult>(
  `http://localhost:8080/api/audits/${currentAuditId.value}`,
)
```

输入框细节：

- `v-model="auditIdInput"` + `inputmode="numeric"`，移动端唤起数字键盘；
- `@keyup.enter="reloadWithCurrentId"` 支持回车提交；
- 按钮 `:disabled="loading"`，加载中切换为旋转 `spinner` + "审计中…"，防止重复提交；
- 输入非法时直接给出提示，不发请求。

### 4.3 页面滚动修复

底部卡片 `reason` 被截断，根容器加上 `overflow-y-auto`，卡片高度交由内容自然撑开，不做 `line-clamp` 截断：

```html
<div class="min-h-screen overflow-y-auto bg-[#0b1120] text-slate-100 p-6 md:p-10">
```

### 4.4 来源标签升级

| `source` | 文案 | 图标 | 视觉 |
| --- | --- | --- | --- |
| `RULE` | 规则命中 | 盾牌 | 蓝底 + `border-sky-400/40` |
| `LLM` | AI 语义 | 灯泡 | 绿底 + `shadow-[0_0_12px_rgba(16,185,129,0.35)]` 外发光 |

```ts
function sourceLabel(source: string): string {
  return source === 'LLM' ? 'AI 语义' : '规则命中'
}
```

---

## 五、404 / 500 的优雅降级

### 5.1 需求

> 当接口报错（404/500）时，请立刻把页面顶部的 companyName、auditId 等所有旧数据，以及 ECharts 的仪表盘和雷达图全部清空。报错文案改为："未找到审计记录，请检查输入 ID 是否正确"。

### 5.2 实现

抽出 `resetState()`，在**发起请求之前**无条件调用，而不是等 `catch` 里再清：

```ts
function resetState() {
  audit.value = null
  if (gaugeChart) { gaugeChart.dispose(); gaugeChart = null }
  if (radarChart) { radarChart.dispose(); radarChart = null }
}
```

效果链路：

1. `audit.value = null` → 模板中 `v-else-if="audit"` 失效 → header 的公司名、auditId、状态标签，以及雷达图卡片、信号卡片整块从 DOM 移除；
2. `dispose()` 释放 ECharts 实例，防止画布与监听器泄漏；
3. 只剩红框错误提示（`bg-red-900/30 border-red-500/40`）。

错误文案不再透出原始 `Error.message`，统一改为业务化提示：

```ts
catch {
  error.value = '未找到审计记录，请检查输入 ID 是否正确'
}
```

副文案同步改为操作引导："可在上方输入栏重新输入 Audit ID 后点击「开始审计」重试。"

---

## 六、附加：导出报告（html2canvas）

虽然不在最初四项目标内，本次一并实现。

```ts
const canvas = await html2canvas(target, {
  scale: 2,                    // 2 倍分辨率，图表与文字更锐利
  backgroundColor: '#0b1120',  // 强制深色底，避免默认白底
  useCORS: true,
  logging: false,
  windowWidth: target.scrollWidth,
})
const link = document.createElement('a')
link.download = `audit-report-${currentAuditId.value}-${stamp}.png`
link.href = canvas.toDataURL('image/png')
link.click()
```

要点：

- 截图目标是 `reportRef`（`max-w-[1400px]` 内容容器），`scrollHeight` 即完整页面高度，底部卡片不会被截断；
- `backgroundColor: '#0b1120'` 是"保留深色背景"的关键，html2canvas 默认输出白底；
- 按钮 `:disabled="exporting || !audit"`，无数据时不可导出；
- 背景光斑是 `fixed` 定位、位于内容容器之外，因此不会进入截图，深色由 `backgroundColor` 兜底。

---

## 七、遗留与注意事项

1. **构建体积警告**：`index-*.js` 约 1.45 MB（gzip 469 KB），主要来自 ECharts 全量引入。如需优化，可改为按需注册 `GaugeChart` / `RadarChart` + `use()`。
2. **跨域依赖**：前端直连 `http://localhost:8080`，依赖后端 `CorsConfig` 对 `/api/**` 放开跨域。
3. **Audit ID 仍为硬编码默认值 28**，如需支持 URL 参数（`?id=28`）或路由跳转，后续可接 `vue-router` 或 `URLSearchParams`。
4. **Tailwind v4 无配置文件**：自定义主题需写入 `style.css` 的 `@theme` 块，而非 `tailwind.config.js`。
