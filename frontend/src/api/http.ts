import axios, { type AxiosError } from 'axios'
import { ElMessage } from 'element-plus'

/** 后端接口基地址：优先读取项目根目录 .env 中的 VITE_API_BASE_URL，未配置时回落到本地默认值 */
const BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080'

/** 审计接口要等大模型返回，超时留足 60 秒 */
const TIMEOUT = 60_000

const http = axios.create({
  baseURL: BASE_URL,
  timeout: TIMEOUT,
  headers: { 'Content-Type': 'application/json' },
})

/** 统一弹出深色红色告警，任何接口异常都不会变成未捕获异常导致白屏 */
function showError(message: string) {
  ElMessage({
    message,
    type: 'error',
    duration: 4000,
    showClose: true,
    grouping: true,
    customClass: 'ja-error-message',
  })
}

/** 把 Axios 错误翻译成给用户看的中文提示 */
function resolveMessage(error: AxiosError): string {
  const isTimeout = error.code === 'ECONNABORTED'
    || error.code === 'ETIMEDOUT'
    || error.message.toLowerCase().includes('timeout')
  if (isTimeout) {
    return '请求超时，AI 分析耗时较长，请稍后重试'
  }
  const status = error.response?.status
  if (status === 400) {
    // 后端参数校验失败时带上具体原因（如 JD 超长），优先展示
    const data = error.response?.data as { message?: string } | undefined
    return data?.message ?? '请求参数不合法，请检查后重试'
  }
  if (status === 500) {
    return '服务器内部错误（500），请稍后重试'
  }
  if (status === undefined) {
    return '网络异常，无法连接后端服务，请确认服务已启动'
  }
  return `请求失败（${status}），请稍后重试`
}

http.interceptors.response.use(
  (response) => response,
  (error: AxiosError) => {
    console.error('接口请求失败：', error.config?.url, error.message)
    showError(resolveMessage(error))
    // 继续抛出，让调用方仍能走自己的 catch 分支做页面级降级
    return Promise.reject(error)
  },
)

export default http
