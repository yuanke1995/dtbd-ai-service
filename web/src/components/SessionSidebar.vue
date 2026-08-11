<template>
  <div class="session-sidebar" :class="{ collapsed }">
    <!-- 展开态：新建对话 + 会话列表 -->
    <div v-if="!collapsed" class="sidebar-inner">
      <!-- 头部：新建对话 + 收起按钮 -->
      <div class="sidebar-header">
        <div class="header-row">
          <a-button type="primary" block @click="$emit('new')" :disabled="loading">
            <plus-outlined /> 新建对话
          </a-button>
          <a-tooltip title="收起侧边栏">
            <a-button type="text" class="collapse-btn" @click="$emit('toggle-collapse')">
              <menu-fold-outlined />
            </a-button>
          </a-tooltip>
        </div>
      </div>

      <!-- 会话列表 -->
      <div class="session-list">
        <div v-if="sessions.length === 0" class="empty">
          <span>暂无历史会话</span>
        </div>
        <div
          v-for="s in sessions"
          :key="s.id"
          class="session-item"
          :class="{ active: s.id === currentSessionId }"
          @click="$emit('select', s.id)"
        >
          <div class="session-info">
            <div class="session-title">{{ s.title || '新对话' }}</div>
            <div class="session-meta">
              <span>{{ formatTime(s.updateTime) }}</span>
              <span class="msg-count">{{ s.messageCount || 0 }} 条消息</span>
            </div>
          </div>
          <a-popconfirm
            title="确定删除该会话？"
            ok-text="删除"
            cancel-text="取消"
            placement="right"
            @confirm.stop="$emit('delete', s.id)"
            @click.stop
          >
            <delete-outlined class="delete-icon" />
          </a-popconfirm>
        </div>
      </div>
    </div>

    <!-- 折叠态：小图标条（新建 + 展开） -->
    <div v-else class="collapsed-bar">
      <a-tooltip title="新建对话" placement="right">
        <a-button type="text" class="cb-btn" @click="$emit('new')" :disabled="loading">
          <plus-outlined />
        </a-button>
      </a-tooltip>
      <a-tooltip title="展开会话列表" placement="right">
        <a-button type="text" class="cb-btn" @click="$emit('toggle-collapse')">
          <menu-unfold-outlined />
        </a-button>
      </a-tooltip>
    </div>
  </div>
</template>

<script setup>
import { PlusOutlined, DeleteOutlined, MenuFoldOutlined, MenuUnfoldOutlined } from '@ant-design/icons-vue'

defineProps({
  sessions: { type: Array, default: () => [] },
  currentSessionId: { type: String, default: null },
  collapsed: { type: Boolean, default: false },
  loading: { type: Boolean, default: false }
})

defineEmits(['select', 'delete', 'new', 'toggle-collapse'])

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
  width: 260px;
  min-width: 260px;
  background: #fafafa;
  border-right: 1px solid #e8e8e8;
  display: flex;
  flex-direction: row;
  transition: width 0.2s, min-width 0.2s;
  overflow: hidden;
  position: relative;
}
.session-sidebar.collapsed {
  width: 48px;
  min-width: 48px;
}
/* 折叠态小图标条 */
.collapsed-bar {
  width: 48px;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-top: 10px;
  gap: 4px;
}
.cb-btn {
  width: 36px;
  height: 36px;
  font-size: 16px;
  color: #555;
  border-radius: 8px;
}
.cb-btn:hover {
  background: #f0f0f0 !important;
  color: #1677ff !important;
}
.sidebar-inner {
  width: 260px;
  min-width: 260px;
  display: flex;
  flex-direction: column;
  height: 100%;
}
.sidebar-header {
  padding: 12px;
  border-bottom: 1px solid #e8e8e8;
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
}
.msg-count {
  color: #bbb;
}
.delete-icon {
  color: #ccc;
  font-size: 14px;
  flex-shrink: 0;
  margin-left: 8px;
  transition: color 0.15s;
}
.delete-icon:hover {
  color: #ff4d4f;
}
</style>
