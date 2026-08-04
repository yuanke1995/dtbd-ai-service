<template>
  <div class="chat">
    <div class="chat-box">
      <div class="head">
        <span><robot-outlined style="color:#1677ff;margin-right:8px" />AI 助手 - 小报</span>
        <a-button size="small" @click="clear" :disabled="loading" danger>
          <delete-outlined /> 清除会话
        </a-button>
      </div>

      <div class="messages" ref="box" @click="openPreview">
        <div v-if="messages.length === 0" class="welcome">
          <robot-outlined style="font-size:48px;color:#1677ff" />
          <h2>你好！我是小报 👋</h2>
          <p>基于操作手册知识库回答你关于系统使用的问题</p>
          <div>
            <a-tag v-for="(q,i) in tips" :key="i" color="blue" style="cursor:pointer;margin:4px" @click="ask(q)">{{ q }}</a-tag>
          </div>
        </div>
        <div v-for="(m,i) in messages" :key="i" class="row" :class="m.role">
          <a-avatar :style="{ background: m.role==='user'?'#87d068':'#1677ff', flexShrink:0 }">{{ m.role==='user'?'我':'AI' }}</a-avatar>
          <div class="bubble" :class="m.role">
            <div class="md" v-html="render(m.content, m.images)"></div>
            <a-spin v-if="m.loading" size="small" style="margin-top:4px" />
          </div>
        </div>
      </div>

      <!-- 图片点击放大灯箱：点击遮罩或 × 关闭 -->
      <div v-if="previewUrl" class="lightbox" @click="previewUrl = ''">
        <img :src="previewUrl" alt="大图预览" @click.stop />
        <span class="lightbox-close" @click="previewUrl = ''">×</span>
      </div>

      <div class="input">
        <a-input
          v-model:value="text" placeholder="请输入问题，回车发送" :disabled="loading"
          @press-enter="send" allow-clear
        >
          <template #suffix>
            <a-button v-if="loading" type="text" size="small" @click="stop" style="color:#ff4d4f">
              <pause-circle-outlined /> 停止
            </a-button>
            <send-outlined v-else-if="text.trim()" style="color:#1677ff;cursor:pointer" @click="send" />
          </template>
        </a-input>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { message } from 'ant-design-vue'
import { RobotOutlined, SendOutlined, DeleteOutlined, PauseCircleOutlined } from '@ant-design/icons-vue'
import { sendQuestion, newSession, getHistory, clearSession } from '../api'

const text = ref('')
const loading = ref(false)
const sessionId = ref('')
const messages = ref([])
const box = ref(null)
const previewUrl = ref('')
const abortController = ref(null)
const tips = ['系统有哪些功能？', '如何创建一个新表单？', '字段验证怎么设置？', '什么是填报周期？']

// 文档图片访问：后端返回 /ai/images/...（已含 context-path /ai），而 vite /proxy 的 target 也含 /ai，
// 直接拼 /proxy 会变成 /proxy/ai/images → 双重 /ai → 404；需去掉 /ai 前缀再拼 /proxy
const resolveImg = u => u.startsWith('http') ? u : '/proxy' + u.replace(/^\/ai/, '')

// 点击回答中的图片 → 灯箱预览大图（事件委托：消息区域内任意 img 均可放大）
const openPreview = e => {
  const t = e.target
  if (t && t.tagName && t.tagName.toLowerCase() === 'img') {
    previewUrl.value = t.getAttribute('src')
  }
}

onMounted(async () => {
  const sid = localStorage.getItem('ai_sid')
  if (!sid) {
    await newSessionId()
    return
  }
  sessionId.value = sid
  // 恢复会话历史（含图片）
  try {
    const r = await getHistory(sid)
    if (r.success && Array.isArray(r.data)) {
      messages.value = r.data
        .filter(m => m && m.content)
        .map(m => ({
          role: m.role === 'user' ? 'user' : 'ai',
          content: String(m.content || ''),
          images: Array.isArray(m.images) ? m.images : []
        }))
      scroll()
    }
  } catch (e) { /* 历史恢复失败不阻塞 */ }
})

async function newSessionId() {
  try {
    const r = await newSession()
    if (r.success && r.data?.sessionId) {
      sessionId.value = r.data.sessionId
      localStorage.setItem('ai_sid', sessionId.value)
    }
  } catch (e) { message.error(e.message || '初始化会话失败') }
}

const send = () => {
  const q = text.value.trim()
  if (!q || loading.value) return
  text.value = ''
  messages.value.push({ role: 'user', content: q })
  const idx = messages.value.length
  messages.value.push({ role: 'ai', content: '', images: [], loading: true })
  loading.value = true
  abortController.value = new AbortController()
  let full = ''
  sendQuestion(sessionId.value, q, {
    signal: abortController.value.signal,
    onToken: t => { full += t; messages.value[idx].content = full; scroll() },
    onImage: imgs => {
      try { messages.value[idx].images = JSON.parse(imgs) } catch (e) { messages.value[idx].images = [] }
    },
    onDone: () => {
      if (messages.value[idx].content === '') messages.value[idx].content = '（已停止生成）'
      messages.value[idx].loading = false
      loading.value = false
      abortController.value = null
      scroll()
    },
    onError: e => {
      messages.value[idx].content = '😅 ' + e
      messages.value[idx].loading = false
      loading.value = false
      abortController.value = null
      message.error(e)
      scroll()
    }
  })
}

const stop = () => {
  if (abortController.value) {
    abortController.value.abort()
    abortController.value = null
  }
}

const ask = q => { text.value = q; nextTick(send) }

const clear = async () => {
  if (loading.value) return
  if (sessionId.value) {
    try { await clearSession(sessionId.value) } catch (e) { /* 忽略 */ }
  }
  messages.value = []
  await newSessionId()
}

/**
 * 安全渲染（零依赖）：
 * 1. [图片N] → 文档原图（img src 做属性转义，防注入）
 * 2. LLM 文本先 HTML 转义，再套 Markdown 子集（加粗/行内代码/标题/列表/链接）
 */
const escapeHtml = s => s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')

const render = (t, images = []) => {
  if (!t) return ''
  let html = escapeHtml(t)
  // 图片标记（转义后插入 img，src 属性转义）
  html = html.replace(/\[图片\s*(\d+)\]/g, (m, n) => {
    const u = images[Number(n) - 1]
    if (!u) return m
    const src = resolveImg(u).replace(/"/g, '&quot;')
    return `<div><img class="md-img" src="${src}" alt="文档图片"/></div>`
  })
  // Markdown 子集
  html = html.replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>')
  html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
  html = html.replace(/`([^`]+)`/g, '<code style="background:#f0f0f0;padding:1px 4px;border-radius:3px">$1</code>')
  html = html.replace(/^### (.+)$/gm, '<h4>$1</h4>')
    .replace(/^## (.+)$/gm, '<h3>$1</h3>')
    .replace(/^# (.+)$/gm, '<h2>$1</h2>')
  html = html.replace(/^[-*] (.+)$/gm, '<li>$1</li>')
  // 相邻 li 包成 ul
  html = html.replace(/(<li>[\s\S]*?<\/li>)(?!\s*<li>)/g, '<ul>$1</ul>')
  html = html.replace(/\n/g, '<br/>')
  return html
}

const scroll = () => nextTick(() => { if (box.value) box.value.scrollTop = box.value.scrollHeight })
</script>

<style scoped>
.chat { display:flex;justify-content:center }
.chat-box { width:100%;max-width:900px;height:calc(100vh - 112px);background:#fff;border-radius:8px;display:flex;flex-direction:column;overflow:hidden }
.head { display:flex;justify-content:space-between;align-items:center;padding:16px 24px;border-bottom:1px solid #f0f0f0;font-weight:600 }
.messages { flex:1;overflow-y:auto;padding:24px }
.welcome { text-align:center;padding:60px 20px }
.welcome h2 { margin:16px 0 8px }
.welcome p { color:#888;margin-bottom:24px }
.row { display:flex;gap:12px;margin-bottom:20px }
.row.user { flex-direction:row-reverse }
.bubble { max-width:70%;padding:12px 16px;border-radius:12px;line-height:1.6 }
.bubble.user { background:#1677ff;color:#fff }
.bubble.ai { background:#f5f5f5;color:#333 }
.input { padding:16px 24px;border-top:1px solid #f0f0f0 }
/* Markdown 内容图片：可点击放大 */
.md :deep(img) { max-width:100%;max-height:320px;border:1px solid #e0e0e0;border-radius:6px;display:block;margin:8px 0;cursor:zoom-in }

/* 图片点击放大灯箱 */
.lightbox { position:fixed; inset:0; background:rgba(0,0,0,.78); display:flex;
  align-items:center; justify-content:center; z-index:1000; cursor:zoom-out }
.lightbox img { max-width:90vw; max-height:90vh; border-radius:4px;
  box-shadow:0 4px 24px rgba(0,0,0,.4); cursor:default }
.lightbox-close { position:fixed; top:16px; right:24px; font-size:36px; color:#fff;
  cursor:pointer; line-height:1; opacity:.85; user-select:none }
.lightbox-close:hover { opacity:1 }
</style>
