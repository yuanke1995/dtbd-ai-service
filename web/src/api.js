/**
 * AI 服务 API 层（零依赖：原生 fetch + XHR）
 * - 常规请求：fetch 封装（超时/401/业务失败统一抛出可读错误）
 * - 上传：XMLHttpRequest（支持进度回调）
 * - SSE：fetch + AbortController（支持停止生成）
 * - 环境配置：VITE_API_BASE 接口前缀、VITE_TRUSTED_TOKEN 内部 token（生产由平台网关注入）
 */
const BASE = import.meta.env.VITE_API_BASE || '/proxy/api/ai'
const TOKEN = import.meta.env.VITE_TRUSTED_TOKEN || ''

const authHeaders = extra => {
  const h = { 'Content-Type': 'application/json', ...(extra || {}) }
  if (TOKEN) h['X-Trusted-Token'] = TOKEN
  return h
}

/**
 * 通用 JSON 请求：超时控制 + 401/业务失败统一抛出 Error(message)
 */
async function request(path, { method = 'GET', body, timeout = 30000 } = {}) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeout)
  try {
    const res = await fetch(BASE + path, {
      method,
      headers: authHeaders(),
      body,
      signal: controller.signal
    })
    let data = null
    try { data = await res.json() } catch (e) { /* 非 JSON 响应 */ }
    if (!res.ok) {
      if (res.status === 401) throw new Error('未授权访问')
      throw new Error(data?.msg || `请求失败(${res.status})`)
    }
    if (data && data.success === false) throw new Error(data.msg || '请求失败')
    return data
  } catch (e) {
    if (e.name === 'AbortError') throw new Error('请求超时，请稍后重试')
    throw e
  } finally {
    clearTimeout(timer)
  }
}

/**
 * XHR 上传（multipart，支持进度百分比回调）
 */
function upload(path, formData, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', BASE + path)
    if (TOKEN) xhr.setRequestHeader('X-Trusted-Token', TOKEN)
    xhr.timeout = 120000
    xhr.upload.onprogress = e => {
      if (e.lengthComputable && onProgress) onProgress(Math.round((e.loaded / e.total) * 100))
    }
    xhr.onload = () => {
      let data = null
      try { data = JSON.parse(xhr.responseText) } catch (e) { /* ignore */ }
      if (xhr.status >= 200 && xhr.status < 300) {
        if (data && data.success === false) reject(new Error(data.msg || '上传失败'))
        else resolve(data)
      } else {
        reject(new Error(data?.msg || `上传失败(${xhr.status})`))
      }
    }
    xhr.onerror = () => reject(new Error('网络错误'))
    xhr.ontimeout = () => reject(new Error('上传超时'))
    xhr.send(formData)
  })
}

/**
 * 流式聊天（SSE）
 * signal 用于停止生成（AbortController.abort()）
 */
export function sendQuestion(sessionId, question, { onToken, onImage, onDone, onError, signal }) {
  fetch(`${BASE}/chat`, {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify({ sessionId, question }),
    signal
  }).then(res => {
    if (!res.ok) {
      res.json().then(d => onError(d?.msg || '请求失败: ' + res.status)).catch(() => onError('请求失败: ' + res.status))
      return
    }
    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    const read = () => {
      reader.read().then(({ done, value }) => {
        if (done) { onDone(); return }
        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''
        for (const line of lines) {
          // Spring SseEmitter 输出 "data:{...}"（冒号后无空格），需兼容带/不带空格两种
          if (line.startsWith('data:')) {
            try {
              const d = JSON.parse(line.substring(5).trim())
              if (d.type === 'token') { onToken(d.content) }
              else if (d.type === 'image') { console.log('[SSE] 收到 image 事件:', d.content); onImage(d.content) }
              else if (d.type === 'done') { onDone(); return }
              else if (d.type === 'error') { onError(d.content); return }
              else { console.log('[SSE] 未知事件类型:', d.type, d) }
            } catch (e) { console.warn('[SSE] JSON 解析失败:', line, e) }
          } else if (line.startsWith('event:')) {
            console.log('[SSE] 收到 named event:', line)
          }
        }
        read()
      }).catch(e => {
        if (e.name === 'AbortError') onDone()  // 用户主动停止，按正常结束处理
        else onError('读取失败: ' + e.message)
      })
    }
    read()
  }).catch(e => {
    if (e.name === 'AbortError') onDone()
    else onError('请求失败: ' + e.message)
  })
}

/** 新建会话 */
export const newSession = () => request('/session/new', { method: 'POST' })

/** 获取会话历史 */
export const getHistory = sid => request(`/session/${sid}`)

/** 清除会话（Redis 缓存） */
export const clearSession = sid => request(`/session/${sid}`, { method: 'DELETE' })

/** 列出所有会话 */
export const listSessions = () => request('/sessions')

/** 删除会话（MySQL 软删除 + Redis 清理） */
export const deleteSessionApi = sid => request(`/session/${sid}`, { method: 'DELETE' })

/** 文档列表 */
export const listDocuments = () => request('/document/list')

/** 上传文档（onProgress 接收 0-100 百分比） */
export function uploadDocument(file, description, onProgress) {
  const fd = new FormData()
  fd.append('file', file)
  if (description) fd.append('description', description)
  return upload('/document/upload', fd, onProgress)
}

/** 删除文档 */
export const deleteDocument = id => request(`/document/${id}`, { method: 'DELETE' })
