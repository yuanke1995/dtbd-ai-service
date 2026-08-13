<template>
  <div class="chat-layout">
    <!-- 左侧会话列表 -->
    <SessionSidebar
      :sessions="visibleSessions"
      :current-session-id="currentSessionId"
      :collapsed="sidebarCollapsed"
      :loading="sessionsLoading"
      :creating="creatingSession"
      :width="sidebarWidth"
      :dragging="sidebarDragging"
      @select="switchSession"
      @delete="handleDeleteSession"
      @clear="handleClearAll"
      @new="createNewSession"
      @toggle-collapse="toggleSidebar"
      @search="onSearchSessions"
      @filter-change="onFilterChange"
      @toggle-pin="handleTogglePin"
      @toggle-favorite="handleToggleFavorite"
    />
    <!-- 侧边栏拖拽手柄（左右伸缩） -->
    <div class="sidebar-resizer" title="拖动调整宽度" @mousedown="onSidebarDrag" />

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
              <!-- 用户消息编辑（问题回填输入框重发） -->
              <a-tooltip v-if="m.role === 'user'" title="编辑此问题重新发送" placement="top">
                <edit-outlined class="msg-edit-btn" @click="editMessage(i)" />
              </a-tooltip>
              <!-- 用户上传图片（本地 dataUrl 预览 / 历史 URL） -->
              <div v-if="m.role === 'user' && m.images && m.images.length" class="msg-imgs">
                <img v-for="(u, ui) in m.images" :key="ui" :src="resolveImg(u)"
                     class="msg-img" :alt="'上传图片' + (ui + 1)" @click="openPreviewFromMsg(m, ui)" @error="onImgError" />
              </div>
              <div class="md" :data-msg-index="i" v-html="render(m.content, m.images)"></div>
              <a-spin v-if="m.loading" size="small" style="margin-top:4px" />
              <!-- 引用来源（回答中 [N] 角标点击查看原文片段） -->
              <div v-if="m.role === 'ai' && m.sources && m.sources.length" class="src-chips">
                <a-tag
                  v-for="(s, si) in m.sources" :key="si" color="blue"
                  style="cursor:pointer;margin:2px" @click="openSource(m.sources[si])"
                >
                  [{{ s.ref }}] {{ (s.fileName || '未知文档') + (s.title ? ' §' + s.title : '') }}
                </a-tag>
              </div>
              <!-- 相关推荐问题 -->
              <div v-if="m.role === 'ai' && m.related && m.related.length" class="related">
                <span class="related-label">猜你想问：</span>
                <a-tag v-for="(q, qi) in m.related" :key="qi" color="green"
                       style="cursor:pointer;margin:2px" @click="ask(q)">{{ q }}</a-tag>
              </div>
              <!-- 流式中断/失败：重试入口（保留已生成内容，重新生成完整回答） -->
              <div v-if="m.role === 'ai' && m.failed && !m.loading" class="retry-row">
                <a-button size="small" type="primary" ghost :disabled="loading" @click="regenerate(i)">
                  <sync-outlined /> 重试
                </a-button>
              </div>
              <!-- 回答操作：检索调试 + 重新生成 + 复制/导出 + 反馈 -->
              <div v-if="m.role === 'ai' && m.messageId" class="fb-row">
                <a-button size="small" type="text" :disabled="loading"
                          @click="openDebug(i)">
                  <bug-outlined /> 检索调试
                </a-button>
                <a-button size="small" type="text" :disabled="loading"
                          @click="regenerate(i)">
                  <reload-outlined /> 重新生成
                </a-button>
                <a-button size="small" type="text" @click="copyAnswer(i)">
                  <copy-outlined /> 复制
                </a-button>
                <a-button size="small" type="text" @click="exportAnswer(i)">
                  <download-outlined /> 导出
                </a-button>
                <a-button size="small" type="text"
                          :class="{ 'fb-active': m.fb === 1 }" @click="openFeedback(m, 1)">
                  <like-outlined /> 有帮助
                </a-button>
                <a-button size="small" type="text"
                          :class="{ 'fb-active': m.fb === 0 }" @click="openFeedback(m, 0)">
                  <dislike-outlined /> 没帮助
                </a-button>
              </div>
            </div>
          </div>
        </div>

        <!-- 引用来源详情弹窗（灯箱打开时禁用 ESC 关闭：ESC 优先关灯箱） -->
        <a-modal v-model:open="sourceVisible" :title="sourceTitle" :footer="null" width="720"
                 :keyboard="!previewUrl" :mask-closable="!previewUrl">
          <a-spin v-if="sourceLoading" style="display:block;margin:40px auto" />
          <template v-else>
            <!-- 知识块全文（溯源；内嵌图片点击可灯箱放大并左右切换；代码块可复制） -->
            <div class="md src-content" @click="openPreview"
                 v-html="render(prepKnowledgeContent(sourceContent || sourceSnippet, sourceImages), sourceImages)"></div>
          </template>
        </a-modal>

        <!-- 回答反馈弹窗 -->
        <a-modal v-model:open="feedbackVisible" title="反馈" :footer="null" width="440">
          <a-textarea v-model:value="feedbackText" placeholder="可选：告诉我们哪里不满意（如回答不准确、图片不对等）" :rows="3" />
          <a-button type="primary" style="margin-top:12px" :loading="feedbackSubmitting" @click="submitFeedback">
            提交反馈
          </a-button>
        </a-modal>

        <!-- 检索调试弹窗：分步展示检索过程（为什么这么答） -->
        <a-modal v-model:open="debugVisible" title="🔍 检索调试（为什么这么答）" :footer="null" width="780">
          <div style="display:flex;gap:8px;margin-bottom:12px">
            <a-input v-model:value="debugQuestion" placeholder="输入要调试的问题" @pressEnter="runDebug" />
            <a-button type="primary" :loading="debugLoading" @click="runDebug">调试</a-button>
          </div>
          <a-spin :spinning="debugLoading">
            <template v-if="debugResult">
              <a-collapse :bordered="false" :default-active-key="['final']">
                <a-collapse-panel v-for="(s, si) in debugStages" :key="si" :name="si === 4 ? 'final' : String(si)"
                                  :header="s.name">
                  <div v-if="s.items.length" class="dbg-item" v-for="(it, ii) in s.items" :key="ii">
                    <div class="dbg-head">
                      <span class="dbg-title">{{ it.title }}</span>
                      <a-tag v-if="it.docName" size="small">{{ it.docName }}</a-tag>
                      <a-tag v-if="it.titleHit" color="green" size="small">标题命中</a-tag>
                      <a-tag color="blue" size="small">{{ it.tag }}</a-tag>
                    </div>
                    <div class="dbg-snippet">{{ it.snippet }}</div>
                  </div>
                  <a-empty v-else description="无命中" />
                </a-collapse-panel>
              </a-collapse>
            </template>
          </a-spin>
        </a-modal>

        <!-- 图片点击放大灯箱：多图左右切换、滚轮缩放、拖动平移、双击重置、ESC 关闭 -->
        <div v-if="previewUrl" class="lightbox" @click="close" @wheel.prevent="onWheel">
          <img
            :src="previewUrl" alt="大图预览" @click.stop @error="onImgError"
            :class="{ dragging: !!dragState }"
            :style="{ transform: 'translate(' + offset.x + 'px,' + offset.y + 'px) scale(' + zoom + ')' }"
            @mousedown="onImgMouseDown" @mousemove="onImgMouseMove" @mouseup="onImgMouseUp" @mouseleave="onImgMouseUp"
            @dblclick="onImgDblClick"
          />
          <!-- 上一张/下一张（多图时显示，到头禁用） -->
          <button v-if="previewList.length > 1" class="lightbox-prev" :disabled="previewIndex === 0"
                  @click.stop="prev" aria-label="上一张">‹</button>
          <button v-if="previewList.length > 1" class="lightbox-next" :disabled="previewIndex === previewList.length - 1"
                  @click.stop="next" aria-label="下一张">›</button>
          <span v-if="previewList.length > 1" class="lightbox-count">{{ previewIndex + 1 }} / {{ previewList.length }}</span>
          <span class="lightbox-close" @click.stop="close">×</span>
          <span v-if="zoom !== 1" class="lightbox-hint">{{ Math.round(zoom * 100) }}%</span>
          <span class="lightbox-tip">滚轮缩放 · 拖动平移 · 双击重置 · ESC 关闭<span v-if="previewList.length > 1"> · ← → 切换</span></span>
        </div>

        <div class="input">
          <!-- 待发送图片预览（点击可放大查看） -->
          <div v-if="pendingImages.length" class="pending-imgs">
            <div v-for="(p, pi) in pendingImages" :key="pi" class="pending-img">
              <img :src="p.dataUrl" alt="待发送图片" @click="previewPendingImage(pi)" />
              <span class="pending-del" @click.stop="removePendingImage(pi)">×</span>
            </div>
          </div>
          <div class="input-box">
            <a-textarea
              ref="textareaRef"
              v-model:value="text"
              placeholder="请输入问题，Enter 发送，Shift+Enter 换行"
              :disabled="loading"
              :auto-size="{ minRows: 1, maxRows: 6 }"
              class="input-area"
              @keydown.enter.exact.prevent="send"
            />
            <div class="input-suffix">
              <a-tooltip title="上传图片（最多 5 张，随问题一起发送）">
                <picture-outlined style="color:#888;cursor:pointer;margin-right:10px" @click="pickImages" />
              </a-tooltip>
              <a-button v-if="loading" type="text" size="small" @click="stop" style="color:#ff4d4f">
                <pause-circle-outlined />
              </a-button>
              <send-outlined v-else-if="text.trim() || pendingImages.length" style="color:#1677ff;cursor:pointer" @click="send" />
            </div>
          </div>
          <input ref="fileInput" type="file" accept="image/*" multiple style="display:none" @change="onFilesChange" />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/common'
import 'highlight.js/styles/github.css'
import { message } from 'ant-design-vue'
import { RobotOutlined, SendOutlined, PauseCircleOutlined, LikeOutlined, DislikeOutlined, PictureOutlined, ReloadOutlined, EditOutlined, BugOutlined, SyncOutlined, CopyOutlined, DownloadOutlined } from '@ant-design/icons-vue'
import { sendQuestion, newSession, getHistory, listSessions, deleteSessionApi, pinSession, favoriteSession, submitFeedback as apiSubmitFeedback, getKnowledgeDetail, clearAllSessionsApi, debugRetrieval } from '../api'
import SessionSidebar from '../components/SessionSidebar.vue'

const text = ref('')
const textareaRef = ref(null)

// 检索调试状态
const debugVisible = ref(false)
const debugLoading = ref(false)
const debugQuestion = ref('')
const debugResult = ref(null)
const loading = ref(false)
const sessionsLoading = ref(false)
const currentSessionId = ref(null)
const sessions = ref([])
const messages = ref([])
const box = ref(null)
// 灯箱多图状态：previewList（resolveImg 后 URL）+ previewIndex；previewUrl 为 computed
const previewList = ref([])
const previewIndex = ref(0)
const previewUrl = computed(() => previewList.value[previewIndex.value] || '')
const zoom = ref(1)
const offset = ref({ x: 0, y: 0 })
const dragState = ref(null)
const abortController = ref(null)

// 图片加载兜底：加载失败替换为灰底占位图（签名过期/文件缺失等场景避免裂图）
const FALLBACK_IMG = 'data:image/svg+xml;utf8,' + encodeURIComponent(
  '<svg xmlns="http://www.w3.org/2000/svg" width="200" height="120"><rect width="100%" height="100%" fill="#f5f5f5"/><text x="50%" y="50%" fill="#999" font-size="14" text-anchor="middle" dominant-baseline="middle">图片加载失败</text></svg>')
const onImgError = e => { e.target.onerror = null; e.target.src = FALLBACK_IMG }

// 侧边栏宽度驱动：可拖拽伸缩，缩到阈值(<150px)自动切换为图标条（48px）
const SIDEBAR_ICON_W = 48
const SIDEBAR_ICON_THRESHOLD = 150
const SIDEBAR_MAX = 420
const sidebarWidth = ref(parseInt(localStorage.getItem('ai_sidebar_width')) || 260)
const sidebarCollapsed = computed(() => sidebarWidth.value < SIDEBAR_ICON_THRESHOLD)
function toggleSidebar() {
  sidebarWidth.value = sidebarWidth.value < SIDEBAR_ICON_THRESHOLD ? 260 : SIDEBAR_ICON_W
  localStorage.setItem('ai_sidebar_width', String(sidebarWidth.value))
}
// 拖拽右侧边缘调整宽度（拖拽中禁用宽度过渡动画，保证跟手；松开设 transition 平滑折叠）
const sidebarDragging = ref(false)
function onSidebarDrag(e) {
  e.preventDefault()
  sidebarDragging.value = true
  const startX = e.clientX
  const startW = sidebarWidth.value
  const onMove = ev => {
    const w = Math.max(SIDEBAR_ICON_W, Math.min(SIDEBAR_MAX, startW + (ev.clientX - startX)))
    sidebarWidth.value = w
  }
  const onUp = () => {
    window.removeEventListener('mousemove', onMove)
    window.removeEventListener('mouseup', onUp)
    sidebarDragging.value = false
    localStorage.setItem('ai_sidebar_width', String(sidebarWidth.value))
  }
  window.addEventListener('mousemove', onMove)
  window.addEventListener('mouseup', onUp)
}

const tips = ['系统有哪些功能？', '如何创建一个新表单？', '字段验证怎么设置？', '什么是填报周期？']

// 文档图片访问：data URL（用户上传预览）原样返回；http 原样；其余走 /proxy
const resolveImg = u => u.startsWith('data:') ? u : u.startsWith('http') ? u : '/proxy' + u.replace(/^\/ai/, '')

// 复制图标 SVG（antd CopyOutlined / CheckOutlined 路径，render 内联生成无需 vRender 组件挂载）
const COPY_SVG = '<span class="anticon"><svg viewBox="64 64 896 896" width="1em" height="1em" fill="currentColor"><path d="M832 64H296c-4.4 0-8 3.6-8 8v56c0 4.4 3.6 8 8 8h496v688c0 4.4 3.6 8 8 8h56c4.4 0 8-3.6 8-8V96c0-17.7-14.3-32-32-32zM704 192H192c-17.7 0-32 14.3-32 32v530.7c0 8.5 3.4 16.6 9.4 22.6l173.3 173.3c12.9 12.9 30.2 20 48.4 20H704c17.7 0 32-14.3 32-32V224c0-17.7-14.3-32-32-32zM384 824l-128-128h128v128z"/></svg></span>'
const CHECK_SVG = '<span class="anticon"><svg viewBox="64 64 896 896" width="1em" height="1em" fill="currentColor"><path d="M912 190h-69.9c-9.8 0-19.1 4.5-25.1 12.2L404.7 724.5 207 474c-6.1-7.7-15.3-12.2-25.1-12.2H112c-6.7 0-12.7 4.1-15.2 10.3-2.4 6.3-1.1 13.4 3.6 18.3l235.3 258.5c12.5 13.7 32.5 14.9 46.5 2.7l446.5-424.3c6.4-6.1 9-15.1 5.7-23.4-2.9-7.2-9.8-12.1-17.4-12.1z"/></svg></span>'

// 代码块复制（事件委托入口）：clipboard API 优先（localhost 安全上下文），execCommand 兜底
const copyCode = btn => {
  const pre = btn.closest('pre')
  if (!pre) return
  const txt = (pre.querySelector('code')?.textContent ?? pre.innerText).trim()
  if (!txt) return
  const ok = () => {
    btn.classList.add('copied')
    btn.title = '已复制'
    btn.innerHTML = CHECK_SVG
    setTimeout(() => { btn.classList.remove('copied'); btn.title = '复制'; btn.innerHTML = COPY_SVG }, 1600)
  }
  const fallbackCopy = () => {
    try {
      const ta = document.createElement('textarea')
      ta.value = txt
      ta.setAttribute('readonly', '')
      ta.style.position = 'absolute'
      ta.style.left = '-9999px'
      ta.style.top = '0'
      document.body.appendChild(ta)
      ta.focus()
      ta.select()
      ta.setSelectionRange(0, txt.length)
      const flag = document.execCommand('copy')
      ta.remove()
      if (flag) ok()
      else message.error('复制失败，请手动复制')
    } catch (err) { message.error('复制失败，请手动复制') }
  }
  if (navigator.clipboard?.writeText) {
    navigator.clipboard.writeText(txt).then(ok).catch(fallbackCopy)
  } else {
    fallbackCopy()
  }
}

// 点击回答内容：复制按钮 / 引用角标 [N] → 来源详情；图片 → 灯箱预览（事件委托）
const openPreview = e => {
  const t = e.target
  // 复制按钮：target 可能是按钮内 SVG/span，需 closest 向上查找
  const copyBtn = t && t.closest ? t.closest('.code-copy') : null
  if (copyBtn) {
    copyCode(copyBtn)
    return
  }
  if (t && t.classList && t.classList.contains('ref-sup')) {
    const mdEl = t.closest('.md')
    const msgIdx = mdEl ? Number(mdEl.dataset.msgIndex) : -1
    const ref = Number(t.dataset.ref)
    const src = messages.value[msgIdx]?.sources?.[ref - 1]
    if (src) openSource(src)
    return
  }
  if (t && t.tagName && t.tagName.toLowerCase() === 'img') {
    // 图片点击：优先消息内 data-seq 定位；引用弹窗全文内（无 .md 上下文）用当前来源图片作切换列表
    const mdEl = t.closest('.md')
    const msgIdx = mdEl ? Number(mdEl.dataset.msgIndex) : -1
    const seq = Number(t.dataset.seq || 0)
    let imgs = messages.value[msgIdx]?.images
    if ((!Array.isArray(imgs) || !imgs.length) && sourceVisible.value && sourceImages.value.length) {
      imgs = sourceImages.value
    }
    if (Array.isArray(imgs) && imgs.length) {
      previewList.value = imgs.map(resolveImg)
      previewIndex.value = seq > 0 && seq <= imgs.length ? seq - 1 : 0
    } else {
      previewList.value = [t.getAttribute('src')]
      previewIndex.value = 0
    }
    zoom.value = 1
    offset.value = { x: 0, y: 0 }
  }
}

// 引用来源详情弹窗
const sourceVisible = ref(false)
const sourceTitle = ref('')
const sourceSnippet = ref('')
const sourceImages = ref([])
const sourceContent = ref('')      // 知识块全文（异步加载）
const sourceLoading = ref(false)
// 知识块原文：把无编号的 [图片]/[图片：描述] 按 images 顺序编号，供 render() 渲染
const prepKnowledgeContent = (content, images) => {
  if (!content) return ''
  if (!images.length) return content.replace(/\[图片(?:[：:][^\]]*)?\]/g, '')
  let i = 0
  return content.replace(/\[图片(?:[：:][^\]]*)?\]/g, () => {
    i++
    return i <= images.length ? `[图片${i}]` : '[图片]'
  })
}
const openSource = async s => {
  if (!s) return
  sourceTitle.value = (s.fileName || '未知文档') + (s.title ? ' §' + s.title : '')
  sourceSnippet.value = s.snippet || '（无原文片段）'
  sourceImages.value = Array.isArray(s.images) ? s.images : [] // 旧消息 sources 无 images，兼容为空
  sourceContent.value = ''
  sourceLoading.value = true
  sourceVisible.value = true
  try {
    const r = await getKnowledgeDetail(s.knowledgeId)
    if (r.success && r.data) {
      sourceContent.value = r.data.content || ''
      if (Array.isArray(r.data.images)) sourceImages.value = r.data.images
      if (r.data.title) sourceTitle.value = (s.fileName || '未知文档') + ' §' + r.data.title
    }
  } catch (e) { /* 接口失败：回退显示 snippet */ }
  finally { sourceLoading.value = false }
}
// 灯箱切换：重置缩放/平移，到头禁用（不循环）
const resetView = () => { zoom.value = 1; offset.value = { x: 0, y: 0 } }
const prev = () => { if (previewIndex.value > 0) { previewIndex.value--; resetView() } }
const next = () => { if (previewIndex.value < previewList.value.length - 1) { previewIndex.value++; resetView() } }

// 回答反馈（👍👎 + 可选文本）
const feedbackVisible = ref(false)
const feedbackSubmitting = ref(false)
const feedbackText = ref('')
const feedbackTarget = ref(null) // { msg, rating }
const openFeedback = (m, rating) => {
  feedbackTarget.value = { msg: m, rating }
  feedbackText.value = ''
  feedbackVisible.value = true
}
const submitFeedback = async () => {
  const t = feedbackTarget.value
  if (!t || !t.msg.messageId) { message.warning('该回答不可反馈'); return }
  feedbackSubmitting.value = true
  try {
    const r = await apiSubmitFeedback(t.msg.messageId, t.rating, feedbackText.value.trim())
    if (r.success) {
      t.msg.fb = t.rating
      message.success('感谢反馈')
      feedbackVisible.value = false
    } else message.error(r.msg || '提交失败')
  } catch (e) { message.error(e.message || '提交失败') }
  finally { feedbackSubmitting.value = false }
}

// 灯箱：关闭（重置缩放与平移）
const close = () => {
  previewList.value = []
  previewIndex.value = 0
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

// ESC 关闭灯箱，← → 切换图片
const onKeydown = e => {
  if (e.key === 'Escape') close()
  else if (e.key === 'ArrowLeft') prev()
  else if (e.key === 'ArrowRight') next()
}
watch(previewUrl, v => {
  if (v) window.addEventListener('keydown', onKeydown)
  else window.removeEventListener('keydown', onKeydown)
})
onUnmounted(() => window.removeEventListener('keydown', onKeydown))

// 窄窗口（<640px）自动折叠侧边栏：保证浏览器窗口可以任意缩小，不出现横向溢出
const handleWindowResize = () => {
  if (window.innerWidth < 640 && sidebarWidth.value >= SIDEBAR_ICON_THRESHOLD) {
    sidebarWidth.value = SIDEBAR_ICON_W
    localStorage.setItem('ai_sidebar_width', String(sidebarWidth.value))
  }
}
window.addEventListener('resize', handleWindowResize)
onUnmounted(() => window.removeEventListener('resize', handleWindowResize))

// 初始化：加载会话列表 → 选择最近会话或新建
onMounted(async () => {

  await loadSessions()
  if (sessions.value.length > 0) {
    await switchSession(sessions.value[0].id)
  } else {
    await createNewSession()
  }
})

// 加载会话列表（支持关键词搜索）
const searchKw = ref('')
async function loadSessions(keyword) {
  sessionsLoading.value = true
  try {
    const r = await listSessions(keyword ?? searchKw.value)
    if (r.success && Array.isArray(r.data)) {
      sessions.value = r.data
    }
  } catch (e) {
    message.error('加载会话列表失败: ' + (e.message || '未知错误'))
  } finally {
    sessionsLoading.value = false
  }
}
// 侧边栏搜索输入
const onSearchSessions = kw => {
  searchKw.value = kw
  loadSessions(kw)
}
// 收藏筛选（本地过滤）
const favFilter = ref('all')
const onFilterChange = f => { favFilter.value = f }

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
          images: Array.isArray(m.images) ? m.images : [],
          sources: Array.isArray(m.sources) ? m.sources : [],
          related: []
        }))
      scroll()
    } else {
      messages.value = []
    }
  } catch (e) {
    messages.value = []
  }
}

// 会话列表显示：隐藏空会话 + 收藏筛选（收藏 tab 仅展示收藏项）
const visibleSessions = computed(() => {
  let list = sessions.value.filter(s => (s.messageCount ?? 0) > 0)
  if (favFilter.value === 'fav') {
    list = list.filter(s => s.isFavorite === 1)
  }
  return list
})

// 置顶/取消置顶（本地更新 + 原地排序，避免整表刷新导致 tooltip 卡住）
async function handleTogglePin(s) {
  try {
    const next = !(s.isPinned === 1)
    const r = await pinSession(s.id, next)
    if (r.success) {
      const target = sessions.value.find(x => x.id === s.id)
      if (target) target.isPinned = next ? 1 : 0
      sortSessions()
      message.success(next ? '已置顶' : '已取消置顶')
    } else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
}

// 收藏/取消收藏（本地更新，不整表刷新）
async function handleToggleFavorite(s) {
  try {
    const next = !(s.isFavorite === 1)
    const r = await favoriteSession(s.id, next)
    if (r.success) {
      const target = sessions.value.find(x => x.id === s.id)
      if (target) target.isFavorite = next ? 1 : 0
      message.success(next ? '已收藏' : '已取消收藏')
    } else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
}

// 原地排序（置顶优先 + 更新时间倒序），保持与后端 listSessions 一致；不替换数组引用避免 DOM 重建
function sortSessions() {
  sessions.value.sort((a, b) =>
    (b.isPinned || 0) - (a.isPinned || 0) || new Date(b.updateTime) - new Date(a.updateTime))
}

// 新建会话（防重复 + 无感复用空会话：已存在空会话时切换过去，不新建不提示）
const creatingSession = ref(false)
async function createNewSession() {
  if (creatingSession.value) return
  // 列表已存在空会话（messageCount=0）→ 无感复用，避免堆叠一堆"新建对话"
  const emptySid = sessions.value.find(s => (s.messageCount ?? 0) === 0)?.id
  if (emptySid) {
    if (currentSessionId.value !== emptySid) {
      await switchSession(emptySid)
    } else {
      messages.value = []
    }
    return
  }
  creatingSession.value = true
  try {
    const r = await newSession()
    if (r.success && r.data?.sessionId) {
      currentSessionId.value = r.data.sessionId
      messages.value = []
      await loadSessions()   // 立即刷新会话列表（新会话置顶显示）
    }
  } catch (e) {
    message.error('创建会话失败: ' + (e.message || '未知错误'))
  } finally {
    creatingSession.value = false
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

// 清空所有对话（sidebar 已二次确认）
async function handleClearAll() {
  try {
    await clearAllSessionsApi()
    messages.value = []
    currentSessionId.value = null
    sessions.value = []
    message.success('所有对话已清空')
  } catch (e) {
    message.error('清空失败: ' + (e.message || '未知错误'))
  }
}

// 用户上传图片：压缩为 data URL（最长边 1280，JPEG 0.85），最多 5 张
const pendingImages = ref([])
const fileInput = ref(null)
const pickImages = () => { if (pendingImages.value.length >= 5) { message.warning('最多上传 5 张图片'); return } fileInput.value?.click() }
const onFilesChange = e => {
  const files = Array.from(e.target.files || [])
  e.target.value = ''
  for (const f of files) {
    if (pendingImages.value.length >= 5) { message.warning('最多上传 5 张图片'); break }
    if (!f.type.startsWith('image/')) { message.warning(`跳过非图片文件: ${f.name}`); continue }
    compressImage(f).then(dataUrl => pendingImages.value.push({ dataUrl })).catch(() => message.error(`图片处理失败: ${f.name}`))
  }
}
const compressImage = file => new Promise((resolve, reject) => {
  const img = new Image()
  const url = URL.createObjectURL(file)
  img.onload = () => {
    const max = 1280
    let { width, height } = img
    if (width > max || height > max) {
      const ratio = Math.min(max / width, max / height)
      width = Math.round(width * ratio); height = Math.round(height * ratio)
    }
    const canvas = document.createElement('canvas')
    canvas.width = width; canvas.height = height
    canvas.getContext('2d').drawImage(img, 0, 0, width, height)
    URL.revokeObjectURL(url)
    resolve(canvas.toDataURL('image/jpeg', 0.85))
  }
  img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('图片加载失败')) }
  img.src = url
})
const removePendingImage = i => pendingImages.value.splice(i, 1)
// 待发送预览图 → 灯箱放大（所有待发送图可左右切换）
const previewPendingImage = pi => {
  if (!pendingImages.value.length) return
  previewList.value = pendingImages.value.map(p => p.dataUrl)
  previewIndex.value = pi
  zoom.value = 1
  offset.value = { x: 0, y: 0 }
}
// 用户气泡图片 → 灯箱（该消息全部图片可切换）
const openPreviewFromMsg = (m, index) => {
  if (!m.images || !m.images.length) return
  previewList.value = m.images.map(resolveImg)
  previewIndex.value = index || 0
  zoom.value = 1
  offset.value = { x: 0, y: 0 }
}

const send = () => {
  const q = text.value.trim()
  const imgs = pendingImages.value.map(p => p.dataUrl)
  if ((!q && !imgs.length) || loading.value) return
  text.value = ''
  pendingImages.value = []
  messages.value.push({ role: 'user', content: q, images: imgs })
  streamAnswer(q, imgs, null, messages.value.length === 1)
}

// 统一流式回答：replaceIdx 为 null 追加新 AI 消息；否则替换该条（重新生成）
// autoRetry：剩余自动重试次数（仅"未收到任何 token"的瞬时断连才自动重试，避免清空已生成内容）
const streamAnswer = (question, imgs, replaceIdx, isFirstMessage, autoRetry = 1) => {
  const idx = replaceIdx ?? messages.value.length
  if (replaceIdx == null) {
    messages.value.push({ role: 'ai', content: '', images: [], sources: [], related: [], loading: true })
  } else {
    messages.value[replaceIdx] = { role: 'ai', content: '', images: [], sources: [], related: [], loading: true, messageId: null, fb: 0 }
  }
  loading.value = true
  abortController.value = new AbortController()
  let full = ''
  let gotToken = false
  sendQuestion(currentSessionId.value, question, imgs, {
    signal: abortController.value.signal,
    onToken: t => { gotToken = true; full += t; messages.value[idx].content = full; scroll() },
    onImage: imgs => {
      try {
        const parsed = JSON.parse(imgs)
        messages.value[idx].images = Array.isArray(parsed) ? parsed : []
      } catch (e) {
        messages.value[idx].images = []
      }
    },
    onDone: contentJson => {
      // done 事件 content 为 {sources, related, messageId} JSON 字符串
      let sources = [], related = [], messageId = null
      try {
        const p = JSON.parse(contentJson || '{}')
        sources = Array.isArray(p.sources) ? p.sources : []
        related = Array.isArray(p.related) ? p.related : []
        messageId = p.messageId || null
        // 后端图片相关性校验后下发的修正内容/图片（覆盖流式中间态，保证 [图片N] 编号与图一致）
        if (typeof p.finalContent === 'string' && p.finalContent !== '') {
          messages.value[idx].content = p.finalContent
        }
        if (Array.isArray(p.finalImages)) {
          messages.value[idx].images = p.finalImages
        }
      } catch (e) { /* 旧版/停止生成：无负载 */ }
      if (messages.value[idx].content === '') messages.value[idx].content = '（已停止生成）'
      messages.value[idx].loading = false
      messages.value[idx].sources = sources
      messages.value[idx].related = related
      messages.value[idx].messageId = messageId
      loading.value = false
      abortController.value = null
      scroll()
      // 首条消息后刷新会话列表（标题已由后端生成）
      if (isFirstMessage) loadSessions()
    },
    onError: e => {
      // 用户主动停止不走到这里（AbortError 在 api.js 按正常结束处理）
      // 瞬时断连自动重试：未收到任何 token 时静默重试（2.5s 退避），替换当前消息而非追加
      if (autoRetry > 0 && !gotToken) {
        message.warning('连接中断，正在自动重试…')
        setTimeout(() => {
          // 等待期间消息列表可能已变化（切换/清空会话），放弃自动重试
          if (idx < messages.value.length && messages.value[idx]?.role === 'ai' && messages.value[idx]?.loading) {
            streamAnswer(question, imgs, idx, false, 0)
          } else {
            loading.value = false
            abortController.value = null
          }
        }, 2500)
        return
      }
      // 已收到部分回答：保留已生成内容（不自动重连清空重来），标记 failed 显示「重试」按钮
      messages.value[idx].content = '😅 ' + e
      messages.value[idx].loading = false
      messages.value[idx].failed = true
      loading.value = false
      abortController.value = null
      message.error(e)
      scroll()
    }
  })
}

// 重新生成：找到该回答对应的用户问题，重新流式回答（替换本条）
const regenerate = mi => {
  if (loading.value) return
  for (let i = mi - 1; i >= 0; i--) {
    if (messages.value[i].role === 'user') {
      // 仅本地 dataUrl 图片可重发（历史 URL 图跳过，避免后端无法处理）
      const imgs = (messages.value[i].images || []).filter(u => u.startsWith('data:'))
      streamAnswer(messages.value[i].content, imgs, mi, false)
      return
    }
  }
  message.warning('未找到对应的问题')
}

// 复制回答（Markdown 原文）
const copyAnswer = async mi => {
  const m = messages.value[mi]
  if (!m || !m.content) { message.warning('该回答无可复制内容'); return }
  const txt = m.content.trim()
  if (navigator.clipboard?.writeText) {
    try { await navigator.clipboard.writeText(txt); message.success('已复制到剪贴板') }
    catch (e) { fallbackCopyText(txt) }
  } else {
    fallbackCopyText(txt)
  }
}
const fallbackCopyText = txt => {
  try {
    const ta = document.createElement('textarea')
    ta.value = txt
    ta.setAttribute('readonly', '')
    ta.style.position = 'absolute'
    ta.style.left = '-9999px'
    document.body.appendChild(ta)
    ta.focus(); ta.select(); ta.setSelectionRange(0, txt.length)
    const ok = document.execCommand('copy')
    ta.remove()
    if (ok) message.success('已复制到剪贴板')
    else message.error('复制失败，请手动复制')
  } catch (err) { message.error('复制失败，请手动复制') }
}

// 导出回答为 .md 文件（含会话标题 + 引用来源）
const exportAnswer = mi => {
  const m = messages.value[mi]
  if (!m || !m.content) { message.warning('该回答无可导出内容'); return }
  const title = sessions.value.find(s => s.id === currentSessionId.value)?.title || 'AI回答'
  const parts = [`# ${title}\n`]
  // 收集该回答之前的用户问题（同会话上下文）
  const questions = []
  for (let i = mi - 1; i >= 0; i--) {
    if (messages.value[i].role === 'user') questions.unshift(messages.value[i].content)
  }
  if (questions.length) {
    parts.push('## 问题\n' + questions.join('\n\n') + '\n')
  }
  parts.push('## 回答\n' + m.content.trim() + '\n')
  if (m.sources && m.sources.length) {
    parts.push('## 引用来源\n' + m.sources.map((s, si) =>
      `${si + 1}. ${s.fileName || '未知文档'}${s.title ? ' §' + s.title : ''}`).join('\n') + '\n')
  }
  const md = parts.join('\n')
  const blob = new Blob([md], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  const safeName = (title || new Date().toISOString().slice(0, 10)).replace(/[\\/:*?"<>|]/g, '_')
  a.href = url
  a.download = safeName + '.md'
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

// 检索调试：打开弹窗（默认该轮问题）并执行
const openDebug = mi => {
  for (let i = mi - 1; i >= 0; i--) {
    if (messages.value[i].role === 'user') { debugQuestion.value = messages.value[i].content; break }
  }
  debugVisible.value = true
  runDebug()
}
const runDebug = async () => {
  const q = debugQuestion.value.trim()
  if (!q) { message.warning('请输入问题'); return }
  debugLoading.value = true
  debugResult.value = null
  try {
    const r = await debugRetrieval(q)
    if (r.success) debugResult.value = r.data
    else message.error(r.msg || '调试失败')
  } catch (e) { message.error(e.message || '调试失败') }
  finally { debugLoading.value = false }
}
// 分步调试面板数据（统一行结构：title/docName/snippet/tag）
const debugStages = computed(() => {
  const d = debugResult.value
  if (!d) return []
  const map = (items, tagFn) => (items || []).map(it => ({
    title: it.title || '（无标题）',
    docName: it.docName || '',
    snippet: it.snippet || '',
    titleHit: !!it.titleHit,
    tag: tagFn(it)
  }))
  return [
    { name: `关键词命中（${(d.keywordHits || []).length}）`, items: map(d.keywordHits, it => '命中率 ' + (it.hitRate ?? 0)) },
    { name: `向量命中（${(d.vectorHits || []).length}）`, items: map(d.vectorHits, it => '相似度 ' + (it.score ?? 0)) },
    { name: `合并后（${(d.merged || []).length}）`, items: map(d.merged, it => '分 ' + (it.score ?? 0)) },
    { name: `重排后（${(d.reranked || []).length}）` + (d.rerankApplied ? '' : `（${d.rerankSkipReason || '未重排'}）`), items: map(d.reranked, it => '分 ' + (it.score ?? 0)) },
    { name: `最终上下文（${(d.finalContext || []).length}/8）`, items: map(d.finalContext, it => '分 ' + (it.score ?? 0)) },
    { name: `被排除（${(d.excluded || []).length}）`, items: map(d.excluded, it => '分 ' + (it.score ?? 0)) }
  ]
})

// 编辑问题：回填输入框（回显本地图片）+ 重新发送
const editMessage = mi => {
  const m = messages.value[mi]
  if (!m || m.role !== 'user') return
  text.value = m.content
  // 回显本地图片（dataUrl）到待发送区；历史 URL 图跳过（后端仅处理 data:）
  const localImgs = (m.images || []).filter(u => u.startsWith('data:'))
  if (localImgs.length) {
    pendingImages.value = [...pendingImages.value, ...localImgs.map(u => ({ dataUrl: u }))]
  }
  const urlCount = (m.images || []).length - localImgs.length
  nextTick(() => textareaRef.value?.focus())
  message.info(urlCount > 0
    ? `已回填文字并回显 ${localImgs.length} 张图片（${urlCount} 张历史图片需重新上传）`
    : '已回填到输入框，修改后按 Enter 发送')
}

const stop = () => {
  if (abortController.value) {
    abortController.value.abort()
    abortController.value = null
  }
}

const ask = q => { text.value = q; nextTick(send) }

// ==================== Markdown 渲染（markdown-it + DOMPurify + highlight.js） ====================
// 安全：html:false（不渲染原始 HTML）+ DOMPurify 白名单双保险
const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,                 // 单换行即换行，贴近原手写行为
  langPrefix: 'hljs language-', // 代码块带 hljs 类才能着色
  highlight(str, lang) {
    if (lang && hljs.getLanguage(lang)) {
      try { return hljs.highlight(str, { language: lang, ignoreIllegals: true }).value } catch (e) { /* 落空走兜底 */ }
    }
    return md.utils.escapeHtml(str) // 兜底必须手动转义（markdown-it 不自动转义 highlight 返回值）
  }
})
// 降级标题：h1→h2 ... h6 封顶（保持原手写 #→h2 行为，h1 留给页面）
md.renderer.rules.heading_open = t => `<h${Math.min(+t[0].tag[1] + 1, 6)}>`
md.renderer.rules.heading_close = t => `</h${Math.min(+t[0].tag[1] + 1, 6)}>`

const render = (t, images = []) => {
  if (!t) return ''
  // ① 预处理：图片标记 [图片N：描述]/[图片N] → markdown 图片占位（保留位置/顺序）
  const pre = t.replace(/\[图片\s*(\d+)(?:[：:][^\]]*)?\][，。、；：！？\s]*/g, '![img](__AI_IMG_$1__)')
  // ② 渲染 + 消毒（放行内部图片占位前缀 __AI_IMG_，否则 DOMPurify 会剥掉其 src 导致图片丢失）
  let html = DOMPurify.sanitize(md.render(pre), {
    ALLOWED_URI_REGEXP: /^(?:__AI_IMG_|https?:|data:|mailto:|tel:)/i
  })
  // ③ DOM 后处理（sanitize 之后新建元素不受白名单限制）
  const box = document.createElement('div')
  box.innerHTML = html
  // 图片：占位 → 真实 src + 居中 + data-seq；图片缺失保留原文 [图片N]
  box.querySelectorAll('img[src^="__AI_IMG_"]').forEach(img => {
    const m = (img.getAttribute('src') || '').match(/^__AI_IMG_(\d+)__$/)
    const n = m ? m[1] : ''
    const u = images[Number(n) - 1]
    if (!u) { img.replaceWith(document.createTextNode(`[图片${n}]`)); return }
    const wrap = document.createElement('div')
    wrap.style.textAlign = 'center'
    const real = document.createElement('img')
    real.className = 'md-img'
    real.src = resolveImg(u)
    real.alt = '文档图片'
    real.onerror = onImgError
    real.dataset.seq = n
    wrap.appendChild(real)
    img.replaceWith(wrap)
  })
  // 引用角标：[N] → sup（TreeWalker 跳过 pre/code/a，防误伤代码块/链接内的 [1]）
  const walker = document.createTreeWalker(box, NodeFilter.SHOW_TEXT)
  const targets = []
  while (walker.nextNode()) {
    const node = walker.currentNode
    if (node.nodeValue && /\[\d+\]/.test(node.nodeValue) && !node.parentElement.closest('pre,code,a')) targets.push(node)
  }
  for (const node of targets) {
    const frag = document.createDocumentFragment()
    node.nodeValue.split(/(\[\d+\])/).forEach(part => {
      const m = part.match(/^\[(\d+)\]$/)
      if (m) {
        const sup = document.createElement('sup')
        sup.className = 'ref-sup'
        sup.dataset.ref = m[1]
        sup.textContent = part
        frag.appendChild(sup)
      } else if (part) {
        frag.appendChild(document.createTextNode(part))
      }
    })
    node.parentNode.replaceChild(frag, node)
  }
  // 代码块复制按钮：只生成 HTML 结构（事件统一委托到 openPreview，避免 innerHTML 序列化丢失事件）
  box.querySelectorAll('pre').forEach(pre => {
    const btn = document.createElement('button')
    btn.type = 'button'
    btn.className = 'code-copy'
    btn.title = '复制'
    btn.innerHTML = COPY_SVG
    pre.appendChild(btn)
  })
  return box.innerHTML
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
/* 侧边栏拖拽手柄 */
.sidebar-resizer {
  width: 5px;
  flex-shrink: 0;
  cursor: col-resize;
  background: transparent;
  transition: background .15s;
  position: relative;
  z-index: 2;
}
.sidebar-resizer:hover, .sidebar-resizer:active { background: #cfe3ff; }
.chat {
  flex: 1;
  min-width: 0;
  display: flex;
  justify-content: center;
}
.chat-box {
  width: 100%;
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
  padding: 24px 48px;   /* 左右留白：拉满宽度下消息不贴边 */
}
.welcome {
  text-align: center;
  padding: 60px 20px;
}
.welcome h2 { margin: 16px 0 8px; }
.welcome p { color: #888; margin-bottom: 24px; }
.row { display: flex; gap: 12px; margin-bottom: 20px; }
.row.user { flex-direction: row-reverse; }
.bubble { max-width: min(720px, 70%); padding: 12px 16px; border-radius: 12px; line-height: 1.6; }
.bubble.user { background: #1677ff; color: #fff; }
.bubble.ai { background: #f5f5f5; color: #333; }
.input { padding: 12px 48px 16px; border-top: 1px solid #f0f0f0; }
.input-box { position: relative; }
/* 待发送图片预览 */
.pending-imgs { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 8px; }
.pending-img { position: relative; }
.pending-img img { width: 64px; height: 64px; object-fit: cover; border-radius: 6px; border: 1px solid #e0e0e0; cursor: zoom-in; }
.pending-del { position: absolute; top: -6px; right: -6px; width: 18px; height: 18px; border-radius: 50%;
  background: rgba(0,0,0,.55); color: #fff; font-size: 12px; line-height: 18px; text-align: center; cursor: pointer; }
.pending-del:hover { background: #ff4d4f; }
/* 用户气泡内图片 */
.msg-imgs { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 6px; }
.msg-img { width: 96px; height: 96px; object-fit: cover; border-radius: 6px;
  border: 1px solid rgba(255,255,255,.35); cursor: zoom-in; }
.msg-img:hover { opacity: .85; }
/* 用户消息编辑按钮（hover 显示，气泡右上角） */
.bubble.user { position: relative; }
.msg-edit-btn {
  position: absolute; top: 6px; right: 8px;
  z-index: 1;                        /* 浮在图片缩略图之上 */
  width: 20px; height: 20px;
  display: flex; align-items: center; justify-content: center;
  background: rgba(0,0,0,.35);       /* 半透明圆底，图片上仍清晰可见 */
  border-radius: 50%;
  color: #fff; font-size: 12px; cursor: pointer;
  opacity: 0; transition: opacity .15s;
}
.bubble.user:hover .msg-edit-btn { opacity: 1; }
.msg-edit-btn:hover { color: #fff; }
/* 多行自适应输入框：随内容增高（1~6 行），右侧留出发送/停止按钮位 */
.input-area {
  resize: none;
  padding: 6px 44px 6px 12px;
  font-size: 14px;
  line-height: 1.6;
  border-radius: 8px;
}
.input-area:focus { border-color: #1677ff; box-shadow: 0 0 0 2px rgba(22,119,255,.1); }
.input-suffix {
  position: absolute;
  right: 12px;
  bottom: 8px;
  font-size: 18px;
  display: flex;
  align-items: center;
  line-height: 1;
}
.input-suffix :deep(.ant-btn) { padding: 0; }
.input-suffix .anticon-send { color: #1677ff; }
.input-suffix .anticon-send:hover { opacity: .8; }
.md :deep(img) { max-width: 100%; max-height: 320px; border: 1px solid #e0e0e0; border-radius: 6px; display: block; margin: 8px 0; cursor: zoom-in; }
/* 引用角标 [N] */
.md :deep(.ref-sup) { color: #1677ff; font-size: 12px; cursor: pointer; user-select: none; }
/* Markdown 完整渲染样式 */
.md :deep(p) { margin: 6px 0; }
.md :deep(h2), .md :deep(h3), .md :deep(h4), .md :deep(h5) { margin: 12px 0 6px; font-weight: 600; }
.md :deep(h2) { font-size: 17px; } .md :deep(h3) { font-size: 15px; } .md :deep(h4) { font-size: 14px; }
.md :deep(code) { background: #f0f0f0; padding: 1px 4px; border-radius: 3px; font-size: 13px; }
.md :deep(pre) { background: #f6f8fa; padding: 12px; border-radius: 6px; overflow-x: auto; font-size: 13px; line-height: 1.5; margin: 8px 0; position: relative; }
.md :deep(pre code) { background: none; padding: 0; display: block; white-space: pre; }
.md :deep(table) { border-collapse: collapse; margin: 8px 0; width: 100%; font-size: 13px; }
.md :deep(th), .md :deep(td) { border: 1px solid #e0e0e0; padding: 6px 10px; text-align: left; word-break: break-word; }
.md :deep(th) { background: #fafafa; font-weight: 600; white-space: nowrap; }
.md :deep(blockquote) { margin: 8px 0; padding: 6px 12px; border-left: 3px solid #d9d9d9; color: #666; background: #fafafa; border-radius: 0 4px 4px 0; }
.md :deep(ul), .md :deep(ol) { margin: 4px 0; padding-left: 20px; }
.md :deep(li) { margin: 2px 0; }
.md :deep(li.ul-item.d1) { margin-left: 16px; list-style-type: circle; }
.md :deep(li.ul-item.d2) { margin-left: 32px; list-style-type: square; }
.md :deep(hr) { border: none; border-top: 1px solid #e8e8e8; margin: 10px 0; }
/* 检索调试面板 */
.dbg-item { padding: 6px 8px; margin-bottom: 6px; border: 1px solid #f0f0f0; border-radius: 6px; background: #fafafa; }
.dbg-head { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.dbg-title { font-weight: 500; font-size: 13px; }
.dbg-snippet { margin-top: 3px; font-size: 12px; color: #888; word-break: break-all; }
/* 代码块复制按钮（antd Tooltip 渲染页面层；render 动态创建，scoped 需 :deep） */
.md :deep(.code-copy) {
  position: absolute; top: 8px; right: 8px; z-index: 1;
  width: 26px; height: 26px;
  display: flex; align-items: center; justify-content: center;
  font-size: 14px;
  color: #555; background: rgba(255,255,255,.85);
  border: 1px solid #e0e0e0; border-radius: 4px;
  cursor: pointer; opacity: 0; transition: opacity .15s;
}
.md :deep(pre:hover .code-copy) { opacity: 1; }
.md :deep(.code-copy:hover) { color: #1677ff; border-color: #1677ff; }
.md :deep(.code-copy.copied) { color: #52c41a; border-color: #52c41a; }
.md :deep(.ref-sup:hover) { text-decoration: underline; }
/* 来源引用 chips */
.src-chips { margin-top: 8px; display: flex; flex-wrap: wrap; gap: 4px; }
.src-chips :deep(.ant-tag) { font-size: 12px; }
/* 引用弹窗知识块全文 */
.src-content { max-height: 45vh; overflow-y: auto; color: #333; line-height: 1.7; font-size: 14px;
  padding-right: 6px; scrollbar-width: thin; }
.src-content :deep(img) { max-width: 100%; max-height: 260px; border: 1px solid #e0e0e0;
  border-radius: 6px; display: block; margin: 8px 0; cursor: zoom-in; }
/* 相关推荐 */
.related { margin-top: 10px; display: flex; flex-wrap: wrap; align-items: center; gap: 4px; }
.related-label { font-size: 12px; color: #888; margin-right: 4px; }
/* 回答反馈 */
.fb-row { margin-top: 10px; display: flex; gap: 4px; opacity: .55; transition: opacity .2s; }
.fb-row:hover { opacity: 1; }
.fb-row :deep(.fb-active) { color: #1677ff; font-weight: 600; }
/* 流式中断重试 */
.retry-row { margin-top: 10px; }

/* 图片灯箱（z-index 须高于 antd modal 默认 1000，避免被引用/反馈弹窗盖住） */
.lightbox { position: fixed; inset: 0; background: rgba(0,0,0,.78); display: flex;
  align-items: center; justify-content: center; z-index: 2000; cursor: zoom-out; overflow: hidden; }
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
/* 灯箱上一张/下一张切换按钮 */
.lightbox-prev, .lightbox-next {
  position: fixed; top: 50%; transform: translateY(-50%);
  width: 44px; height: 44px; border-radius: 50%;
  border: 1px solid rgba(255,255,255,.35);
  background: rgba(0,0,0,.4); color: #fff;
  font-size: 26px; line-height: 1; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  transition: background .15s, opacity .15s; z-index: 2001; user-select: none;
}
.lightbox-prev { left: 16px; }
.lightbox-next { right: 16px; }
.lightbox-prev:hover:not(:disabled), .lightbox-next:hover:not(:disabled) { background: rgba(0,0,0,.7); }
.lightbox-prev:disabled, .lightbox-next:disabled { opacity: .25; cursor: not-allowed; }
/* 灯箱图片计数 */
.lightbox-count { position: fixed; bottom: 44px; left: 50%; transform: translateX(-50%);
  color: rgba(255,255,255,.75); font-size: 13px;
  background: rgba(0,0,0,.45); padding: 2px 12px; border-radius: 12px; user-select: none; }
</style>
