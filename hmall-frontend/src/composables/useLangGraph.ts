/**
 * LangGraph Vue Composable
 *
 * 封装 @langchain/langgraph-sdk 1.x Client，管理对话状态，
 * 提供 sendMessage / resume / clearHistory 方法。
 *
 * 多租户：请求携带 Authorization；服务端 Auth 按 metadata.owner 隔离 threads。
 * owner 格式：`${agentType}:${userId}`（与 hmall-agent/src/security/auth.py 一致）。
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
  user_id?: string
  enable_rag?: boolean
}

export interface UseLangGraphOptions {
  /** LangGraph Server URL，默认取 VITE_AGENT_URL 环境变量 */
  apiUrl?: string
  /** Agent ID：customer_agent 或 admin_agent */
  assistantId: 'customer_agent' | 'admin_agent'
  /** sessionStorage 中的 token key（portal: token / admin: admin-token） */
  tokenKey?: string
  /** 与 token 对应的 agent 类型，用于解析 JWT 与 owner */
  agentType?: 'customer' | 'admin'
}

/** 解码 JWT payload（不校验签名） */
function decodeJwtPayload(token: string): Record<string, unknown> | null {
  try {
    const parts = token.split('.')
    if (parts.length !== 3) return null
    const b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const padded = b64 + '='.repeat((4 - (b64.length % 4)) % 4)
    return JSON.parse(atob(padded))
  } catch {
    return null
  }
}

/** 从 JWT 解析多租户身份（与后端 jwt_payload.py 对齐） */
function resolveIdentity(
  token: string,
  agentType: 'customer' | 'admin',
): { userId: string; owner: string } | null {
  const payload = decodeJwtPayload(token)
  if (!payload) return null

  let userId = ''
  if (agentType === 'admin') {
    userId = String(payload.sub ?? '')
  } else {
    userId = String(payload.user ?? payload.user_id ?? '')
  }
  if (!userId || userId === 'undefined' || userId === 'null') return null
  return { userId, owner: `${agentType}:${userId}` }
}

export function useLangGraph(options: UseLangGraphOptions) {
  const apiUrl = options.apiUrl || import.meta.env.VITE_AGENT_URL || 'http://localhost:8090'
  const assistantId = options.assistantId
  const tokenKey = options.tokenKey || (options.agentType === 'admin' ? 'admin-token' : 'token')
  const agentType: 'customer' | 'admin' =
    options.agentType || (assistantId === 'admin_agent' ? 'admin' : 'customer')

  // ==================== 响应式状态 ====================
  const messages: Ref<ChatMessage[]> = ref([])
  const isLoading = ref(false)
  const interruptData: Ref<InterruptData | null> = ref(null)
  const threadId: Ref<string | null> = ref(null)
  const error: Ref<string | null> = ref(null)
  const threads: Ref<ThreadSummary[]> = ref([])
  const isThreadsLoading = ref(false)
  let _currentContext: AgentContext | null = null
  let _boundOwner: string | null = null

  // ==================== Client / 身份 ====================

  function _readToken(): string {
    return sessionStorage.getItem(tokenKey) || ''
  }

  function _getIdentity(): { userId: string; owner: string; token: string } | null {
    const token = _readToken()
    if (!token) return null
    const id = resolveIdentity(token, agentType)
    if (!id) return null
    return { ...id, token }
  }

  /** 每次请求用当前 token 新建 Client，避免换账号后仍带旧 Authorization */
  function _createClient(token: string): Client {
    const authValue = token.toLowerCase().startsWith('bearer ')
      ? token
      : `Bearer ${token}`
    return new Client({
      apiUrl,
      defaultHeaders: {
        Authorization: authValue,
        'X-Hmall-Agent-Type': agentType,
      },
    })
  }

  /** 用户切换时清空本地会话状态，避免串会话 */
  function _ensureOwnerBound(owner: string) {
    if (_boundOwner && _boundOwner !== owner) {
      threadId.value = null
      messages.value = []
      interruptData.value = null
      _currentContext = null
    }
    _boundOwner = owner
  }

  // ==================== 内部方法 ====================

  function _genId(): string {
    return `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
  }

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

  async function _processStream(streamResponse: AsyncGenerator<any>) {
    let aiMessage: ChatMessage | null = null

    for await (const chunk of streamResponse) {
      if (chunk.event === 'messages/partial' || chunk.event === 'messages/complete') {
        for (const msg of chunk.data || []) {
          if (msg.type !== 'ai') continue

          const content = _extractContent(msg)
          if (!content) continue

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
            const idx = messages.value.findIndex(m => m.id === msgId)
            if (idx !== -1) {
              messages.value[idx].content = content
            }
          }
        }
      }

      if (chunk.event === 'values') {
        const data = chunk.data || {}

        if (data.__interrupt__) {
          const interrupt = Array.isArray(data.__interrupt__)
            ? data.__interrupt__[0]
            : data.__interrupt__

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

    const identity = _getIdentity()
    if (!identity) {
      error.value = '请先登录后再使用智能助手'
      messages.value.push({
        id: _genId(),
        type: 'ai',
        content: '❌ 请先登录后再使用智能助手',
        timestamp: Date.now(),
      })
      return
    }

    _ensureOwnerBound(identity.owner)
    error.value = null
    isLoading.value = true
    const enrichedContext: AgentContext = {
      ...context,
      user_token: identity.token,
      user_id: identity.userId,
      agent_type: agentType,
    }
    _currentContext = enrichedContext

    messages.value.push({
      id: _genId(),
      type: 'human',
      content: text,
      timestamp: Date.now(),
    })

    const client = _createClient(identity.token)

    try {
      if (!threadId.value) {
        const thread = await client.threads.create({
          metadata: {
            owner: identity.owner,
            user_id: identity.userId,
            agent_type: agentType,
          },
        })
        threadId.value = thread.thread_id
      }

      const streamResponse = client.runs.stream(threadId.value, assistantId, {
        input: {
          messages: [{ type: 'human', content: text }],
        },
        config: {
          recursion_limit: 100,
        },
        context: enrichedContext,
        streamMode: ['messages', 'values'],
      })

      await _processStream(streamResponse)
      fetchThreads()
    } catch (e: any) {
      const msg = e?.message || error.value || '发送失败'
      error.value = msg
      messages.value.push({
        id: _genId(),
        type: 'ai',
        content: `❌ 发送失败：${msg}`,
        timestamp: Date.now(),
      })
    } finally {
      isLoading.value = false
    }
  }

  /**
   * 恢复中断（二次确认 / 多轮交互）
   */
  async function resume(value: string) {
    if (!threadId.value || isLoading.value) return

    const identity = _getIdentity()
    if (!identity) {
      error.value = '请先登录后再继续操作'
      return
    }
    _ensureOwnerBound(identity.owner)

    interruptData.value = null
    error.value = null
    isLoading.value = true

    messages.value.push({
      id: _genId(),
      type: 'human',
      content: value,
      timestamp: Date.now(),
    })

    const client = _createClient(identity.token)
    const ctx: AgentContext = {
      ...(_currentContext || { agent_type: agentType, user_token: identity.token }),
      user_token: identity.token,
      user_id: identity.userId,
      agent_type: agentType,
    }
    _currentContext = ctx

    try {
      const streamResponse = client.runs.stream(threadId.value, assistantId, {
        command: { resume: value },
        config: {
          recursion_limit: 100,
        },
        context: ctx,
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

  /** 拒绝中断（取消操作） */
  async function rejectInterrupt() {
    if (!threadId.value) return
    const identity = _getIdentity()
    if (!identity) return

    interruptData.value = null
    isLoading.value = true

    try {
      const client = _createClient(identity.token)
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

  /** 清除对话历史 */
  async function clearHistory() {
    const identity = _getIdentity()
    if (threadId.value && identity) {
      try {
        const client = _createClient(identity.token)
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

  function _getPreview(msgs: ChatMessage[]): string {
    const first = msgs.find(m => m.type === 'human' || m.type === 'ai')
    return first ? first.content.slice(0, 40) : '新对话'
  }

  /** 获取当前用户的会话列表（服务端按 owner 过滤） */
  async function fetchThreads() {
    isThreadsLoading.value = true
    try {
      const identity = _getIdentity()
      if (!identity) {
        threads.value = []
        return
      }
      _ensureOwnerBound(identity.owner)

      const client = _createClient(identity.token)
      const list = await client.threads.search({
        metadata: { owner: identity.owner },
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
      threads.value = []
    } finally {
      isThreadsLoading.value = false
    }
  }

  /** 切换到已有会话 */
  async function switchThread(targetThreadId: string) {
    if (targetThreadId === threadId.value) return
    if (isLoading.value) return

    const identity = _getIdentity()
    if (!identity) {
      error.value = '请先登录'
      return
    }

    threadId.value = targetThreadId
    messages.value = []
    interruptData.value = null
    error.value = null

    try {
      isLoading.value = true
      const client = _createClient(identity.token)
      const state = await client.threads.getState(targetThreadId)
      const msgs = _parseMessagesFromState(state)
      messages.value = msgs

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
      threadId.value = null
      messages.value = []
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
    const identity = _getIdentity()
    if (!identity) return

    try {
      const client = _createClient(identity.token)
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
    messages,
    isLoading,
    interruptData,
    threadId,
    error,
    threads,
    isThreadsLoading,
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
