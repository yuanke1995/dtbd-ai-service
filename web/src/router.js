import { createRouter, createWebHistory } from 'vue-router'
import Chat from './views/Chat.vue'
import Documents from './views/Documents.vue'
import Dashboard from './views/Dashboard.vue'

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/chat' },
    { path: '/chat', component: Chat },
    { path: '/documents', component: Documents },
    { path: '/dashboard', component: Dashboard }
  ]
})
