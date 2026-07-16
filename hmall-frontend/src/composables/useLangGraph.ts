/**
 * LangGraph Vue Composable
 *
 * 封装 @langchain/langgraph-sdk 1.x Client，管理对话状态，
 * 提供 sendMessage / resume / clearHistory 方法。
 *
 * SDK 1.x 的 runs.stream() 正确转发 context / command 字段，
 * 无需 fetch 绕过。支持 customer_agent 和 admin_agent 两种 Agent。
 */

import { Client } from '@langchain/langgraph-sdk'
import { ref, type Ref } from 'vue'

export interface ChatMessage {
  id: string
  type: 'human' | 'ai' | 'system'
  content: string
  timestamp: number
}

export interface ThreadSummary {
  thread_id: string
  title: string
  updated_at: string
  preview: string
}

export interface InterruptData {
  type: string
  message: string
  expected_response?: string
}

export interface AgentContext {
  agent_type: 'customer' | 'admin'
  user_token: string
  enable_rag?: boolean
}

export interface UseLangGraphOptions {
  /** LangGraph Server URL，默认取 VITE_AGENT_URL 环境变量 */
  apiUrl?: string
  /** Agent ID：customer_agent 或 admin_agent */
  assistantId: 'customer_agent' | 'admin_agent'
}

export function useLangGraph(options: UseLangGraphOptions) {
  const apiUrl = options.apiUrl || import.meta.env.VITE_AGENT_URL || 'http://localhost:8090'
  const assistantId = options.assistantId

  const client = new Client({ apiUrl })

  // ==================== 响应式状态 ====================
  const messages: Ref<ChatMessage[]> = ref([])
  const isLoading = ref(false)
  const interruptData: Ref<InterruptData | null> = ref(null)
  const threadId: Ref<string | null> = ref(null)
  const error: Ref<string | null> = ref(null)
  const threads: Ref<ThreadSummary[]> = ref([])
  const isThreadsLoading = ref(false)
  let _currentContext: AgentContext | null = null  // 缓存当前 context，供 resume 复用

  // ==================== 内部方法 ====================

  /** 生成唯一 ID */
  function _genId(): string {
    return `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
  }

  /** 从 SDK Message 对象中提取文本内容 */
  function _extractContent(msg: { content: unknown }): string {
    const content = msg.content
    if (typeof content === 'string') return content
    if (Array.isArray(content)) {
      return content
        .map((p: any) => {
          if (typeof p === 'string') return p
          if (p?.type === 'text') return p.text || ''
          return ''
        })
        .join('')
    }
    return ''
  }

  /** 处理 SSE 流式响应 */
  async function _processStream(streamResponse: AsyncGenerator<any>) {
    let aiMessage: ChatMessage | null = null

    for await (const chunk of streamResponse) {
      // 处理消息增量更新（流式 token + 完整消息）
      if (chunk.event === 'messages/partial' || chunk.event === 'messages/complete') {
        for (const msg of chunk.data || []) {
          // SDK 1.x 统一使用 type="ai"，不再出现 "AIMessageChunk"
          if (msg.type !== 'ai') continue

          const content = _extractContent(msg)
          if (!content) continue

          // 同一条 AI 消息（同 id）复用同一个 ChatMessage，实现增量更新
          const msgId = msg.id || _genId()
          if (!aiMessage || aiMessage.id !== msgId) {
            aiMessage = {
              id: msgId,
              type: 'ai',
              content,
              timestamp: Date.now(),
            }
            messages.value.push(aiMessage)
          } else {
            // 通过响应式数组索引更新，确保 Vue 检测到变化
            // 直接修改 aiMessage.content 会绕过 Proxy，导致 UI 不刷新
            const idx = messages.value.findIndex(m => m.id === msgId)
            if (idx !== -1) {
              messages.value[idx].content = content
            }
          }
        }
      }

      // 处理 values 事件（检查 interrupt）
      if (chunk.event === 'values') {
        const data = chunk.data || {}

        // 检测 interrupt
        if (data.__interrupt__) {
          const interrupt = Array.isArray(data.__interrupt__)
            ? data.__interrupt__[0]
            : data.__interrupt__

          // interrupt value 可能是 {type, message, ...} 结构
          const value = interrupt?.value || interrupt
          if (typeof value === 'object' && value?.message) {
            interruptData.value = value as InterruptData
          } else if (typeof value === 'string') {
            interruptData.value = {
              type: 'confirmation',
              message: value,
            }
          }
        }
      }

      // 处理错误 —— 在 UI 中展示错误消息
      if (chunk.event === 'error') {
        const errMsg = chunk.data?.message || chunk.data?.error || 'Agent 处理失败'
        error.value = errMsg
        messages.value.push({
          id: _genId(),
          type: 'ai',
          content: `❌ ${errMsg}`,
          timestamp: Date.now(),
        })
        break
      }
    }
  }

  // ==================== 公共方法 ====================

  /**
   * 发送消息（流式）
   * @param text 用户消息文本
   * @param context Agent 上下文（agent_type, user_token, enable_rag）
   */
  async function sendMessage(text: string, context: AgentContext) {
    if (!text.trim() || isLoading.value) return

    error.value = null
    isLoading.value = true
    _currentContext = context  // 缓存 context，供 resume 复用

    // 添加用户消息到 UI
    messages.value.push({
      id: _genId(),
      type: 'human',
      content: text,
      timestamp: Date.now(),
    })

    try {
      // 创建或复用 Thread
      if (!threadId.value) {
        const thread = await client.threads.create()
        threadId.value = thread.thread_id
      }

      // 流式调用 Agent —— SDK 1.x 正确转发 context 字段
      // LangGraph 0.6.0+ 禁止同时传 configurable 和 context，统一用 context
      const streamResponse = client.runs.stream(threadId.value, assistantId, {
        input: {
          messages: [{ type: 'human', content: text }],
        },
        config: {
          recursion_limit: 100,
        },
        context,
        streamMode: ['messages', 'values'],
      })

      await _processStream(streamResponse)
      // 发送后异步刷新会话列表（不阻塞 UI）
      fetchThreads()
    } catch (e: any) {
      // 添加错误提示消息
      messages.value.push({
        id: _genId(),
        type: 'ai',
        content: `❌ 发送失败：${error.value}`,
        timestamp: Date.now(),
      })
    } finally {
      isLoading.value = false
    }
  }

  /**
   * 恢复中断（二次确认 / 多轮交互）
   * @param value 用户回复的确认值
   */
  async function resume(value: string) {
    if (!threadId.value || isLoading.value) return

    interruptData.value = null
    error.value = null
    isLoading.value = true

    // 添加用户回复到 UI
    messages.value.push({
      id: _genId(),
      type: 'human',
      content: value,
      timestamp: Date.now(),
    })

    try {
      // SDK 1.x 正确转发 command + context 字段
      const streamResponse = client.runs.stream(threadId.value, assistantId, {
        command: { resume: value },
        config: {
          recursion_limit: 100,
        },
        context: _currentContext || undefined,
        streamMode: ['messages', 'values'],
      })

      await _processStream(streamResponse)
    } catch (e: any) {
      error.value = e.message || '恢复中断失败'
      messages.value.push({
        id: _genId(),
        type: 'ai',
        content: `❌ 操作失败：${error.value}`,
        timestamp: Date.now(),
      })
    } finally {
      isLoading.value = false
    }
  }

  /**
   * 拒绝中断（取消操作）
   */
  async function rejectInterrupt() {
    if (!threadId.value) return
    interruptData.value = null
    isLoading.value = true

    try {
      const streamResponse = client.runs.stream(threadId.value, assistantId, {
        command: { goto: '__end__' },
        config: { recursion_limit: 100 },
        streamMode: ['messages', 'values'],
      })
      await _processStream(streamResponse)
    } catch {
      // 忽略错误
    } finally {
      isLoading.value = false
    }
  }

  /**
   * 清除对话历史
   */
  async function clearHistory() {
    if (threadId.value) {
      try {
        await client.threads.delete(threadId.value)
      } catch {
        // 忽略删除错误
      }
    }
    threadId.value = null
    messages.value = []
    interruptData.value = null
    error.value = null
    _currentContext = null
    await fetchThreads()
  }

  // ==================== 会话管理 ====================

  /** 从线程状态中提取消息列表 */
  function _parseMessagesFromState(state: any): ChatMessage[] {
    const msgs = state?.values?.messages || state?.messages || []
    if (!Array.isArray(msgs)) return []
    return msgs
      .filter((m: any) => m.type === 'human' || m.type === 'ai')
      .map((m: any) => ({
        id: m.id || _genId(),
        type: m.type as 'human' | 'ai',
        content: _extractContent(m),
        timestamp: Date.now(),
      }))
      .filter((m: ChatMessage) => m.content)
  }

  /** 从消息列表生成预览文本 */
  function _getPreview(msgs: ChatMessage[]): string {
    const first = msgs.find(m => m.type === 'human' || m.type === 'ai')
    return first ? first.content.slice(0, 40) : '新对话'
  }

  /** 获取会话列表 */
  async function fetchThreads() {
    isThreadsLoading.value = true
    try {
      const list = await client.threads.search({
        limit: 50,
        offset: 0,
      })
      const summaries: ThreadSummary[] = []
      for (const t of list) {
        let preview = '新对话'
        try {
          const state = await client.threads.getState(t.thread_id)
          const msgs = _parseMessagesFromState(state)
          preview = _getPreview(msgs)
        } catch {
          // 状态获取失败时使用默认预览
        }
        summaries.push({
          thread_id: t.thread_id,
          title: preview,
          updated_at: t.updated_at,
          preview,
        })
      }
      threads.value = summaries
    } catch {
      // 忽略列表获取错误
    } finally {
      isThreadsLoading.value = false
    }
  }

  /** 切换到已有会话 */
  async function switchThread(targetThreadId: string) {
    if (targetThreadId === threadId.value) return
    if (isLoading.value) return

    threadId.value = targetThreadId
    messages.value = []
    interruptData.value = null
    error.value = null

    try {
      isLoading.value = true
      const state = await client.threads.getState(targetThreadId)
      const msgs = _parseMessagesFromState(state)
      messages.value = msgs

      // 检查是否有未完成的 interrupt
      const interrupts = (state as any)?.interrupts
      if (interrupts && typeof interrupts === 'object') {
        for (const key of Object.keys(interrupts)) {
          const arr = interrupts[key]
          if (Array.isArray(arr) && arr.length > 0) {
            const value = arr[0]?.value || arr[0]
            if (typeof value === 'object' && value?.message) {
              interruptData.value = value as InterruptData
            } else if (typeof value === 'string') {
              interruptData.value = { type: 'confirmation', message: value }
            }
            break
          }
        }
      }
    } catch (e: any) {
      error.value = e.message || '加载会话失败'
    } finally {
      isLoading.value = false
    }
  }

  /** 新建会话（清空当前状态，不删除旧会话） */
  function newConversation() {
    if (isLoading.value) return
    threadId.value = null
    messages.value = []
    interruptData.value = null
    error.value = null
    _currentContext = null
  }

  /** 删除指定会话 */
  async function deleteThread(targetThreadId: string) {
    try {
      await client.threads.delete(targetThreadId)
      if (targetThreadId === threadId.value) {
        threadId.value = null
        messages.value = []
        interruptData.value = null
        _currentContext = null
      }
      await fetchThreads()
    } catch {
      // 忽略删除错误
    }
  }

  return {
    // 状态
    messages,
    isLoading,
    interruptData,
    threadId,
    error,
    threads,
    isThreadsLoading,
    // 方法
    sendMessage,
    resume,
    rejectInterrupt,
    clearHistory,
    fetchThreads,
    switchThread,
    newConversation,
    deleteThread,
  }
}
