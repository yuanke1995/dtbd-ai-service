<template>
  <div class="chat-layout">
    <!-- 左侧会话列表 -->
    <SessionSidebar
      :sessions="sessions"
      :current-session-id="currentSessionId"
      :collapsed="sidebarCollapsed"
      :loading="sessionsLoading"
      @select="switchSession"
      @delete="handleDeleteSession"
      @new="createNewSession"
      @toggle-collapse="toggleSidebar"
    />

    <!-- 右侧聊天区域 -->
    <div class="chat">
      <div class="chat-box">
        <div class="head">
          <span><robot-outlined style="color:#1677ff;margin-right:8px" />AI 助手 - 小报</span>
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

        <!-- 图片点击放大灯箱：滚轮缩放、拖动平移、双击重置、ESC 关闭 -->
        <div v-if="previewUrl" class="lightbox" @click="close" @wheel.prevent="onWheel">
          <img
            :src="previewUrl" alt="大图预览" @click.stop
            :class="{ dragging: !!dragState }"
            :style="{ transform: 'translate(' + offset.x + 'px,' + offset.y + 'px) scale(' + zoom + ')' }"
            @mousedown="onImgMouseDown" @mousemove="onImgMouseMove" @mouseup="onImgMouseUp" @mouseleave="onImgMouseUp"
            @dblclick="onImgDblClick"
          />
          <span class="lightbox-close" @click.stop="close">×</span>
          <span v-if="zoom !== 1" class="lightbox-hint">{{ Math.round(zoom * 100) }}%</span>
          <span class="lightbox-tip">滚轮缩放 · 拖动平移 · 双击重置 · ESC 关闭</span>
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
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { message } from 'ant-design-vue'
import { RobotOutlined, SendOutlined, PauseCircleOutlined } from '@ant-design/icons-vue'
import { sendQuestion, newSession, getHistory, listSessions, deleteSessionApi } from '../api'
import SessionSidebar from '../components/SessionSidebar.vue'

const text = ref('')
const loading = ref(false)
const sessionsLoading = ref(false)
const currentSessionId = ref(null)
const sessions = ref([])
const messages = ref([])
const box = ref(null)
const previewUrl = ref('')
const zoom = ref(1)
const offset = ref({ x: 0, y: 0 })
const dragState = ref(null)
const abortController = ref(null)
const sidebarCollapsed = ref(false)
const tips = ['系统有哪些功能？', '如何创建一个新表单？', '字段验证怎么设置？', '什么是填报周期？']

// 文档图片访问
const resolveImg = u => u.startsWith('http') ? u : '/proxy' + u.replace(/^\/ai/, '')

// 点击回答中的图片 → 灯箱预览
const openPreview = e => {
  const t = e.target
  if (t && t.tagName && t.tagName.toLowerCase() === 'img') {
    previewUrl.value = t.getAttribute('src')
    zoom.value = 1
    offset.value = { x: 0, y: 0 }
  }
}

// 灯箱：关闭（重置缩放与平移）
const close = () => {
  previewUrl.value = ''
  zoom.value = 1
  offset.value = { x: 0, y: 0 }
  dragState.value = null
}

// 灯箱：滚轮缩放（25% ~ 800%）
const onWheel = e => {
  zoom.value = Math.min(8, Math.max(0.25, zoom.value + (e.deltaY < 0 ? 0.15 : -0.15)))
}

// 灯箱：拖动平移（放大后查看超出窗口的边缘）
const onImgMouseDown = e => {
  if (e.button !== 0) return
  dragState.value = { startX: e.clientX, startY: e.clientY, ox: offset.value.x, oy: offset.value.y }
  e.preventDefault()
}
const onImgMouseMove = e => {
  if (!dragState.value) return
  offset.value.x = dragState.value.ox + (e.clientX - dragState.value.startX)
  offset.value.y = dragState.value.oy + (e.clientY - dragState.value.startY)
}
const onImgMouseUp = () => { dragState.value = null }

// 双击重置视图
const onImgDblClick = () => {
  zoom.value = 1
  offset.value = { x: 0, y: 0 }
}

// ESC 关闭灯箱
const onKeydown = e => {
  if (e.key === 'Escape') close()
}
watch(previewUrl, v => {
  if (v) window.addEventListener('keydown', onKeydown)
  else window.removeEventListener('keydown', onKeydown)
})
onUnmounted(() => window.removeEventListener('keydown', onKeydown))

// 窄窗口（<640px）自动折叠侧边栏：保证浏览器窗口可以任意缩小，不出现横向溢出
const handleWindowResize = () => {
  if (window.innerWidth < 640 && !sidebarCollapsed.value) {
    sidebarCollapsed.value = true
    localStorage.setItem('ai_sidebar_collapsed', 'true')
  }
}
window.addEventListener('resize', handleWindowResize)
onUnmounted(() => window.removeEventListener('resize', handleWindowResize))

// 初始化：恢复侧边栏状态 → 加载会话列表 → 选择最近会话或新建
onMounted(async () => {
  const saved = localStorage.getItem('ai_sidebar_collapsed')
  if (saved === 'true') sidebarCollapsed.value = true

  await loadSessions()
  if (sessions.value.length > 0) {
    await switchSession(sessions.value[0].id)
  } else {
    await createNewSession()
  }
})

// 加载会话列表
async function loadSessions() {
  sessionsLoading.value = true
  try {
    const r = await listSessions()
    if (r.success && Array.isArray(r.data)) {
      sessions.value = r.data
    }
  } catch (e) {
    message.error('加载会话列表失败: ' + (e.message || '未知错误'))
  } finally {
    sessionsLoading.value = false
  }
}

// 切换会话
async function switchSession(sid) {
  if (loading.value) return
  currentSessionId.value = sid
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
    } else {
      messages.value = []
    }
  } catch (e) {
    messages.value = []
  }
}

// 新建会话
async function createNewSession() {
  try {
    const r = await newSession()
    if (r.success && r.data?.sessionId) {
      currentSessionId.value = r.data.sessionId
      messages.value = []
    }
  } catch (e) {
    message.error('创建会话失败: ' + (e.message || '未知错误'))
  }
}

// 删除会话
async function handleDeleteSession(sid) {
  try {
    await deleteSessionApi(sid)
    message.success('会话已删除')
    if (sid === currentSessionId.value) {
      // 删除的是当前会话，切换到下一个或新建
      const remaining = sessions.value.filter(s => s.id !== sid)
      if (remaining.length > 0) {
        await switchSession(remaining[0].id)
      } else {
        await createNewSession()
      }
    }
    await loadSessions()
  } catch (e) {
    message.error('删除失败: ' + (e.message || '未知错误'))
  }
}

// 切换侧边栏折叠
function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value
  localStorage.setItem('ai_sidebar_collapsed', String(sidebarCollapsed.value))
}

const send = () => {
  const q = text.value.trim()
  if (!q || loading.value) return
  const isFirstMessage = messages.value.length === 0
  text.value = ''
  messages.value.push({ role: 'user', content: q })
  const idx = messages.value.length
  messages.value.push({ role: 'ai', content: '', images: [], loading: true })
  loading.value = true
  abortController.value = new AbortController()
  let full = ''
  sendQuestion(currentSessionId.value, q, {
    signal: abortController.value.signal,
    onToken: t => { full += t; messages.value[idx].content = full; scroll() },
    onImage: imgs => {
      console.log('[Chat] onImage 收到:', imgs)
      try {
        const parsed = JSON.parse(imgs)
        console.log('[Chat] onImage 解析后:', parsed, '类型:', typeof parsed, 'Array.isArray:', Array.isArray(parsed))
        messages.value[idx].images = Array.isArray(parsed) ? parsed : []
      } catch (e) {
        console.warn('[Chat] onImage JSON.parse 失败:', e)
        messages.value[idx].images = []
      }
    },
    onDone: () => {
      if (messages.value[idx].content === '') messages.value[idx].content = '（已停止生成）'
      messages.value[idx].loading = false
      loading.value = false
      abortController.value = null
      scroll()
      // 首条消息后刷新会话列表（标题已由后端生成）
      if (isFirstMessage) loadSessions()
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

// ==================== 安全渲染（零依赖） ====================
const escapeHtml = s => s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')

const render = (t, images = []) => {
  if (!t) return ''
  let html = escapeHtml(t)
  // 图片标记：兼容 [图片N] 与 [图片N：描述] 两种格式；吸收标记后紧跟的标点/空白，
  // 避免标点落在图片下一行；标记前的标点保留在文字末尾（如"如图：[图片1]"的冒号保留）
  html = html.replace(/\[图片\s*(\d+)(?:[：:][^\]]*)?\][，。、；：！？\s]*/g, (m, n) => {
    const u = images[Number(n) - 1]
    if (!u) {
      console.warn('[图片] 渲染失败:', m, '索引', n, 'images长度', images.length, images)
      return m
    }
    const src = resolveImg(u).replace(/"/g, '&quot;')
    return `<div style="text-align:center"><img class="md-img" src="${src}" alt="文档图片"/></div>`
  })
  // Markdown 子集
  html = html.replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>')
  html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
  html = html.replace(/`([^`]+)`/g, '<code style="background:#f0f0f0;padding:1px 4px;border-radius:3px">$1</code>')
  html = html.replace(/^### (.+)$/gm, '<h4>$1</h4>')
    .replace(/^## (.+)$/gm, '<h3>$1</h3>')
    .replace(/^# (.+)$/gm, '<h2>$1</h2>')
  html = html.replace(/^[-*] (.+)$/gm, '<li>$1</li>')
  html = html.replace(/(<li>[\s\S]*?<\/li>)(?!\s*<li>)/g, '<ul>$1</ul>')
  html = html.replace(/\n/g, '<br/>')
  return html
}

const scroll = () => nextTick(() => { if (box.value) box.value.scrollTop = box.value.scrollHeight })
</script>

<style scoped>
.chat-layout {
  display: flex;
  height: calc(100vh - 112px);
  background: #fff;
  border-radius: 8px;
  overflow: hidden;
}
.chat {
  flex: 1;
  min-width: 0;
  display: flex;
  justify-content: center;
}
.chat-box {
  width: 100%;
  max-width: 900px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 24px;
  border-bottom: 1px solid #f0f0f0;
  font-weight: 600;
}
.messages {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
}
.welcome {
  text-align: center;
  padding: 60px 20px;
}
.welcome h2 { margin: 16px 0 8px; }
.welcome p { color: #888; margin-bottom: 24px; }
.row { display: flex; gap: 12px; margin-bottom: 20px; }
.row.user { flex-direction: row-reverse; }
.bubble { max-width: 70%; padding: 12px 16px; border-radius: 12px; line-height: 1.6; }
.bubble.user { background: #1677ff; color: #fff; }
.bubble.ai { background: #f5f5f5; color: #333; }
.input { padding: 16px 24px; border-top: 1px solid #f0f0f0; }
.md :deep(img) { max-width: 100%; max-height: 320px; border: 1px solid #e0e0e0; border-radius: 6px; display: block; margin: 8px 0; cursor: zoom-in; }

/* 图片灯箱 */
.lightbox { position: fixed; inset: 0; background: rgba(0,0,0,.78); display: flex;
  align-items: center; justify-content: center; z-index: 1000; cursor: zoom-out; overflow: hidden; }
.lightbox img { max-width: 90vw; max-height: 90vh; border-radius: 4px;
  box-shadow: 0 4px 24px rgba(0,0,0,.4); cursor: grab; user-select: none;
  transition: transform .12s ease; will-change: transform; }
.lightbox img.dragging { cursor: grabbing; transition: none; }
.lightbox-close { position: fixed; top: 16px; right: 24px; font-size: 36px; color: #fff;
  cursor: pointer; line-height: 1; opacity: .85; user-select: none; }
.lightbox-close:hover { opacity: 1; }
.lightbox-hint { position: fixed; top: 20px; left: 50%; transform: translateX(-50%);
  color: #fff; font-size: 14px; background: rgba(0,0,0,.5); padding: 2px 10px; border-radius: 12px; user-select: none; }
.lightbox-tip { position: fixed; bottom: 20px; left: 50%; transform: translateX(-50%);
  color: rgba(255,255,255,.6); font-size: 12px; user-select: none; }
</style>
