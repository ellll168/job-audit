<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import http from './api/http'
// html2canvas-pro 是 html2canvas 的兼容分支，唯一区别是支持 oklch()/lab() 等现代颜色函数。
// Tailwind v4 的调色板全部输出 oklch()，原版 html2canvas 1.4.1 会直接抛
// "Attempting to parse an unsupported color function 'oklch'"，导致截图无声失败。
// 它只在点击「导出报告」时才需要，因此改为动态 import（见 handleExport）。
// ECharts 按需引入：只注册页面用到的仪表盘、雷达图与 Canvas 渲染器，
// 不再把全部图表类型（约 1MB）打进产物。
import * as echarts from 'echarts/core'
import { GaugeChart, RadarChart } from 'echarts/charts'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([GaugeChart, RadarChart, CanvasRenderer])

/* ---------- 类型 ---------- */

interface SignalRecord {
  id: number
  auditId: number
  signalType: string
  severity: 'HIGH' | 'MEDIUM' | 'LOW' | string
  reason: string
  confidence: number | null
  source: 'RULE' | 'LLM' | string
  createdAt: string
}

interface AuditResult {
  auditId: number
  companyName: string
  inputText: string
  status: string
  createdAt: string
  signals: SignalRecord[]
  aiVerdict: string | null
  /** 避坑建议：后端按需生成、不落库；演示或无信号时为空数组 */
  avoidanceTips: string[]
}

/* ---------- 响应式状态 ---------- */

/** 页面阶段：input = 输入页，result = 结果页 */
const stage = ref<'input' | 'result'>('input')
const loading = ref(false)
const error = ref<string | null>(null)
const audit = ref<AuditResult | null>(null)

const jdText = ref('')
const companyName = ref('')

const gaugeRef = ref<HTMLElement | null>(null)
const radarRef = ref<HTMLElement | null>(null)
let gaugeChart: echarts.ECharts | null = null
let radarChart: echarts.ECharts | null = null

/* ---------- 风险维度映射 ---------- */

const DIMENSIONS = [
  { key: 'duty',   name: '职责模糊' },
  { key: 'salary', name: '薪资坑人' },
  { key: 'pua',    name: 'PUA话术' },
  { key: 'info',   name: '信息缺失' },
]

const signalToDimension: Record<string, string> = {
  vague_duty:        'duty',
  contradictory_req: 'duty',
  salary_opacity:    'salary',
  pua_culture:       'pua',
  overwork_risk:     'pua',
  information_missing: 'info',
  scam_risk:         'info',
  urgency_noise:     'info',
  overpackaged:      'info',
  broad_duty:        'duty',
  salary_mismatch:   'salary',
}

const severityScore: Record<string, number> = { HIGH: 100, MEDIUM: 60, LOW: 30 }

/* ---------- 衍生计算 ---------- */

/** 综合风险分数（0-100），供仪表盘使用 */
const riskScore = computed<number>(() => {
  if (!audit.value) return 0
  const weight: Record<string, number> = { HIGH: 30, MEDIUM: 15, LOW: 5 }
  const raw = audit.value.signals.reduce(
    // SYSTEM 信号（如 AI 服务降级提示）不代表岗位风险，不计入总分
    (acc, s) => (s.source === 'SYSTEM' ? acc : acc + (weight[s.severity] ?? 0)),
    0,
  )
  return Math.min(100, raw)
})

/** 四个风险维度的当前值（0-100），供雷达图使用 */
const dimensionValues = computed<number[]>(() => {
  const vals: Record<string, number> = { duty: 0, salary: 0, pua: 0, info: 0 }
  if (audit.value) {
    for (const s of audit.value.signals) {
      if (s.source === 'SYSTEM') continue
      const dim = signalToDimension[s.signalType] ?? 'info'
      const level = severityScore[s.severity] ?? 30
      if (level > (vals[dim] ?? 0)) vals[dim] = level
    }
  }
  return DIMENSIONS.map((d) => vals[d.key])
})

/* ---------- 信号卡片样式 ---------- */

function signalCardClass(s: SignalRecord): string {
  const base =
    'bg-slate-900/60 backdrop-blur-md border border-slate-700/50 rounded-2xl shadow-2xl p-5 transition-all hover:-translate-y-0.5 hover:shadow-cyan-500/10'
  if (s.severity === 'HIGH') {
    // 左侧黄黑警戒线由 .da-hazard 伪元素绘制
    return `${base} da-hazard shadow-red-500/20`
  }
  if (s.severity === 'MEDIUM') {
    return `${base} border-l-4 border-l-orange-500`
  }
  return `${base} border-l-4 border-l-slate-500`
}

function sourceLabel(source: string): string {
  if (source === 'LLM') return 'AI 语义'
  if (source === 'SYSTEM') return '系统提示'
  return '规则命中'
}

function sourceTagClass(source: string): string {
  const base = 'inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium'
  if (source === 'LLM') {
    return `${base} bg-emerald-500/15 text-emerald-300 border border-emerald-400/40 shadow-[0_0_12px_rgba(16,185,129,0.35)]`
  }
  if (source === 'SYSTEM') {
    return `${base} bg-amber-500/15 text-amber-300 border border-amber-400/40 shadow-[0_0_12px_rgba(251,191,36,0.35)]`
  }
  return `${base} bg-sky-500/15 text-sky-300 border border-sky-400/40`
}

function severityTagClass(severity: string): string {
  if (severity === 'HIGH')
    return 'px-2 py-0.5 rounded-md text-xs font-semibold bg-red-500/20 text-red-300 border border-red-400/40'
  if (severity === 'MEDIUM')
    return 'px-2 py-0.5 rounded-md text-xs font-semibold bg-orange-500/20 text-orange-300 border border-orange-400/40'
  return 'px-2 py-0.5 rounded-md text-xs font-semibold bg-slate-500/20 text-slate-300 border border-slate-400/40'
}

function signalTypeLabel(type: string): string {
  const map: Record<string, string> = {
    vague_duty:           '职责模糊',
    contradictory_req:    '需求矛盾',
    salary_opacity:       '薪资不透明',
    salary_mismatch:      '薪资与能力不匹配',
    pua_culture:          'PUA文化',
    overwork_risk:        '加班风险',
    information_missing:  '信息缺失',
    scam_risk:            '诈骗嫌疑',
    urgency_noise:        '紧急噪音',
    overpackaged:         '包装过度',
    broad_duty:           '职责过宽',
    AI_SERVICE_DOWN:      'AI 服务降级',
  }
  return map[type] ?? type
}

/* ---------- 导出报告 ---------- */

const reportContainer = ref<HTMLElement | null>(null)
const exporting = ref(false)
const exportError = ref<string | null>(null)

async function handleExport() {
  exportError.value = null

  const target = reportContainer.value
  if (!target) {
    exportError.value = '导出失败：页面尚未渲染完成，请稍后重试'
    return
  }
  if (exporting.value) return

  exporting.value = true
  try {
    // 等一帧，确保 ECharts canvas 已完成绘制
    await nextTick()

    // html2canvas-pro 约 200KB，只在首次导出时异步加载，不进首屏包
    const { default: html2canvas } = await import('html2canvas-pro')

    const canvas = await html2canvas(target, {
      backgroundColor: '#0b1120',
      scale: 2,
      useCORS: true,
      logging: false,
    })

    const dataUrl = canvas.toDataURL('image/png')
    const stamp = new Date()
      .toISOString()
      .replace(/[:T]/g, '-')
      .slice(0, 19)

    const link = document.createElement('a')
    link.href = dataUrl
    link.download = `audit-report-${audit.value?.auditId ?? 'unknown'}-${stamp}.png`
    // 部分浏览器要求 a 标签在文档中才会触发下载
    link.style.display = 'none'
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
  } catch (e) {
    console.error('导出报告失败：', e)
    exportError.value = '导出失败：' + (e instanceof Error ? e.message : String(e))
  } finally {
    exporting.value = false
  }
}

/* ---------- 面试反问话术 ---------- */

const questions = ref<string[]>([])
const questionsLoading = ref(false)

async function loadQuestions(auditId: number) {
  questionsLoading.value = true
  try {
    const res = await http.get<{ questions: string[] }>(
      `/api/audits/${auditId}/questions`,
    )
    questions.value = res.data.questions
  } catch {
    // 话术生成失败不影响主页面，仅静默为空
    questions.value = []
  } finally {
    questionsLoading.value = false
  }
}

/* ---------- 核心交互：提交审计 / 重新审计 ---------- */

/** 清空已有数据与图表 */
function resetState() {
  audit.value = null
  questions.value = []
  if (gaugeChart) {
    gaugeChart.dispose()
    gaugeChart = null
  }
  if (radarChart) {
    radarChart.dispose()
    radarChart = null
  }
}

async function submitAudit() {
  const text = jdText.value.trim()
  if (!text) {
    error.value = '请先粘贴岗位 JD 原文'
    return
  }
  loading.value = true
  error.value = null
  resetState()
  try {
    const res = await http.post<AuditResult>('/api/audits', {
      companyName: companyName.value.trim() || '未填写公司',
      inputText: text,
    })
    audit.value = res.data
    stage.value = 'result'
    syncUrl(res.data.auditId)
  } catch {
    error.value = '审计失败，请确认后端服务已启动后重试'
  }
  loading.value = false
  if (audit.value) {
    await nextTick()
    initGauge(riskScore.value)
    initRadar(dimensionValues.value)
    loadQuestions(audit.value.auditId)
  }
}

/* ---------- URL 持久化：刷新不丢结果 ---------- */

const AUDIT_ID_PARAM = 'auditId'

/** 把 auditId 同步到地址栏；传 null 表示清除（回到输入页） */
function syncUrl(auditId: number | null) {
  const url = new URL(window.location.href)
  if (auditId === null) {
    url.searchParams.delete(AUDIT_ID_PARAM)
  } else {
    url.searchParams.set(AUDIT_ID_PARAM, String(auditId))
  }
  // replaceState 不产生历史记录，避免浏览器后退键在审计结果间来回跳
  window.history.replaceState(null, '', url)
}

/** 按 auditId 拉取已有审计结果（刷新恢复时使用） */
async function loadAudit(auditId: number) {
  loading.value = true
  error.value = null
  resetState()
  try {
    const res = await http.get<AuditResult>(`/api/audits/${auditId}`)
    audit.value = res.data
    stage.value = 'result'
    // 回填输入框，方便从结果页点「返回首页」后微调原文
    jdText.value = res.data.inputText
    companyName.value = res.data.companyName
  } catch {
    // 详细错误（超时/404/500）已由 axios 拦截器统一弹出，这里只保证不白屏
    stage.value = 'input'
    error.value = '未能加载该审计记录，请重新粘贴 JD 进行审计'
  }
  loading.value = false
  if (audit.value) {
    await nextTick()
    initGauge(riskScore.value)
    initRadar(dimensionValues.value)
    loadQuestions(audit.value.auditId)
  }
}

/** 回到输入页，保留上次输入方便微调 */
function backToInput() {
  resetState()
  error.value = null
  syncUrl(null)
  stage.value = 'input'
}

/* ---------- 图表 ---------- */

function initGauge(score: number) {
  const dom = gaugeRef.value
  if (!dom) return
  gaugeChart?.dispose()
  gaugeChart = echarts.init(dom, undefined, { renderer: 'canvas' })
  gaugeChart.setOption({
    series: [
      {
        type: 'gauge',
        startAngle: 220,
        endAngle: -40,
        min: 0,
        max: 100,
        radius: '95%',
        progress: { show: true, width: 16 },
        axisLine: {
          lineStyle: {
            width: 16,
            color: [
              [0.3, '#22c55e'],
              [0.7, '#eab308'],
              [1, '#ef4444'],
            ],
          },
        },
        pointer: { show: false },
        axisTick: { show: false },
        splitLine: { show: false },
        axisLabel: { show: false },
        anchor: { show: false },
        title: { show: false },
        detail: {
          valueAnimation: true,
          offsetCenter: ['0%', '0%'],
          fontSize: 44,
          fontWeight: 800,
          color: getScoreColor(score),
          formatter: '{value}',
        },
        data: [{ value: score }],
      },
    ],
  })
}

function getScoreColor(score: number): string {
  if (score >= 70) return '#ef4444'
  if (score >= 40) return '#eab308'
  return '#22c55e'
}

function initRadar(values: number[]) {
  const dom = radarRef.value
  if (!dom) return
  radarChart?.dispose()
  radarChart = echarts.init(dom, undefined, { renderer: 'canvas' })
  radarChart.setOption({
    backgroundColor: 'transparent',
    radar: {
      indicator: DIMENSIONS.map((d) => ({ name: d.name, max: 100 })),
      shape: 'polygon',
      splitNumber: 4,
      axisName: { color: '#94a3b8', fontSize: 13 },
      axisLine: { lineStyle: { color: 'rgba(148,163,184,0.25)' } },
      splitLine: { lineStyle: { color: 'rgba(148,163,184,0.18)' } },
      splitArea: {
        areaStyle: {
          color: ['rgba(56,189,248,0.03)', 'rgba(56,189,248,0.06)'],
        },
      },
    },
    series: [
      {
        type: 'radar',
        data: [
          {
            value: values,
            name: '风险分布',
            symbol: 'circle',
            symbolSize: 6,
            lineStyle: { color: '#38bdf8', width: 2 },
            areaStyle: {
              color: new echarts.graphic.RadialGradient(0.5, 0.5, 0.5, [
                { offset: 0, color: 'rgba(56,189,248,0.55)' },
                { offset: 1, color: 'rgba(56,189,248,0.05)' },
              ]),
            },
            itemStyle: { color: '#38bdf8', borderColor: '#e0f2fe', borderWidth: 1 },
          },
        ],
      },
    ],
  })
}

function resizeCharts() {
  gaugeChart?.resize()
  radarChart?.resize()
}

/* ---------- 生命周期 ---------- */

onMounted(() => {
  window.addEventListener('resize', resizeCharts)

  // 刷新恢复：地址栏带 ?auditId=xx 时自动重新拉取该次审计结果
  const raw = new URLSearchParams(window.location.search).get(AUDIT_ID_PARAM)
  const auditId = raw ? Number(raw) : Number.NaN
  if (Number.isInteger(auditId) && auditId > 0) {
    loadAudit(auditId)
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeCharts)
  gaugeChart?.dispose()
  radarChart?.dispose()
})
</script>

<template>
  <div class="h-screen overflow-y-auto bg-[#0b1120] text-slate-100 p-6 md:p-10">
    <!-- 背景：点阵网格 + 横向扫描线 + 冷色光斑 -->
    <div
      class="pointer-events-none fixed inset-0 overflow-hidden da-dot-grid"
      aria-hidden="true"
    >
      <div class="absolute inset-0 da-scanlines" />
      <div
        class="absolute -top-40 -left-40 w-[480px] h-[480px] rounded-full opacity-25 blur-3xl"
        style="background: radial-gradient(circle, #0ea5e9 0%, transparent 70%)"
      />
      <div
        class="absolute -bottom-40 -right-40 w-[520px] h-[520px] rounded-full opacity-20 blur-3xl"
        style="background: radial-gradient(circle, #a855f7 0%, transparent 70%)"
      />
    </div>

    <!-- ========== 输入页面 ========== -->
    <div
      v-if="stage === 'input'"
      class="relative min-h-[calc(100vh-5rem)] flex items-center justify-center"
    >
      <div
        class="w-full max-w-2xl bg-slate-900/60 backdrop-blur-md border border-slate-700/50 rounded-2xl shadow-2xl p-8 md:p-10"
      >
        <p class="text-xs uppercase tracking-[0.3em] text-cyan-400/80 mb-2">
          Job Audit
        </p>
        <h1
          class="text-3xl md:text-4xl font-black bg-gradient-to-r from-cyan-300 via-sky-300 to-fuchsia-400 bg-clip-text text-transparent"
        >
          岗位反向尽调
        </h1>
        <p class="mt-2 text-sm text-slate-400">
          粘贴招聘 JD，让 AI 帮你排雷。
        </p>

        <label class="block mt-8 mb-2 text-sm text-slate-400">
          岗位 JD 原文 <span class="text-red-400">*</span>
        </label>
        <textarea
          v-model="jdText"
          rows="10"
          maxlength="8000"
          placeholder="把招聘启事原文粘贴到这里，例如：岗位职责、任职要求、薪资范围……"
          class="w-full bg-slate-800/70 border border-slate-700/60 focus:border-cyan-400/60 focus:outline-none focus:ring-2 focus:ring-cyan-400/20 rounded-xl px-4 py-3 text-slate-100 placeholder-slate-500 text-sm leading-relaxed transition resize-y"
        />
        <p class="mt-1 text-xs text-slate-500">{{ jdText.length }} / 8000 字</p>

        <label class="block mt-5 mb-2 text-sm text-slate-400">
          公司名称 <span class="text-slate-600">（可选）</span>
        </label>
        <input
          v-model="companyName"
          type="text"
          placeholder="例如：某互联网公司"
          class="w-full bg-slate-800/70 border border-slate-700/60 focus:border-cyan-400/60 focus:outline-none focus:ring-2 focus:ring-cyan-400/20 rounded-xl px-4 py-3 text-slate-100 placeholder-slate-500 text-sm transition"
          @keyup.enter="submitAudit"
        />

        <div
          v-if="error"
          class="mt-5 bg-red-900/30 border border-red-500/40 rounded-xl px-4 py-3 text-sm text-red-300"
        >
          {{ error }}
        </div>

        <button
          @click="submitAudit"
          :disabled="loading"
          class="mt-8 w-full inline-flex items-center justify-center gap-3 px-8 py-4 rounded-xl font-bold text-lg
                 bg-gradient-to-r from-cyan-500 to-sky-500 text-white shadow-lg shadow-cyan-500/30
                 hover:from-cyan-400 hover:to-sky-400 hover:shadow-cyan-400/40
                 active:scale-[0.98] transition-all duration-150
                 disabled:opacity-60 disabled:cursor-not-allowed"
        >
          <svg
            v-if="!loading"
            class="w-5 h-5"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2.5"
          >
            <path d="M13 2 3 14h9l-1 8 10-12h-9l1-8Z" />
          </svg>
          <svg
            v-else
            class="w-5 h-5 animate-spin"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
          >
            <path d="M21 12a9 9 0 1 1-6.22-8.56" />
          </svg>
          {{ loading ? 'AI 正在深度排雷，大约需要 5-10 秒…' : '一键开始审计' }}
        </button>
      </div>
    </div>

    <!-- ========== 结果页面 ========== -->
    <div v-else ref="reportContainer" class="relative max-w-[1400px] mx-auto space-y-6">
      <!-- 操作栏：吸顶常驻，长报告滚动时始终可见；不参与截图 -->
      <div
        data-html2canvas-ignore="true"
        class="sticky top-0 z-30 bg-slate-900/95 backdrop-blur-md border border-slate-700/50 rounded-2xl shadow-2xl p-4 md:p-5 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3"
      >
        <span class="text-sm text-slate-400 uppercase tracking-widest">
          Audit Report
        </span>
        <div class="flex items-center gap-3">
          <button
            @click="backToInput"
            class="inline-flex items-center justify-center gap-2 px-5 py-2.5 rounded-lg font-semibold text-sm
                   bg-slate-800/70 border border-slate-600/60 text-slate-200
                   hover:bg-slate-700/70 hover:border-cyan-400/50 hover:text-cyan-300
                   active:scale-[0.98] transition-all duration-150"
          >
            <svg
              class="w-4 h-4"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
            >
              <path d="M3 12a9 9 0 1 0 3-6.7" />
              <path d="M3 4v5h5" />
            </svg>
            返回首页
          </button>
          <button
            @click="handleExport"
            :disabled="exporting || !audit"
            class="inline-flex items-center justify-center gap-2 px-5 py-2.5 rounded-lg font-semibold text-sm
                   bg-slate-800/70 border border-slate-600/60 text-slate-200
                   hover:bg-slate-700/70 hover:border-amber-400/50 hover:text-amber-300
                   active:scale-[0.98] transition-all duration-150
                   disabled:opacity-50 disabled:cursor-not-allowed"
          >
            <svg
              v-if="!exporting"
              class="w-4 h-4"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
            >
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
              <polyline points="7 10 12 15 17 10" />
              <line x1="12" y1="15" x2="12" y2="3" />
            </svg>
            <svg
              v-else
              class="w-4 h-4 animate-spin"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
            >
              <path d="M21 12a9 9 0 1 1-6.22-8.56" />
            </svg>
            {{ exporting ? '导出中…' : '导出报告' }}
          </button>
        </div>
      </div>

      <!-- 导出错误提示 -->
      <div
        v-if="exportError"
        data-html2canvas-ignore="true"
        class="bg-red-900/30 border border-red-500/40 rounded-xl px-4 py-3 text-sm text-red-300"
      >
        {{ exportError }}
      </div>

      <!-- 顶部：公司 + 仪表盘 -->
      <header
        class="bg-slate-900/60 backdrop-blur-md border border-slate-700/50 rounded-2xl shadow-2xl p-6 md:p-8"
      >
        <div class="grid grid-cols-1 lg:grid-cols-[1fr_280px] gap-6 items-center">
          <div>
            <p class="text-xs uppercase tracking-[0.3em] text-cyan-400/80 mb-2">
              Job Audit Dashboard
            </p>
            <h1
              v-if="audit"
              class="text-3xl md:text-5xl font-black bg-gradient-to-r from-cyan-300 via-sky-300 to-fuchsia-400 bg-clip-text text-transparent"
            >
              {{ audit.companyName }}
            </h1>
            <h1 v-else class="text-3xl md:text-5xl font-black text-slate-500">
              —
            </h1>
            <div
              v-if="audit"
              class="mt-3 flex flex-wrap items-center gap-3 text-sm text-slate-400"
            >
              <span class="px-2.5 py-1 rounded-md bg-slate-800/60 border border-slate-700/60">
                审计 ID：#{{ audit.auditId }}
              </span>
              <span class="px-2.5 py-1 rounded-md bg-slate-800/60 border border-slate-700/60">
                {{ audit.createdAt }}
              </span>
              <span
                class="px-2.5 py-1 rounded-md bg-cyan-500/10 border border-cyan-400/30 text-cyan-300"
              >
                {{ audit.status }}
              </span>
            </div>

            <!-- AI 毒舌锐评：仿打假报告排版 -->
            <div
              v-if="audit?.aiVerdict"
              class="mt-5 flex items-stretch gap-4 bg-slate-800/40 backdrop-blur-md border border-red-500/30 rounded-xl px-4 py-3 shadow-[0_0_20px_rgba(239,68,68,0.10)]"
            >
              <div
                class="shrink-0 self-center -rotate-6 select-none border-2 border-red-500/70 text-red-400
                       font-mono text-[10px] font-bold tracking-[0.15em] whitespace-nowrap
                       px-2.5 py-1 rounded-sm shadow-[0_0_12px_rgba(239,68,68,0.25)]"
              >
                [ WARNING: AI VERDICT ]
              </div>
              <p class="font-mono text-lg font-semibold text-amber-300 leading-relaxed">
                {{ audit.aiVerdict }}
              </p>
            </div>
          </div>
          <div ref="gaugeRef" class="h-[220px] w-full" />
        </div>
      </header>

      <!-- 避坑锦囊：紧跟毒舌锐评，暗黑毛玻璃 + 微黄警示边框 -->
      <section
        v-if="audit?.avoidanceTips?.length"
        class="mt-6 bg-slate-900/60 backdrop-blur-md border-2 border-amber-400/60 rounded-2xl
               shadow-[0_0_30px_rgba(251,191,36,0.20)] p-6"
      >
        <h2 class="flex items-center gap-2 text-lg font-bold text-amber-300 mb-4 tracking-wide">
          🛡️ 你的专属避坑锦囊
        </h2>
        <ul class="space-y-3">
          <li
            v-for="(tip, i) in audit.avoidanceTips"
            :key="i"
            class="flex items-start gap-2.5 text-base font-bold text-amber-100 leading-relaxed"
          >
            <span class="select-none">👉</span>
            <span>{{ tip }}</span>
          </li>
        </ul>
      </section>

      <!-- 结果页内错误（如导出等操作异常时不应出现，预留） -->
      <div
        v-if="error"
        class="bg-red-900/30 backdrop-blur-md border border-red-500/40 rounded-2xl p-10 text-center text-red-300"
      >
        {{ error }}
        <div class="text-xs text-red-400/70 mt-3">
          可点击「重新审计」返回输入页重试。
        </div>
      </div>

      <template v-if="audit">
        <!-- 中间：雷达图 + JD 原文 -->
        <section class="grid grid-cols-1 lg:grid-cols-2 gap-6">
          <div
            class="bg-slate-900/60 backdrop-blur-md border border-slate-700/50 rounded-2xl shadow-2xl p-5"
          >
            <div class="flex items-center justify-between mb-2">
              <h2 class="text-sm uppercase tracking-[0.25em] text-cyan-400/80">
                风险维度分布
              </h2>
              <span class="text-xs text-slate-500">Radar</span>
            </div>
            <div ref="radarRef" class="h-[340px] w-full" />
          </div>

          <div
            class="bg-slate-900/60 backdrop-blur-md border border-slate-700/50 rounded-2xl shadow-2xl p-5 flex flex-col"
          >
            <div class="flex items-center justify-between mb-3">
              <h2 class="text-sm uppercase tracking-[0.25em] text-cyan-400/80">
                岗位原文 JD
              </h2>
              <span class="text-xs text-slate-500">
                {{ audit.inputText.length }} 字符
              </span>
            </div>
            <div
              class="flex-1 overflow-auto pr-2 text-slate-400 text-sm leading-relaxed whitespace-pre-wrap"
              style="max-height: 360px"
            >
              {{ audit.inputText }}
            </div>
          </div>
        </section>

        <!-- 底部：信号卡片 -->
        <section>
          <div class="flex items-center justify-between mb-4">
            <h2 class="text-sm uppercase tracking-[0.25em] text-cyan-400/80">
              风险信号明细
            </h2>
            <span class="text-xs text-slate-500">
              共 {{ audit.signals.length }} 条
            </span>
          </div>

          <div
            v-if="audit.signals.length === 0"
            class="bg-slate-900/60 backdrop-blur-md border border-slate-700/50 rounded-2xl p-8 text-center text-slate-400"
          >
            ✅ 未检测到明显风险信号
          </div>

          <div v-else class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
            <div
              v-for="sig in audit.signals"
              :key="sig.id"
              :class="signalCardClass(sig)"
            >
              <div class="flex items-start justify-between gap-3">
                <h3 class="text-lg font-bold text-slate-100">
                  {{ signalTypeLabel(sig.signalType) }}
                </h3>
                <span :class="severityTagClass(sig.severity)">
                  {{ sig.severity }}
                </span>
              </div>

              <p class="mt-3 text-sm text-slate-400 leading-relaxed">
                {{ sig.reason }}
              </p>

              <div class="mt-4 flex items-center justify-between">
                <span :class="sourceTagClass(sig.source)">
                  <svg
                    v-if="sig.source === 'LLM'"
                    class="w-3.5 h-3.5"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="2"
                  >
                    <path
                      d="M9 18h6M10 22h4M12 2a7 7 0 0 0-4 12.7V17h8v-2.3A7 7 0 0 0 12 2Z"
                    />
                  </svg>
                  <svg
                    v-else
                    class="w-3.5 h-3.5"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="2"
                  >
                    <path
                      d="M12 2 4 6v6c0 5 3.5 9.5 8 10 4.5-.5 8-5 8-10V6l-8-4Z"
                    />
                  </svg>
                  {{ sourceLabel(sig.source) }}
                </span>
                <span
                  v-if="sig.confidence != null"
                  class="text-xs text-slate-500"
                >
                  置信度 {{ (sig.confidence * 100).toFixed(0) }}%
                </span>
              </div>
            </div>
          </div>
        </section>

        <!-- AI 建议：面试反问话术 -->
        <section
          class="bg-slate-900/60 backdrop-blur-md border border-slate-700/50 rounded-2xl shadow-2xl p-5"
        >
          <div class="flex items-center justify-between mb-4">
            <h2 class="text-sm uppercase tracking-[0.25em] text-cyan-400/80">
              AI 建议：面试如何反向背调？
            </h2>
            <span class="text-xs text-slate-500">Interview Questions</span>
          </div>

          <div
            v-if="questionsLoading"
            class="flex items-center gap-3 text-slate-400 text-sm py-4"
          >
            <svg
              class="w-4 h-4 animate-spin text-cyan-400"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
            >
              <path d="M21 12a9 9 0 1 1-6.22-8.56" />
            </svg>
            AI 正在为您生成面试策略...
          </div>

          <div
            v-else-if="questions.length === 0"
            class="text-slate-500 text-sm py-2"
          >
            暂无可用话术
          </div>

          <ol v-else class="space-y-4">
            <li
              v-for="(q, i) in questions"
              :key="i"
              class="flex items-start gap-4"
            >
              <span
                class="shrink-0 mt-1 w-2.5 h-2.5 rounded-full bg-cyan-400 shadow-[0_0_10px_rgba(34,211,238,0.9)]"
              />
              <div class="flex-1">
                <span class="text-xs font-mono text-cyan-400/70 mr-2">
                  Q{{ i + 1 }}
                </span>
                <span class="text-slate-200 leading-relaxed">{{ q }}</span>
              </div>
            </li>
          </ol>
        </section>
      </template>
    </div>
  </div>
</template>

<style>
/* ===== 职场数字侦探：点阵底纹 ===== */
.da-dot-grid {
  background-image: radial-gradient(
    rgba(148, 163, 184, 0.18) 1px,
    transparent 1px
  );
  background-size: 22px 22px;
  background-position: 0 0;
}

/* ===== 终端扫描线（极慢向下漂移，制造 CRT 感） ===== */
.da-scanlines {
  background-image: repeating-linear-gradient(
    to bottom,
    rgba(148, 163, 184, 0.05) 0px,
    rgba(148, 163, 184, 0.05) 1px,
    transparent 1px,
    transparent 4px
  );
  animation: da-scanline-drift 8s linear infinite;
}

@keyframes da-scanline-drift {
  from {
    background-position-y: 0;
  }
  to {
    background-position-y: 24px;
  }
}

/* ===== HIGH 卡片左侧黄黑警戒线 ===== */
.da-hazard {
  position: relative;
  overflow: hidden;
}

.da-hazard::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 6px;
  background-image: repeating-linear-gradient(
    135deg,
    #fbbf24 0px,
    #fbbf24 6px,
    #111827 6px,
    #111827 12px
  );
  box-shadow: 0 0 10px rgba(251, 191, 36, 0.25);
}

/* 尊重系统的减少动效偏好 */
@media (prefers-reduced-motion: reduce) {
  .da-scanlines {
    animation: none;
  }
}

/* 让滚动条跟暗黑主题协调 */
::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}
::-webkit-scrollbar-track {
  background: transparent;
}
::-webkit-scrollbar-thumb {
  background: rgba(148, 163, 184, 0.35);
  border-radius: 3px;
}
::-webkit-scrollbar-thumb:hover {
  background: rgba(148, 163, 184, 0.55);
}
</style>
