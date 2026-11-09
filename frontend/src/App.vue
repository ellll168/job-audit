<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import axios from 'axios'
import html2canvas from 'html2canvas'
import * as echarts from 'echarts'

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
}

/* ---------- 响应式状态 ---------- */

const loading = ref(true)
const error = ref<string | null>(null)
const audit = ref<AuditResult | null>(null)

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
    (acc, s) => acc + (weight[s.severity] ?? 0),
    0,
  )
  return Math.min(100, raw)
})

/** 四个风险维度的当前值（0-100），供雷达图使用 */
const dimensionValues = computed<number[]>(() => {
  const vals: Record<string, number> = { duty: 0, salary: 0, pua: 0, info: 0 }
  if (audit.value) {
    for (const s of audit.value.signals) {
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
    return `${base} border-l-4 border-l-red-500 shadow-red-500/20`
  }
  if (s.severity === 'MEDIUM') {
    return `${base} border-l-4 border-l-orange-500`
  }
  return `${base} border-l-4 border-l-slate-500`
}

/* ---------- Audit ID 输入 ---------- */

const auditIdInput = ref('28')
const currentAuditId = ref<number>(28)

/* ---------- 导出报告 ---------- */

const reportRef = ref<HTMLElement | null>(null)
const exporting = ref(false)

async function exportReport() {
  const target = reportRef.value
  if (!target || exporting.value) return
  exporting.value = true
  try {
    // 让 echarts canvas 在截图中保持清晰：先触发一次 resize 重绘
    await nextTick()
    const canvas = await html2canvas(target, {
      scale: 2,
      backgroundColor: '#0b1120',
      useCORS: true,
      logging: false,
      windowWidth: target.scrollWidth,
    })
    const dataUrl = canvas.toDataURL('image/png')
    const link = document.createElement('a')
    const stamp = new Date()
      .toISOString()
      .replace(/[:T]/g, '-')
      .slice(0, 19)
    link.download = `audit-report-${currentAuditId.value}-${stamp}.png`
    link.href = dataUrl
    link.click()
  } catch (e) {
    console.error('导出报告失败：', e)
  } finally {
    exporting.value = false
  }
}

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

/* ---------- 信号卡片样式 ---------- */

function sourceLabel(source: string): string {
  return source === 'LLM' ? 'AI 语义' : '规则命中'
}

function sourceTagClass(source: string): string {
  return source === 'LLM'
    ? 'inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium bg-emerald-500/15 text-emerald-300 border border-emerald-400/40 shadow-[0_0_12px_rgba(16,185,129,0.35)]'
    : 'inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium bg-sky-500/15 text-sky-300 border border-sky-400/40'
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
  }
  return map[type] ?? type
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

/* ---------- 数据加载 ---------- */

/** 清空已有数据与图表，用于报错或重新加载前的重置 */
function resetState() {
  audit.value = null
  if (gaugeChart) {
    gaugeChart.dispose()
    gaugeChart = null
  }
  if (radarChart) {
    radarChart.dispose()
    radarChart = null
  }
}

async function loadAudit() {
  loading.value = true
  error.value = null
  // 先清空旧数据，避免接口报错时页面残留上一次的 companyName / 图表
  resetState()
  try {
    const res = await axios.get<AuditResult>(
      `http://localhost:8080/api/audits/${currentAuditId.value}`,
    )
    audit.value = res.data
  } catch {
    error.value = '未找到审计记录，请检查输入 ID 是否正确'
  }
  loading.value = false
  if (audit.value) {
    await nextTick()
    initGauge(riskScore.value)
    initRadar(dimensionValues.value)
  }
}

/* ---------- 生命周期 ---------- */

onMounted(() => {
  loadAudit()
  window.addEventListener('resize', resizeCharts)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeCharts)
  gaugeChart?.dispose()
  radarChart?.dispose()
})
</script>

<template>
  <div class="min-h-screen overflow-y-auto bg-[#0b1120] text-slate-100 p-6 md:p-10">
    <!-- 背景光斑 -->
    <div
      class="pointer-events-none fixed inset-0 overflow-hidden"
      aria-hidden="true"
    >
      <div
        class="absolute -top-40 -left-40 w-[480px] h-[480px] rounded-full opacity-30 blur-3xl"
        style="background: radial-gradient(circle, #0ea5e9 0%, transparent 70%)"
      />
      <div
        class="absolute -bottom-40 -right-40 w-[520px] h-[520px] rounded-full opacity-25 blur-3xl"
        style="background: radial-gradient(circle, #a855f7 0%, transparent 70%)"
      />
    </div>

    <div ref="reportRef" class="relative max-w-[1400px] mx-auto space-y-6">
      <!-- Audit ID 输入栏 -->
      <div
        class="bg-slate-900/60 backdrop-blur-md border border-slate-700/50 rounded-2xl shadow-2xl p-4 md:p-5 flex flex-col sm:flex-row items-stretch sm:items-center gap-3"
      >
        <div class="flex items-center gap-2 text-sm text-slate-400 shrink-0">
          <svg
            class="w-4 h-4 text-cyan-400"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
          >
            <circle cx="11" cy="11" r="7" />
            <path d="m21 21-4.3-4.3" />
          </svg>
          <span class="uppercase tracking-widest text-slate-500">Audit ID</span>
        </div>
        <input
          v-model="auditIdInput"
          type="text"
          inputmode="numeric"
          placeholder="输入审计 ID，如 28"
          class="flex-1 min-w-0 bg-slate-800/70 border border-slate-700/60 focus:border-cyan-400/60 focus:outline-none focus:ring-2 focus:ring-cyan-400/20 rounded-lg px-4 py-2.5 text-slate-100 placeholder-slate-500 font-mono transition"
          @keyup.enter="reloadWithCurrentId"
        />
        <button
          @click="reloadWithCurrentId"
          :disabled="loading"
          class="shrink-0 inline-flex items-center justify-center gap-2 px-6 py-2.5 rounded-lg font-semibold text-sm
                 bg-gradient-to-r from-cyan-500 to-sky-500 text-white shadow-lg shadow-cyan-500/30
                 hover:from-cyan-400 hover:to-sky-400 hover:shadow-cyan-400/40
                 active:scale-[0.98] transition-all duration-150
                 disabled:opacity-60 disabled:cursor-not-allowed"
        >
          <svg
            v-if="!loading"
            class="w-4 h-4"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2.5"
          >
            <path d="M13 2 3 14h9l-1 8 10-12h-9l1-8Z" />
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
          {{ loading ? '审计中…' : '开始审计' }}
        </button>
        <button
          @click="exportReport"
          :disabled="exporting || !audit"
          class="shrink-0 inline-flex items-center justify-center gap-2 px-6 py-2.5 rounded-lg font-semibold text-sm
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
          </div>
          <div ref="gaugeRef" class="h-[220px] w-full" />
        </div>
      </header>

      <!-- 加载 / 错误 -->
      <div
        v-if="loading"
        class="bg-slate-900/60 backdrop-blur-md border border-slate-700/50 rounded-2xl shadow-2xl p-10 text-center text-slate-400"
      >
        正在拉取审计数据 …
      </div>
      <div
        v-else-if="error"
        class="bg-red-900/30 backdrop-blur-md border border-red-500/40 rounded-2xl p-10 text-center text-red-300"
      >
        {{ error }}
        <div class="text-xs text-red-400/70 mt-3">
          可在上方输入栏重新输入 Audit ID 后点击「开始审计」重试。
        </div>
      </div>

      <template v-else-if="audit">
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
                  <!-- AI 语义：发光电灯泡图标 -->
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
                  <!-- 规则命中：盾牌图标 -->
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
      </template>
    </div>
  </div>
</template>

<style>
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
