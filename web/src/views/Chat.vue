<template>
  <div class="chat">
    <div class="chat-box">
      <div class="head">
        <span><robot-outlined style="color:#1677ff;margin-right:8px" />AI 助手 - 小报</span>
        <a-button size="small" @click="clear" :disabled="loading">
          <delete-outlined /> 清除会话
        </a-button>
      </div>

      <div class="messages" ref="box">
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
            <div v-html="render(m.content)"></div>
            <a-spin v-if="m.loading" size="small" style="margin-top:4px" />
          </div>
        </div>
      </div>

      <div class="input">
        <a-input v-model:value="text" placeholder="请输入问题，回车发送" :disabled="loading" @press-enter="send" allow-clear>
          <template #suffix>
            <send-outlined v-if="text.trim() && !loading" style="color:#1677ff;cursor:pointer" @click="send" />
          </template>
        </a-input>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { RobotOutlined, SendOutlined, DeleteOutlined } from '@ant-design/icons-vue'
import { sendQuestion, newSession, clearSession } from '../api'

const text = ref('')
const loading = ref(false)
const sessionId = ref('')
const messages = ref([])
const box = ref(null)
const tips = ['系统有哪些功能？', '如何创建一个新表单？', '字段验证怎么设置？', '什么是填报周期？']

onMounted(async () => {
  const sid = localStorage.getItem('ai_sid')
  if (sid) { sessionId.value = sid; return }
  const res = await newSession()
  if (res.success && res.data?.sessionId) {
    sessionId.value = res.data.sessionId
    localStorage.setItem('ai_sid', sessionId.value)
  }
})

const send = () => {
  const q = text.value.trim()
  if (!q || loading.value) return
  text.value = ''
  messages.value.push({ role: 'user', content: q })
  const idx = messages.value.length
  messages.value.push({ role: 'ai', content: '', loading: true })
  loading.value = true
  let full = ''
  sendQuestion(sessionId.value, q, {
    onToken: t => { full += t; messages.value[idx].content = full; scroll() },
    onDone: () => { messages.value[idx].loading = false; loading.value = false; scroll() },
    onError: e => { messages.value[idx].content = '😅 ' + e; messages.value[idx].loading = false; loading.value = false; scroll() }
  })
}

const ask = q => { text.value = q; nextTick(send) }

const clear = async () => {
  if (loading.value) return
  await clearSession(sessionId.value)
  messages.value = []
  const res = await newSession()
  if (res.success && res.data?.sessionId) {
    sessionId.value = res.data.sessionId
    localStorage.setItem('ai_sid', sessionId.value)
  }
}

const render = t => {
  if (!t) return ''
  return t.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')
    .replace(/\*\*(.+?)\*\*/g,'<strong>$1</strong>')
    .replace(/`(.+?)`/g,'<code style="background:#f5f5f5;padding:1px 4px;border-radius:3px">$1</code>')
    .replace(/\n/g,'<br/>')
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
</style>