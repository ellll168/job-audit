# 会话记录：仪表盘纯色动态配色重构

> 日期：2026-11-16
> 范围：`frontend/src/App.vue`（ECharts 仪表盘 `initGauge` / `getScoreColor` / `riskScore`）
> 目标：消除固定红绿渐变带来的视觉误导，让仪表盘颜色只表达当前分数的风险等级

---

## 一、需求原文

> 我要重构 ECharts 仪表盘的颜色逻辑。现在的固定渐变有视觉误导，请按以下"纯色动态渐变"逻辑重写：
>
> 1. 写一个 computed 或基于传入 score 的函数，根据分数返回颜色值：
>    - `score < 60`：纯绿色（如 `#10b981`）
>    - `60 <= score < 80`：纯橙色（如 `#f59e0b`）
>    - `score >= 80`：纯红色（如 `#ef4444`）
> 2. 把这个颜色值同时应用到进度条和中间的大数字上。
> 3. 不要出现任何渐变色块，低于 60 分时整个仪表盘就是一整圈纯粹的绿色。

---

## 二、问题诊断

改造前的 `initGauge`：

```ts
progress: { show: true, width: 16, itemStyle: { color: getScoreColor(score) } },
axisLine: {
  lineStyle: {
    width: 16,
    color: [
      [0.6, '#22c55e'],
      [1, '#ef4444'],
    ],
  },
},
```

`getScoreColor` 也只有两档（`>=60` 红，否则绿）。

存在三个问题：

| 问题 | 表现 |
|---|---|
| 背景轨道是**固定**的红绿渐变 | 即使 0 分，环上 60% 之后的部分依然是一条红色，看起来像"已经踩到预警区" |
| 颜色档位与分数语义不匹配 | 没有橙色过渡区，60 分和 95 分视觉上完全一样 |
| 进度弧与轨道色相不一致 | 低分时绿色进度 + 红色轨道，一条环出现两种结论 |

结论：颜色应当由**当前分数**单一决定，整圈只表达一个风险等级。

---

## 三、核心实现

### 3.1 分数 → 纯色

```ts
/** 分数 → 纯色：<60 绿，60~79 橙，>=80 红，不返回任何渐变 */
function getScoreColor(score: number): string {
  if (score >= 80) return '#ef4444'
  if (score >= 60) return '#f59e0b'
  return '#10b981'
}
```

放在 `initGauge` 下方，保持"图表相关函数聚在一起"的组织方式，没有额外引入 `computed`——颜色是每次渲染时的局部量，不需要响应式。

### 3.2 完整 option

```ts
function initGauge(score: number) {
  const dom = gaugeRef.value
  if (!dom) return
  // 按当前分数取一个纯色，整块仪表盘的色相由它决定，不再使用固定红绿渐变
  const color = getScoreColor(score)
  gaugeChart?.dispose()
  gaugeChart = echarts.init(dom, undefined, { renderer: 'canvas' })
  gaugeChart.setOption({
    // 关闭入场动画：截图/导出时不会出现半截圆弧
    animation: false,
    series: [
      {
        type: 'gauge',
        startAngle: 220,
        endAngle: -40,
        min: 0,
        max: 100,
        radius: '95%',
        // 进度条：纯色，无渐变
        progress: { show: true, width: 16, roundCap: true, itemStyle: { color } },
        // 背景圈：用单段色标（ECharts 推荐写法）铺满整圈，与进度条同色
        axisLine: {
          lineStyle: {
            width: 16,
            color: [[1, color]],
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
          color,
          formatter: '{value}',
        },
        data: [{ value: score }],
      },
    ],
  })
}
```

三处用到同一个 `color` 变量：`progress.itemStyle.color`、`axisLine.lineStyle.color`、`detail.color`。这样"环"和"数字"永远同一个色相。

---

## 四、验证：三档实测

用 demo profile（H2、无 Key）构造三段不同严重度的 JD，再用 headless Chrome 截图比对。

| 分数 | 触发的信号 | 渲染结果 |
|---|---|---|
| 35 | `salary_opacity`(HIGH) + `vague_duty`(MEDIUM) + `urgency_noise`(LOW) + `overwork_risk`(MEDIUM) | 整圈纯绿 `#10b981`，环上无任何红色残留 |
| 65 | 同上再加 `scam_risk`(HIGH) | 整圈纯橙 `#f59e0b` |
| 95 | 再加押金/培训费关键词 + 极短文本（`information_missing`） | 整圈纯红 `#ef4444`，封顶 95 不到 100 |

改造前后对比：

- 改造前：35 分的环是"60% 绿 + 40% 红"的固定渐变，视觉上像已经进入高危区
- 改造后：35 分的环是完整一圈绿，颜色与中间数字一致

---

## 五、踩坑记录

### 5.1 `axisLine.color` 传单个字符串不铺满整圈

第一次实现写成：

```ts
axisLine: { lineStyle: { width: 16, color } }
```

实际渲染只画出了进度弧，背景轨道完全不可见（低分时看上去是"半截绿弧 + 空环"），不符合"整圈纯色"。

改为 ECharts 文档中的色标数组写法后正常：

```ts
axisLine: { lineStyle: { width: 16, color: [[1, color]] } }
```

`[[1, color]]` 表示"从 0 到 1 全部使用该颜色"，等价于纯色但走的是色标解析路径。

> 中间还试过 `opacity: 0.22` 做"淡色底轨"，结果整圈几乎看不见，反而更像图表没渲染出来，已弃用。

### 5.2 入场动画导致截图出现半截圆弧

ECharts 仪表盘的 `progress` 默认有入场动画。headless 截图时如果抓在动画中途，会得到一圈不完整的弧，容易误判成"配置没生效"。

解决：根级加 `animation: false`。仪表盘是一次性快照式指标，没有动画损失；`detail.valueAnimation` 保留数字滚动。

### 5.3 权重与配色阈值不匹配

此前为了"防止分数溢出 100"，权重被下调到 `HIGH 20 / MEDIUM 9 / LOW 4` 且封顶 95。结果：

- 一段明确很差的 JD（面议 + 单休 + 领导交办 + 押金）只有 42 分
- 在新的三档配色下永远停在绿色，"橙色/红色"两档成了死代码

重新校准为：

```ts
// 与仪表盘三档配色对齐：2 HIGH + 2 MEDIUM 刚好 90（红），1 HIGH + 2 MEDIUM 刚好 60（橙）。
// 封顶 95 而不是 100，避免"随便一段差 JD 就满分"。
const weight: Record<string, number> = { HIGH: 30, MEDIUM: 15, LOW: 5 }
...
return Math.min(95, raw)
```

校准后的分数分布：

| 信号组合 | 分数 | 颜色 |
|---|---|---|
| 1 HIGH + 2 MEDIUM | 60 | 橙 |
| 2 HIGH + 2 MEDIUM | 90 | 红 |
| 5 HIGH（极端） | 150 → 95 | 红（封顶） |
| 3 MEDIUM + 1 LOW | 50 | 绿 |

**经验：权重、封顶值、颜色阈值三者必须一起设计**，否则会出现"阈值存在但永远命中不到"的假功能。

---

## 六、取舍说明

整圈使用同一纯色、且 `progress` 与 `axisLine` 同色后，**圆弧长度不再能表达进度**——环永远是满的，分数只由中间数字表达。这是"整圈纯色"需求的直接结果，不是 bug。

若后续想恢复"进度感"，只需把背景轨道改成同色低透明度：

```ts
axisLine: { lineStyle: { width: 16, color: [[1, color + '33']] } },
```

这样得到"同色淡底 + 纯色进度弧"，既不会出现无关色相，也能看出比例。当前版本按需求保留整圈纯色。

---

## 七、结论

1. 颜色是**风险等级**的表达，不应携带"区间刻度"信息；用色标数组画渐变会让低分 JD 看起来已经危险。
2. 颜色只在一个函数里定义（`getScoreColor`），option 里三处引用同一变量，避免"环和数字不同色"。
3. ECharts 仪表盘背景环用 `[[1, color]]`，不要依赖字符串色值。
4. 需要截图/导出时关闭 `animation`，避免半截圆弧进入报告。
5. 调整评分权重前，先看它落在哪个颜色档，避免阈值失效。
