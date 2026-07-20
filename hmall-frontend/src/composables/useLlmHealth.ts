/**
 * LLM 健康状态轮询 Composable
 *
 * 定期轮询后端 /api/v1/llm/health 端点，根据 LLM API 远程调用是否通畅
 * 动态更新状态。供 ChatPanel / AdminLayout 等组件展示真实在线状态。
 *
 * 轮询间隔 30 秒，组件卸载时自动停止。
 */

import { ref, computed, onMounted, onUnmounted, type Ref, type ComputedRef } from 'vue'

export type LlmStatus = 'online' | 'offline' | 'checking'

export interface UseLlmHealthOptions {
  /** LangGraph Server URL，默认取 VITE_AGENT_URL 环境变量 */
  apiUrl?: string
  /** 轮询间隔（毫秒），默认 30000 */
  intervalMs?: number
}

export interface LlmHealthResult {
  llmStatus: Ref<LlmStatus>
  statusText: ComputedRef<string>
  statusType: ComputedRef<'success' | 'danger' | 'info'>
  latencyMs: Ref<number | null>
  detail: Ref<string | null>
  refresh: () => Promise<void>
}

const STATUS_TEXT_MAP: Record<LlmStatus, string> = {
  online: '在线',
  offline: '离线',
  checking: '检测中',
}

const STATUS_TYPE_MAP: Record<LlmStatus, 'success' | 'danger' | 'info'> = {
  online: 'success',
  offline: 'danger',
  checking: 'info',
}

export function useLlmHealth(options: UseLlmHealthOptions = {}): LlmHealthResult {
  const apiUrl = options.apiUrl || import.meta.env.VITE_AGENT_URL || 'http://localhost:8090'
  const intervalMs = options.intervalMs ?? 30000

  const llmStatus = ref<LlmStatus>('checking')
  const latencyMs = ref<number | null>(null)
  const detail = ref<string | null>(null)
  let timer: ReturnType<typeof setInterval> | null = null
  let polling = false

  const statusText = computed(() => STATUS_TEXT_MAP[llmStatus.value])
  const statusType = computed(() => STATUS_TYPE_MAP[llmStatus.value])

  async function checkOnce() {
    if (polling) return
    polling = true
    try {
      const resp = await fetch(`${apiUrl}/api/v1/llm/health`, {
        method: 'GET',
        headers: { 'Content-Type': 'application/json' },
      })
      if (!resp.ok) {
        llmStatus.value = 'offline'
        detail.value = `HTTP ${resp.status}`
        return
      }
      const data = await resp.json()
      if (data.llm_reachable === true) {
        llmStatus.value = 'online'
        latencyMs.value = data.latency_ms ?? null
        detail.value = null
      } else {
        llmStatus.value = 'offline'
        detail.value = data.detail || 'LLM API 不可达'
      }
    } catch (e) {
      llmStatus.value = 'offline'
      detail.value = e instanceof Error ? e.message : String(e)
    } finally {
      polling = false
    }
  }

  async function refresh() {
    await checkOnce()
  }

  function start() {
    if (timer) return
    checkOnce()
    timer = setInterval(checkOnce, intervalMs)
  }

  function stop() {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  onMounted(start)
  onUnmounted(stop)

  return {
    llmStatus,
    statusText,
    statusType,
    latencyMs,
    detail,
    refresh,
  }
}
