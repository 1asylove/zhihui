<template>
  <div class="layout">
    <!-- 左侧：会话列表 -->
    <aside class="sidebar">
      <div class="side-header">
        <span class="logo">智汇</span>
        <button class="new-btn" @click="newChat">+ 新会话</button>
      </div>
      <div class="session-list">
        <div
          v-for="s in sessions"
          :key="s.id"
          class="session-item"
          :class="{ active: s.id === activeSessionId }"
          @click="selectSession(s)"
        >
          <span class="title">{{ s.title || '未命名会话' }}</span>
          <span class="del" title="删除会话" @click.stop="onDeleteSession(s)">×</span>
        </div>
        <p v-if="!sessions.length" class="empty">暂无会话，直接提问会自动建会话</p>
      </div>
      <div class="side-footer">
        <span>{{ username }}</span>
        <router-link to="/dashboard">数据看板</router-link>
        <router-link to="/docs">文档管理</router-link>
        <a href="javascript:;" @click="logout">退出</a>
      </div>
    </aside>

    <!-- 右侧：聊天区 -->
    <main class="chat">
      <header class="chat-header">
        <span class="mode-label">回答模式：</span>
        <button class="mode-btn" :class="{ on: mode === 'agent' }" @click="mode = 'agent'">Agent</button>
        <button class="mode-btn" :class="{ on: mode === 'stream' }" @click="mode = 'stream'">流式 RAG</button>
        <span class="mode-hint">{{ mode === 'agent' ? 'POST /agent/chat · LangGraph 意图路由 + 工具调用' : 'POST /qa/stream · SSE 逐字输出' }}</span>
      </header>

      <div ref="msgBox" class="messages">
        <div v-if="!messages.length" class="welcome">
          <h2>向知识库提问</h2>
          <p>试试：「制度由哪个部门负责解释」</p>
        </div>

        <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
          <div class="bubble">
            <!-- 降级提示 -->
            <div v-if="m.meta && m.meta.degraded" class="degraded-bar">
              Agent 服务不可用，已降级为本地 RAG 回答
            </div>
            <div class="content">{{ m.content }}<span v-if="m.streaming" class="cursor">▍</span></div>

            <!-- Agent 元信息 -->
            <div v-if="m.meta" class="meta-row">
              <span class="badge" :class="m.meta.route">{{ routeLabel(m.meta.route) }}</span>
              <span v-for="t in m.meta.toolsUsed" :key="t.name" class="tool-chip" :class="{ fail: !t.ok }">
                {{ t.name }} · {{ t.ok ? `${t.hitCount} 命中` : '失败' }} · {{ t.tookMs }}ms
              </span>
              <span class="took">共 {{ m.meta.tookMs }}ms</span>
            </div>

            <!-- 引用卡片 -->
            <div v-if="m.citations && m.citations.length" class="citations">
              <div v-for="(c, ci) in m.citations" :key="ci" class="cite-card" @click="toggleCite(i, ci)">
                <div class="cite-head">
                  <span class="cite-no">[{{ ci + 1 }}]</span>
                  <span class="cite-file">{{ c.fileName }}</span>
                  <span class="cite-info">片段 {{ c.chunkIndex }} · {{ Number(c.score).toFixed(3) }}</span>
                </div>
                <div v-if="isCiteOpen(i, ci)" class="cite-body">{{ c.content }}</div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <p v-if="error" class="error-bar">{{ error }}</p>

      <footer class="input-bar">
        <textarea
          v-model="input"
          rows="2"
          placeholder="输入问题，Enter 发送，Shift+Enter 换行"
          @keydown.enter.exact.prevent="send"
        ></textarea>
        <button :disabled="sending || !input.trim()" @click="send">
          {{ sending ? '回答中…' : '发送' }}
        </button>
      </footer>
    </main>
  </div>
</template>

<script setup>
import { nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, streamQa } from '../api.js'

const router = useRouter()
const username = localStorage.getItem('username') || ''

const sessions = ref([])
const activeSessionId = ref(null)
const messages = ref([])
const input = ref('')
const sending = ref(false)
const error = ref('')
const mode = ref('agent')
const msgBox = ref(null)
const openCites = ref(new Set())

onMounted(loadSessions)

async function loadSessions() {
  try {
    sessions.value = await api.listSessions()
  } catch (e) {
    error.value = e.message
  }
}

async function selectSession(s) {
  activeSessionId.value = s.id
  error.value = ''
  try {
    messages.value = await api.sessionMessages(s.id)
    scrollToBottom()
  } catch (e) {
    error.value = e.message
  }
}

function newChat() {
  activeSessionId.value = null
  messages.value = []
  error.value = ''
}

async function onDeleteSession(s) {
  if (!confirm(`删除会话「${s.title}」？`)) return
  try {
    await api.deleteSession(s.id)
    if (s.id === activeSessionId.value) newChat()
    await loadSessions()
  } catch (e) {
    error.value = e.message
  }
}

function logout() {
  localStorage.removeItem('token')
  localStorage.removeItem('username')
  router.push('/login')
}

function routeLabel(route) {
  return { chitchat: '闲聊', knowledge: '知识检索', fallback: '降级' }[route] || route
}

function toggleCite(mi, ci) {
  const key = `${mi}-${ci}`
  const s = new Set(openCites.value)
  s.has(key) ? s.delete(key) : s.add(key)
  openCites.value = s
}
function isCiteOpen(mi, ci) {
  return openCites.value.has(`${mi}-${ci}`)
}

async function scrollToBottom() {
  await nextTick()
  if (msgBox.value) msgBox.value.scrollTop = msgBox.value.scrollHeight
}

async function send() {
  const question = input.value.trim()
  if (!question || sending.value) return
  input.value = ''
  error.value = ''
  sending.value = true
  messages.value.push({ role: 'user', content: question })
  scrollToBottom()

  try {
    const payload = { question }
    if (activeSessionId.value) payload.sessionId = activeSessionId.value

    if (mode.value === 'agent') {
      const data = await api.agentChat(payload)
      activeSessionId.value = data.sessionId
      messages.value.push({
        role: 'assistant',
        content: data.answer,
        citations: data.citations || [],
        meta: {
          route: data.route,
          toolsUsed: data.toolsUsed || [],
          degraded: data.degraded,
          tookMs: data.tookMs
        }
      })
    } else {
      // 流式模式：meta → delta… → done
      const assistantMsg = { role: 'assistant', content: '', citations: [], streaming: true }
      messages.value.push(assistantMsg)
      await streamQa(payload, (event, data) => {
        if (event === 'meta') {
          try {
            const meta = JSON.parse(data)
            if (meta.sessionId) activeSessionId.value = meta.sessionId
            assistantMsg.citations = meta.citations || []
          } catch { /* meta 非 JSON 时忽略 */ }
        } else if (event === 'delta') {
          assistantMsg.content += data
          scrollToBottom()
        } else if (event === 'error') {
          assistantMsg.content += `\n[流式错误] ${data}`
        }
      })
      assistantMsg.streaming = false
    }
    loadSessions() // 刷新标题/排序，新会话出现在列表里
  } catch (e) {
    // 请求失败：把刚 push 的用户问题标出错误，但保留在界面上
    error.value = e.message
  } finally {
    sending.value = false
    scrollToBottom()
  }
}
</script>

<style scoped>
.layout { display: flex; height: 100vh; font-family: 'Microsoft YaHei', sans-serif; }

/* 侧边栏 */
.sidebar {
  width: 240px;
  background: #1e3a5f;
  color: #dbe6f3;
  display: flex;
  flex-direction: column;
}
.side-header { display: flex; align-items: center; justify-content: space-between; padding: 16px; }
.logo { font-size: 20px; font-weight: bold; }
.new-btn {
  background: #2d5a8a; color: #fff; border: none; border-radius: 6px;
  padding: 6px 10px; font-size: 12px; cursor: pointer;
}
.session-list { flex: 1; overflow-y: auto; padding: 0 8px; }
.session-item {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 12px; border-radius: 8px; cursor: pointer; margin-bottom: 4px;
}
.session-item:hover { background: #2a4a73; }
.session-item.active { background: #2d5a8a; }
.session-item .title { font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.session-item .del { color: #8aa5c4; padding: 0 4px; visibility: hidden; }
.session-item:hover .del { visibility: visible; }
.session-item .del:hover { color: #ff7875; }
.empty { color: #7d96b5; font-size: 12px; text-align: center; margin-top: 24px; padding: 0 12px; }
.side-footer {
  display: flex; justify-content: space-between; align-items: center; padding: 14px 16px;
  border-top: 1px solid #2a4a73; font-size: 13px; gap: 8px;
}
.side-footer a { color: #8ab4e0; text-decoration: none; }

/* 聊天区 */
.chat { flex: 1; display: flex; flex-direction: column; background: #f5f7fa; }
.chat-header {
  display: flex; align-items: center; gap: 8px;
  padding: 12px 20px; background: #fff; border-bottom: 1px solid #e8ecf1;
}
.mode-label { font-size: 13px; color: #666; }
.mode-btn {
  border: 1px solid #d0d7e2; background: #fff; border-radius: 6px;
  padding: 5px 12px; font-size: 13px; cursor: pointer; color: #555;
}
.mode-btn.on { background: #2d5a8a; border-color: #2d5a8a; color: #fff; }
.mode-hint { font-size: 12px; color: #aaa; }

.messages { flex: 1; overflow-y: auto; padding: 24px 32px; }
.welcome { text-align: center; color: #9ab; margin-top: 15vh; }
.welcome h2 { font-weight: normal; color: #5a7a9e; }

.msg { display: flex; margin-bottom: 18px; }
.msg.user { justify-content: flex-end; }
.msg .bubble { max-width: 72%; }
.msg.user .bubble {
  background: #2d5a8a; color: #fff; border-radius: 12px 12px 2px 12px; padding: 11px 14px;
}
.msg.assistant .bubble {
  background: #fff; border: 1px solid #e4e9f0; border-radius: 12px 12px 12px 2px; padding: 11px 14px;
}
.content { font-size: 14px; line-height: 1.7; white-space: pre-wrap; word-break: break-word; }
.cursor { color: #2d5a8a; animation: blink 0.8s infinite; }
@keyframes blink { 50% { opacity: 0; } }

.degraded-bar {
  background: #fffbe6; border: 1px solid #ffe58f; color: #ad6800;
  border-radius: 6px; padding: 6px 10px; font-size: 12px; margin-bottom: 8px;
}

.meta-row { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-top: 10px; }
.badge { font-size: 11px; border-radius: 4px; padding: 2px 8px; color: #fff; background: #8a94a6; }
.badge.knowledge { background: #2d5a8a; }
.badge.chitchat { background: #52a36c; }
.badge.fallback { background: #d48806; }
.tool-chip {
  font-size: 11px; background: #eef3f9; border: 1px solid #d5e0ee;
  color: #3d5a80; border-radius: 4px; padding: 2px 8px;
}
.tool-chip.fail { color: #d4380d; border-color: #ffccc7; background: #fff2f0; }
.took { font-size: 11px; color: #aaa; }

.citations { margin-top: 10px; border-top: 1px dashed #e0e6ee; padding-top: 8px; }
.cite-card {
  background: #f7f9fc; border: 1px solid #e4eaf2; border-radius: 6px;
  padding: 7px 10px; margin-bottom: 6px; cursor: pointer; font-size: 12px;
}
.cite-head { display: flex; align-items: center; gap: 8px; }
.cite-no { color: #2d5a8a; font-weight: bold; }
.cite-file { color: #333; }
.cite-info { color: #999; margin-left: auto; }
.cite-body { color: #666; margin-top: 6px; line-height: 1.6; white-space: pre-wrap; }

.error-bar {
  margin: 0 32px; padding: 8px 12px; background: #fff2f0; border: 1px solid #ffccc7;
  color: #d4380d; border-radius: 6px; font-size: 13px;
}

.input-bar {
  display: flex; gap: 10px; padding: 14px 32px 18px;
  background: #fff; border-top: 1px solid #e8ecf1;
}
textarea {
  flex: 1; resize: none; border: 1px solid #d0d7e2; border-radius: 8px;
  padding: 10px 12px; font-size: 14px; outline: none; font-family: inherit;
}
textarea:focus { border-color: #2d5a8a; }
.input-bar button {
  background: #2d5a8a; color: #fff; border: none; border-radius: 8px;
  padding: 0 22px; font-size: 14px; cursor: pointer;
}
.input-bar button:disabled { opacity: 0.55; cursor: not-allowed; }
</style>
