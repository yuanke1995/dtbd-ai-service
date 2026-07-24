/**
 * AI 服务 API（测试台直连 AI 服务）
 * 前端调用 /proxy/** → vite 代理转发到 http://localhost:8090/ai/**
 * 携带 X-Trusted-Token 通过 AI 服务鉴权
 */

// 与 AI 服务 application.yml 中 ai-app.trusted-token 保持一致
const TRUSTED_TOKEN = 'dtbd-ai-internal-token'

const BASE = '/proxy/api/ai'

const headers = () => ({
  'Content-Type': 'application/json',
  'X-Trusted-Token': TRUSTED_TOKEN,
  'X-User-Id': 'tester',
  'X-User-Name': '测试用户'
})

/**
 * 流式聊天
 */
export function sendQuestion(sessionId, question, { onToken, onDone, onError }) {
  fetch(`${BASE}/chat`, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({ sessionId, question })
  }).then(res => {
    if (!res.ok) { onError('请求失败: ' + res.status); return }
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
          if (line.startsWith('data: ')) {
            try {
              const d = JSON.parse(line.substring(6))
              if (d.type === 'token') onToken(d.content)
              else if (d.type === 'done') { onDone(); return }
              else if (d.type === 'error') { onError(d.content); return }
            } catch (e) { /* skip */ }
          }
        }
        read()
      }).catch(e => onError('读取失败: ' + e.message))
    }
    read()
  }).catch(e => onError('请求失败: ' + e.message))
}

export async function newSession() {
  const r = await fetch(`${BASE}/session/new`, { method: 'POST', headers: headers() })
  return r.json()
}

export async function clearSession(sessionId) {
  const r = await fetch(`${BASE}/session/${sessionId}`, { method: 'DELETE', headers: headers() })
  return r.json()
}

export async function listDocuments() {
  const r = await fetch(`${BASE}/document/list`, { headers: headers() })
  return r.json()
}

export async function uploadDocument(file, description) {
  const fd = new FormData()
  fd.append('file', file)
  if (description) fd.append('description', description)
  const r = await fetch(`${BASE}/document/upload`, {
    method: 'POST',
    headers: { 'X-Trusted-Token': TRUSTED_TOKEN, 'X-User-Id': 'tester' },
    body: fd
  })
  return r.json()
}

export async function deleteDocument(id) {
  const r = await fetch(`${BASE}/document/${id}`, { method: 'DELETE', headers: headers() })
  return r.json()
}