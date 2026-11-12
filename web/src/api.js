// 统一请求封装：自动带 token、拆 Result 信封、401 跳登录
const BASE = ''

export class ApiError extends Error {
  constructor(code, message) {
    super(message)
    this.code = code
  }
}

async function request(path, { method = 'GET', body } = {}) {
  const headers = { 'Content-Type': 'application/json' }
  const token = localStorage.getItem('token')
  if (token) headers.Authorization = `Bearer ${token}`

  const resp = await fetch(BASE + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body)
  })

  if (resp.status === 401) {
    localStorage.removeItem('token')
    window.location.href = '/login'
    throw new ApiError(401, '登录已过期，请重新登录')
  }

  const json = await resp.json().catch(() => null)
  if (!json) throw new ApiError(resp.status, `HTTP ${resp.status}`)
  if (json.code !== 200) throw new ApiError(json.code, json.message || '请求失败')
  return json.data
}

export const api = {
  login: (username, password) => request('/auth/login', { method: 'POST', body: { username, password } }),
  listSessions: () => request('/sessions'),
  sessionMessages: (id) => request(`/sessions/${id}/messages`),
  deleteSession: (id) => request(`/sessions/${id}`, { method: 'DELETE' }),
  agentChat: (payload) => request('/agent/chat', { method: 'POST', body: payload }),
  listDocuments: (page = 1, size = 10) => request(`/documents?page=${page}&size=${size}`),
  deleteDocument: (id) => request(`/documents/${id}`, { method: 'DELETE' }),
  chartOverview: () => request('/charts/overview')
}

// 上传文档：multipart 不能走 request()（它会强制 JSON 头）
// 注意：后端上传是同步的，解析+切片+向量化全做完才返回，大文件可能要等几十秒
export async function uploadDocument(file) {
  const token = localStorage.getItem('token')
  const form = new FormData()
  form.append('file', file)
  const resp = await fetch('/documents/upload', {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` },
    body: form
  })
  if (resp.status === 401) {
    localStorage.removeItem('token')
    window.location.href = '/login'
    throw new ApiError(401, '登录已过期，请重新登录')
  }
  const json = await resp.json().catch(() => null)
  if (!json) throw new ApiError(resp.status, `HTTP ${resp.status}`)
  if (json.code !== 200) throw new ApiError(json.code, json.message || '上传失败')
  return json.data
}

// 流式问答（/qa/stream，SSE）。onEvent(eventName, dataString)
export async function streamQa(payload, onEvent) {
  const token = localStorage.getItem('token')
  const resp = await fetch(BASE + '/qa/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`
    },
    body: JSON.stringify(payload)
  })

  if (resp.status === 401) {
    localStorage.removeItem('token')
    window.location.href = '/login'
    throw new ApiError(401, '登录已过期，请重新登录')
  }
  const contentType = resp.headers.get('content-type') || ''
  if (!resp.ok || !contentType.includes('text/event-stream')) {
    // 建流前失败时后端返回普通 JSON 错误
    const json = await resp.json().catch(() => null)
    throw new ApiError(json?.code ?? resp.status, json?.message || `HTTP ${resp.status}`)
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    // SSE 事件以空行分隔
    let idx
    while ((idx = buffer.indexOf('\n\n')) >= 0) {
      const raw = buffer.slice(0, idx)
      buffer = buffer.slice(idx + 2)
      let event = 'message'
      const dataLines = []
      for (const line of raw.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        else if (line.startsWith('data:')) dataLines.push(line.slice(5))
      }
      onEvent(event, dataLines.join('\n'))
    }
  }
}
