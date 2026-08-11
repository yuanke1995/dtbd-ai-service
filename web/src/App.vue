<template>
  <a-layout style="min-height:100vh">
    <a-layout-header class="header">
      <div class="logo">
        <robot-outlined style="color:#fff;font-size:20px;margin-right:8px" />
        <span>DTBD AI 助手 · 测试台</span>
      </div>
      <a-menu theme="dark" mode="horizontal" :selected-keys="[activeKey]" @click="onMenu" class="menu">
        <a-menu-item key="chat">智能问答</a-menu-item>
        <a-menu-item key="documents">文档管理</a-menu-item>
        <a-menu-item key="dashboard">数据看板</a-menu-item>
      </a-menu>
    </a-layout-header>
    <a-layout-content class="content">
      <router-view />
    </a-layout-content>
  </a-layout>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { RobotOutlined } from '@ant-design/icons-vue'

const route = useRoute()
const router = useRouter()
const activeKey = computed(() => {
  if (route.path === '/documents') return 'documents'
  if (route.path === '/dashboard') return 'dashboard'
  return 'chat'
})
const onMenu = ({ key }) => router.push(key === 'documents' ? '/documents' : key === 'dashboard' ? '/dashboard' : '/chat')
</script>

<style>
html, body { margin: 0; overflow-x: hidden; }
.header { display:flex;align-items:center }
.logo { color:#fff;font-size:16px;font-weight:600;display:flex;align-items:center;margin-right:40px }
.menu { flex:1;min-width:0 }
.content { padding:24px;background:#f0f2f5 }
</style>