import { createRouter, createWebHistory } from 'vue-router'
import LoginView from './views/LoginView.vue'
import ChatView from './views/ChatView.vue'
import DocumentsView from './views/DocumentsView.vue'
import DashboardView from './views/DashboardView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView },
    { path: '/', component: ChatView },
    { path: '/docs', component: DocumentsView },
    { path: '/dashboard', component: DashboardView }
  ]
})

router.beforeEach((to) => {
  const token = localStorage.getItem('token')
  if (!token && to.path !== '/login') return '/login'
  if (token && to.path === '/login') return '/'
})

export default router
