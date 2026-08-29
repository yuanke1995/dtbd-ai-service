<template>
  <div class="session-sidebar" :class="{ collapsed, 'no-anim': dragging }"
       :style="{ width: width + 'px', minWidth: width + 'px' }">
    <!-- 展开态：新建对话 + 会话列表 -->
    <div v-if="!collapsed" class="sidebar-inner">
      <!-- 头部：新建对话 + 清空 + 收起按钮 -->
      <div class="sidebar-header">
        <div class="header-row">
          <a-button type="primary" block @click="$emit('new')" :disabled="loading || creating"
                    :style="width < 220 ? 'padding: 0 8px' : ''">
            <plus-outlined />
            <span v-if="width >= 220">新建对话</span>
          </a-button>
          <a-tooltip title="清空所有对话">
            <a-popconfirm title="确定清空所有对话？此操作不可恢复" ok-text="清空" cancel-text="取消"
                          placement="bottomRight" @confirm="$emit('clear')" @click.stop>
              <a-button type="text" class="collapse-btn">
                <clear-outlined />
              </a-button>
            </a-popconfirm>
          </a-tooltip>
          <a-tooltip title="收起侧边栏">
            <a-button type="text" class="collapse-btn" @click="$emit('toggle-collapse')">
              <menu-fold-outlined />
            </a-button>
          </a-tooltip>
        </div>
      </div>

      <!-- 搜索 + 收藏筛选 -->
      <div class="sidebar-search" v-if="!collapsed">
        <div class="search-row">
          <a-input
            v-model:value="searchText"
            placeholder="搜索会话"
            allow-clear
            size="small"
            @input="onSearchInput"
            @clear="onSearchClear"
          >
            <template #prefix><search-outlined style="color:#bbb" /></template>
          </a-input>
        </div>
        <div class="filter-row">
          <span class="filter-tab" :class="{ active: filter === 'all' }" @click="setFilter('all')">全部</span>
          <span class="filter-tab" :class="{ active: filter === 'fav' }" @click="setFilter('fav')">收藏</span>
        </div>
      </div>

      <!-- 会话列表 -->
      <div class="session-list">
        <div v-if="sessions.length === 0" class="empty">
          <span>{{ searchText || filter === 'fav' ? '无匹配会话' : '暂无历史会话' }}</span>
        </div>
        <div
          v-for="s in sessions"
          :key="s.id"
          class="session-item"
          :class="{ active: s.id === currentSessionId }"
          @click="$emit('select', s.id)"
        >
          <div class="session-info">
            <div class="session-title">
              <span v-if="s.isPinned" class="pin-mark"><pushpin-filled style="font-size:11px" /></span>
              {{ s.title || '新对话' }}
            </div>
            <div class="session-meta">
              <span>{{ formatTime(s.updateTime) }}</span>
              <span class="msg-count">{{ s.messageCount || 0 }} 条消息</span>
            </div>
          </div>
          <div class="session-actions" @click.stop>
            <a-tooltip :title="s.isFavorite ? '取消收藏' : '收藏'" placement="top"
                       :open="!!favTipOpen[s.id]" @open-change="v => favTipOpen[s.id] = v">
              <star-outlined class="act-icon" :class="{ fav: s.isFavorite }"
                             @click="onToggleFavorite(s)" />
            </a-tooltip>
            <a-tooltip :title="s.isPinned ? '取消置顶' : '置顶'" placement="top"
                       :open="!!pinTipOpen[s.id]" @open-change="v => pinTipOpen[s.id] = v">
              <pushpin-outlined class="act-icon" :class="{ pin: s.isPinned }"
                                @click="onTogglePin(s)" />
            </a-tooltip>
            <a-popconfirm
              title="确定删除该会话？"
              ok-text="删除"
              cancel-text="取消"
              placement="right"
              @confirm="$emit('delete', s.id)"
            >
              <delete-outlined class="delete-icon" />
            </a-popconfirm>
          </div>
        </div>
      </div>
    </div>

    <!-- 折叠态：小图标条（展开 + 新建 + 对话） -->
    <div v-else class="collapsed-bar">
      <a-tooltip title="展开会话列表" placement="right">
        <a-button type="text" class="cb-btn" @click="$emit('toggle-collapse')">
          <menu-unfold-outlined />
        </a-button>
      </a-tooltip>
      <a-tooltip title="新建对话" placement="right">
        <a-button type="text" class="cb-btn" @click="$emit('new')" :disabled="loading || creating">
          <plus-outlined />
        </a-button>
      </a-tooltip>
      <a-tooltip :title="`会话列表（共 ${sessions.length} 条）`" placement="right">
        <div class="cb-badge" @click="$emit('toggle-collapse')">
          <a-badge :count="sessions.length" :overflow-count="99" size="small">
            <a-button type="text" class="cb-btn" style="pointer-events:none">
              <comment-outlined />
            </a-button>
          </a-badge>
        </div>
      </a-tooltip>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { PlusOutlined, DeleteOutlined, MenuFoldOutlined, MenuUnfoldOutlined, ClearOutlined, CommentOutlined,
         SearchOutlined, PushpinOutlined, PushpinFilled, StarOutlined } from '@ant-design/icons-vue'

defineProps({
  sessions: { type: Array, default: () => [] },
  currentSessionId: { type: String, default: null },
  collapsed: { type: Boolean, default: false },
  loading: { type: Boolean, default: false },
  creating: { type: Boolean, default: false },   // 新建对话进行中（防重复点击）
  width: { type: Number, default: 260 },         // 展开态宽度（可由外层拖拽调整）
  dragging: { type: Boolean, default: false }    // 拖拽中（禁用宽度过渡动画）
})

const emit = defineEmits(['select', 'delete', 'new', 'toggle-collapse', 'clear', 'toggle-pin', 'toggle-favorite', 'search', 'filter-change'])

// 置顶/收藏 tooltip 受控 open 状态：点击图标时立即关闭，避免浮层残留
const pinTipOpen = reactive({})
const favTipOpen = reactive({})
const onTogglePin = s => {
  pinTipOpen[s.id] = false
  emit('toggle-pin', s)
}
const onToggleFavorite = s => {
  favTipOpen[s.id] = false
  emit('toggle-favorite', s)
}

// 搜索（300ms 防抖）
const searchText = ref('')
let debounceTimer = null
const onSearchInput = () => {
  clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => emit('search', searchText.value.trim()), 300)
}
const onSearchClear = () => {
  clearTimeout(debounceTimer)
  emit('search', '')
}
// 收藏筛选 tab（父组件按 s.isFavorite 本地过滤）
const filter = ref('all')
const setFilter = f => {
  filter.value = f
  emit('filter-change', f)
}

const formatTime = (t) => {
  if (!t) return ''
  const d = new Date(t)
  if (isNaN(d.getTime())) return ''
  const now = new Date()
  const isToday = d.toDateString() === now.toDateString()
  if (isToday) {
    return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  return `${mm}-${dd}`
}
</script>

<style scoped>
.session-sidebar {
  background: #fafafa;
  border-right: 1px solid #e8e8e8;
  display: flex;
  flex-direction: row;
  overflow: hidden;
  position: relative;
  transition: width 0.18s ease, min-width 0.18s ease; /* 阈值折叠/展开时平滑过渡 */
}
.session-sidebar.no-anim { transition: none; }  /* 拖拽中禁用动画，保证跟手 */
.session-sidebar.collapsed { /* 宽度由 inline style 控制（48px） */ }
/* 折叠态小图标条 */
.collapsed-bar {
  width: 100%;           /* 跟随容器宽度（用户拖到哪宽度就是哪），图标居中 */
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-top: 10px;
  gap: 4px;
}
.cb-btn {
  width: 36px;
  height: 36px;
  padding: 0 !important;            /* 覆盖 antd 按钮默认 padding，图标严格居中 */
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  color: #555;
  border-radius: 8px;
}
.cb-btn:hover {
  background: #f0f0f0 !important;
  color: #1677ff !important;
}
/* 折叠态对话图标（含条数角标） */
.cb-badge {
  cursor: pointer;
  border-radius: 8px;
  padding: 2px;
}
.cb-badge:hover {
  background: #f0f0f0;
}
.cb-badge :deep(.ant-badge) { display: block; }
.cb-badge :deep(.ant-badge-count) {
  box-shadow: none;
  font-size: 10px;
  /* 角标默认突出在右上角外，内收使其落在 hover 圆角范围内 */
  transform: translate(20%, -20%) !important;
  right: 6px;
  top: 4px;
}
.sidebar-inner {
  width: 100%;
  min-width: 0;              /* 跟随外层拖拽宽度伸缩 */
  display: flex;
  flex-direction: column;
  height: 100%;
}
.sidebar-header {
  padding: 12px;
  border-bottom: 1px solid #e8e8e8;
}
/* 搜索 + 收藏筛选 */
.sidebar-search {
  padding: 8px 12px 4px;
  border-bottom: 1px solid #f0f0f0;
}
.search-row { margin-bottom: 6px; }
.filter-row {
  display: flex;
  gap: 4px;
  padding-bottom: 6px;
}
.filter-tab {
  font-size: 12px;
  color: #666;
  padding: 2px 10px;
  border-radius: 10px;
  cursor: pointer;
  transition: all .15s;
  user-select: none;
}
.filter-tab:hover { color: #1677ff; }
.filter-tab.active {
  background: #e6f4ff;
  color: #1677ff;
  font-weight: 500;
}
/* 会话项操作区（hover 显示） */
.session-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  opacity: 0;
  transition: opacity .15s;
  flex-shrink: 0;
  margin-left: 6px;
}
.session-item:hover .session-actions { opacity: 1; }
.session-item.active .session-actions { opacity: 1; }
.act-icon {
  color: #bbb;
  font-size: 13px;
  padding: 3px;
  cursor: pointer;
  border-radius: 4px;
}
.act-icon:hover { color: #1677ff; background: #f0f0f0; }
.act-icon.fav { color: #faad14; }
.act-icon.pin { color: #1677ff; }
/* 置顶标题前的图钉标记 */
.pin-mark { color: #1677ff; margin-right: 2px; }
.delete-icon {
  color: #ccc;
  font-size: 13px;
  flex-shrink: 0;
  padding: 3px;
  border-radius: 4px;
  transition: color 0.15s;
}
.delete-icon:hover {
  color: #ff4d4f;
  background: #fff1f0;
}
.header-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.collapse-btn {
  color: #888;
  font-size: 14px;
  flex-shrink: 0;
}
.collapse-btn:hover {
  color: #1677ff !important;
  background: #f0f0f0 !important;
}
.session-list {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 8px;
  scrollbar-width: thin; /* Firefox */
  scrollbar-color: #d9d9d9 transparent;
}
/* 细滚动条（Webkit/Chrome/Edge）：默认淡色，hover 深色 */
.session-list::-webkit-scrollbar {
  width: 6px;
}
.session-list::-webkit-scrollbar-track {
  background: transparent;
}
.session-list::-webkit-scrollbar-thumb {
  background: #d9d9d9;
  border-radius: 3px;
}
.session-list::-webkit-scrollbar-thumb:hover {
  background: #bfbfbf;
}
.empty {
  text-align: center;
  color: #999;
  padding: 32px 16px;
  font-size: 13px;
}
.session-item {
  display: flex;
  align-items: center;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  margin-bottom: 2px;
  transition: background 0.15s;
}
.session-item:hover {
  background: #f0f0f0;
}
.session-item.active {
  background: #e6f4ff;
}
.session-info {
  flex: 1;
  min-width: 0;
  overflow: hidden;
}
.session-title {
  font-size: 14px;
  color: #333;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  line-height: 1.4;
}
.session-meta {
  font-size: 12px;
  color: #999;
  margin-top: 2px;
  display: flex;
  gap: 8px;
  align-items: center;
  /* 侧边栏拖窄时禁止逐字折行（"3 条消息"会竖排）：时间保持完整，条数超宽时省略号 */
  white-space: nowrap;
  overflow: hidden;
}
.session-meta > span:first-child { flex-shrink: 0; }
.msg-count {
  color: #bbb;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
