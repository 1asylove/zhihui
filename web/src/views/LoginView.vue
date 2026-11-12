<template>
  <div class="login-page">
    <div class="login-card">
      <h1>智汇 · 智能知识中枢</h1>
      <p class="sub">央国企智能知识库问答系统</p>
      <form @submit.prevent="onLogin">
        <input v-model.trim="username" placeholder="用户名" autocomplete="username" />
        <input v-model="password" type="password" placeholder="密码" autocomplete="current-password" />
        <button type="submit" :disabled="loading">{{ loading ? '登录中…' : '登 录' }}</button>
      </form>
      <p v-if="error" class="error">{{ error }}</p>
      <p class="tip">测试账号：test1 / 123456（管理员）、test2 / 123456（普通用户）</p>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api.js'

const router = useRouter()
const username = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')

async function onLogin() {
  if (!username.value || !password.value) {
    error.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const token = await api.login(username.value, password.value)
    localStorage.setItem('token', token)
    localStorage.setItem('username', username.value)
    router.push('/')
  } catch (e) {
    error.value = e.message || '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1e3a5f 0%, #2d5a8a 100%);
}
.login-card {
  width: 360px;
  background: #fff;
  border-radius: 12px;
  padding: 36px 32px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.25);
}
h1 { font-size: 22px; margin: 0 0 4px; color: #1e3a5f; }
.sub { color: #888; font-size: 13px; margin: 0 0 24px; }
form { display: flex; flex-direction: column; gap: 14px; }
input {
  padding: 11px 14px;
  border: 1px solid #d9d9d9;
  border-radius: 8px;
  font-size: 14px;
  outline: none;
}
input:focus { border-color: #2d5a8a; }
button {
  padding: 11px;
  background: #2d5a8a;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 15px;
  cursor: pointer;
}
button:disabled { opacity: 0.6; cursor: not-allowed; }
.error { color: #d4380d; font-size: 13px; margin: 12px 0 0; }
.tip { color: #aaa; font-size: 12px; margin: 16px 0 0; text-align: center; }
</style>
