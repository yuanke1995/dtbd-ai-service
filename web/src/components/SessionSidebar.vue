<template>
  <div class="session-sidebar" :class="{ collapsed }">
    <div class="sidebar-inner">
      <!-- 头部：新建对话 -->
      <div class="sidebar-header">
        <a-button type="primary" block @click="$emit('new')" :disabled="loading">
          <plus-outlined /> 新建对话
        </a-button>
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

    <!-- 折叠/展开手柄 -->
    <div class="toggle-handle" @click="$emit('toggle-collapse')" :title="collapsed ? '展开侧边栏' : '收起侧边栏'">
      <menu-fold-outlined v-if="!collapsed" />
      <menu-unfold-outlined v-else />
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
  width: 0;
  min-width: 0;
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
.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
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
.toggle-handle {
  position: absolute;
  right: -28px;
  top: 50%;
  transform: translateY(-50%);
  width: 28px;
  height: 48px;
  background: #fafafa;
  border: 1px solid #e8e8e8;
  border-left: none;
  border-radius: 0 6px 6px 0;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: #888;
  font-size: 14px;
  z-index: 10;
  transition: color 0.15s;
}
.toggle-handle:hover {
  color: #1677ff;
  background: #f0f0f0;
}
.session-sidebar.collapsed .toggle-handle {
  right: -28px;
}
</style>
