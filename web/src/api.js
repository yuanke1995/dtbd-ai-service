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
      if (res.status === 401) window.dispatchEvent(new CustomEvent('app:unauthorized'))
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
        if (xhr.status === 401) window.dispatchEvent(new CustomEvent('app:unauthorized'))
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
 * deepThink=true 时后端先流式输出思考过程（thinking / thinking_done 事件）
 */
export function sendQuestion(sessionId, question, images = [], { onToken, onImage, onDone, onError, onThinking, onThinkingDone, onWarn, deepThink = false, signal }) {
  fetch(`${BASE}/chat`, {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify({ sessionId, question, images, deepThink }),
    signal
  }).then(res => {
    if (!res.ok) {
      if (res.status === 401) window.dispatchEvent(new CustomEvent('app:unauthorized'))
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
              else if (d.type === 'thinking') { onThinking && onThinking(d.content) }
              else if (d.type === 'thinking_done') { onThinkingDone && onThinkingDone(d.content) }
              else if (d.type === 'image') { console.log('[SSE] 收到 image 事件:', d.content); onImage(d.content) }
              else if (d.type === 'warn') { console.warn('[SSE] 收到 warn 事件:', d.content); onWarn && onWarn(d.content) }
              else if (d.type === 'done') { onDone(d.content); return } // content 为 {sources,related,degradations} JSON 字符串
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

/** 列出所有会话（支持 keyword 按标题/消息内容模糊搜索） */
export const listSessions = (keyword = '') =>
  request('/sessions' + (keyword ? '?keyword=' + encodeURIComponent(keyword) : ''))

/** 置顶/取消置顶会话 */
export const pinSession = (sid, pinned) =>
  request(`/session/${sid}/pin`, { method: 'PUT', body: JSON.stringify({ pinned }) })

/** 收藏/取消收藏会话 */
export const favoriteSession = (sid, favorite) =>
  request(`/session/${sid}/favorite`, { method: 'PUT', body: JSON.stringify({ favorite }) })

/** 删除会话（MySQL 软删除 + Redis 清理） */
export const deleteSessionApi = sid => request(`/session/${sid}`, { method: 'DELETE' })

/** 清空所有会话 */
export const clearAllSessionsApi = () => request('/sessions', { method: 'DELETE' })

/** 文档列表 */
export const listDocuments = () => request('/document/list')

/** 上传文档（onProgress 接收 0-100 百分比） */
export function uploadDocument(file, description, onProgress) {
  const fd = new FormData()
  fd.append('file', file)
  if (description) fd.append('description', description)
  return upload('/document/upload', fd, onProgress)
}

/** 批量上传（onProgress 接收 0-100 百分比） */
export function uploadDocumentsBatch(files, onProgress) {
  const fd = new FormData()
  files.forEach(f => fd.append('file', f))
  return upload('/document/upload/batch', fd, onProgress)
}

/** 文档启停用：status 0 生效 / 1 弃用 */
export const updateDocumentStatus = (id, status) =>
  request(`/document/${id}/status`, { method: 'PUT', body: JSON.stringify({ status }) })

/** 文档重解析 */
export const reparseDocument = id =>
  request(`/document/${id}/reparse`, { method: 'POST' })

/** 删除文档 */
export const deleteDocument = id => request(`/document/${id}`, { method: 'DELETE' })

/** 提交回答反馈（messageId 关联；rating 1=有帮助 0=没帮助） */
export const submitFeedback = (messageId, rating, feedbackText) =>
  request('/feedback', {
    method: 'POST',
    body: JSON.stringify({ messageId, rating, feedbackText: feedbackText || null })
  })

/** 看板统计 */
export const getAnalytics = () => request('/analytics/summary')

/** 知识块详情（引用溯源全文） */
export const getKnowledgeDetail = id => request(`/knowledge/${id}`)

/** 批量删除文档 */
export const batchDeleteDocuments = ids =>
  request('/document/batch/delete', { method: 'POST', body: JSON.stringify({ ids }) })

/** 批量启停用文档 */
export const batchUpdateDocumentStatus = (ids, status) =>
  request('/document/batch/status', { method: 'POST', body: JSON.stringify({ ids, status }) })

/** 文档命中次数统计（{docId: count}） */
export const getDocumentStats = () => request('/document/stats')

/** 模型配置：获取全量（分组 + editable 标记） */
export const getConfig = () => request('/config')

/** 前端运行时配置（文档上传上限/支持格式等，与后端一致） */
export const getRuntimeConfig = () => request('/config/public')

/** 探测重排服务是否可用（设置页开启前校验） */
export const checkRerank = () => request('/config/rerank/check')

/** 探测 Meilisearch 是否可用（设置页切换关键词引擎前校验） */
export const checkKeywordEngine = () => request('/config/keyword/check')

/** 关键词索引运维：引擎状态/索引统计 */
export const getSearchIndexStats = () => request('/search-index/stats')

/** 关键词索引运维：全量重建（后台执行） */
export const reindexSearchIndex = () => request('/search-index/reindex', { method: 'POST' })

/** 模型配置：保存可编辑项 {"chat":{...},"vision":{...}} */
export const saveConfig = payload => request('/config', { method: 'PUT', body: JSON.stringify(payload) })

/** 按文档列出知识块（知识块预览） */
export const listKnowledgeByDoc = docId => request('/knowledge/list?docId=' + encodeURIComponent(docId))

/** 获取无命中问题列表（按频次降序） */
export const getUnmatchedQuestions = () => request('/knowledge/unmatched')

/** 新增知识块（手动补充知识库缺口） */
export const createKnowledge = (title, content, docId) =>
  request('/knowledge', {
    method: 'POST',
    body: JSON.stringify({ title, content, docId: docId || null })
  })

/** 编辑知识块（重新向量化） */
export const updateKnowledge = (id, title, content) =>
  request(`/knowledge/${id}`, {
    method: 'PUT',
    body: JSON.stringify({ title, content })
  })

/** 删除知识块 */
export const deleteKnowledge = id => request(`/knowledge/${id}`, { method: 'DELETE' })

/** 文档版本列表 */
export const listDocumentVersions = id => request(`/document/${id}/versions`)

/** 回滚文档到指定版本 */
export const rollbackDocument = (id, version) =>
  request(`/document/${id}/rollback`, { method: 'POST', body: JSON.stringify({ version }) })

/** 检索调试：分步查看关键词/向量/合并/重排/最终上下文/被排除 */
export const debugRetrieval = question =>
  request('/debug/retrieval', { method: 'POST', body: JSON.stringify({ question }) })

/** 检索评估：从历史问答回放生成评估集 */
export const evalGenerate = maxCases =>
  request('/eval/generate', { method: 'POST', body: JSON.stringify({ maxCases }) })

/** 检索评估：读取当前评估集 */
export const getEvalSet = () => request('/eval/set')

/** 检索评估：批量参数组对比运行 */
export const runEvaluation = payload =>
  request('/eval/run', { method: 'POST', body: JSON.stringify(payload) })
