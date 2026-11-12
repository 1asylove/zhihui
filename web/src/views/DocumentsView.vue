<template>
  <div class="page">
    <header class="top">
      <h1>文档管理</h1>
      <router-link class="back" to="/">← 返回问答</router-link>
    </header>

    <!-- 上传区：点击或拖拽 -->
    <div
      class="dropzone"
      :class="{ over: dragOver, disabled: uploading }"
      @click="pickFile"
      @dragover.prevent="dragOver = true"
      @dragleave="dragOver = false"
      @drop.prevent="onDrop"
    >
      <p v-if="!uploading">点击选择或拖拽文件到这里（txt / pdf / docx）</p>
      <p v-else>正在上传并处理「{{ uploadingName }}」…（解析+切片+向量化，请稍候）</p>
      <input ref="fileInput" type="file" hidden multiple @change="onPick" />
    </div>
    <p v-if="uploadMsg" class="msg" :class="{ err: uploadErr }">{{ uploadMsg }}</p>

    <!-- 文档列表 -->
    <table class="doc-table">
      <thead>
        <tr>
          <th>ID</th><th>文件名</th><th>类型</th><th>大小</th><th>状态</th><th>上传时间</th><th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="d in docs" :key="d.id">
          <td>{{ d.id }}</td>
          <td class="name">{{ d.fileName }}</td>
          <td>{{ d.fileType }}</td>
          <td>{{ fmtSize(d.fileSize) }}</td>
          <td>
            <span class="status" :class="d.status">{{ statusLabel(d.status) }}</span>
            <span v-if="d.status === 'FAILED' && d.errorMsg" class="err-msg" :title="d.errorMsg">?</span>
          </td>
          <td>{{ fmtTime(d.createTime) }}</td>
          <td><button class="del-btn" @click="onDelete(d)">删除</button></td>
        </tr>
        <tr v-if="!docs.length"><td colspan="7" class="empty">暂无文档</td></tr>
      </tbody>
    </table>

    <div class="pager">
      <button :disabled="page <= 1" @click="goto(page - 1)">上一页</button>
      <span>{{ page }} / {{ totalPages || 1 }}（共 {{ total }} 篇）</span>
      <button :disabled="page >= totalPages" @click="goto(page + 1)">下一页</button>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { api, uploadDocument } from '../api.js'

const docs = ref([])
const page = ref(1)
const totalPages = ref(1)
const total = ref(0)
const dragOver = ref(false)
const uploading = ref(false)
const uploadingName = ref('')
const uploadMsg = ref('')
const uploadErr = ref(false)
const fileInput = ref(null)

onMounted(() => load(1))

async function load(p) {
  try {
    const data = await api.listDocuments(p, 10)
    docs.value = data.records
    page.value = data.current
    totalPages.value = data.pages
    total.value = data.total
  } catch (e) {
    uploadMsg.value = e.message
    uploadErr.value = true
  }
}

function goto(p) { load(p) }

function pickFile() {
  if (!uploading.value) fileInput.value.click()
}
function onPick(e) {
  doUpload([...e.target.files])
  e.target.value = ''
}
function onDrop(e) {
  dragOver.value = false
  doUpload([...e.dataTransfer.files])
}

async function doUpload(files) {
  if (!files.length || uploading.value) return
  uploading.value = true
  uploadMsg.value = ''
  uploadErr.value = false
  let ok = 0
  const fails = []
  for (const f of files) {
    uploadingName.value = f.name
    try {
      const doc = await uploadDocument(f)
      // 接口 200 但文档本身可能解析失败（status=FAILED）
      if (doc.status === 'FAILED') fails.push(`${f.name}（解析失败：${doc.errorMsg || '未知原因'}）`)
      else ok++
    } catch (e) {
      fails.push(`${f.name}（${e.message}）`)
    }
  }
  uploading.value = false
  if (ok && !fails.length) uploadMsg.value = `成功上传 ${ok} 个文件`
  else if (ok) uploadMsg.value = `成功 ${ok} 个；失败：${fails.join('；')}`
  else { uploadMsg.value = `上传失败：${fails.join('；')}`; uploadErr.value = true }
  if (fails.length) uploadErr.value = true
  load(1)
}

async function onDelete(d) {
  if (!confirm(`删除文档「${d.fileName}」？会同时删除其切片和向量。`)) return
  try {
    await api.deleteDocument(d.id)
    load(page.value)
  } catch (e) {
    uploadMsg.value = e.message // 403（无 doc:delete 权限）也会在这里提示
    uploadErr.value = true
  }
}

function statusLabel(s) {
  return { PENDING: '待处理', PARSING: '解析中', SUCCESS: '已完成', FAILED: '失败' }[s] || s
}
function fmtSize(n) {
  if (n == null) return '-'
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
  return (n / 1024 / 1024).toFixed(1) + ' MB'
}
function fmtTime(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : '-'
}
</script>

<style scoped>
.page { max-width: 960px; margin: 0 auto; padding: 24px; font-family: 'Microsoft YaHei', sans-serif; }
.top { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
h1 { font-size: 20px; color: #1e3a5f; margin: 0; }
.back { color: #2d5a8a; text-decoration: none; font-size: 14px; }

.dropzone {
  border: 2px dashed #b8c7da; border-radius: 12px; background: #f7fafd;
  padding: 36px; text-align: center; color: #5a7a9e; cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}
.dropzone.over { border-color: #2d5a8a; background: #eaf2fb; }
.dropzone.disabled { opacity: 0.6; cursor: wait; }
.dropzone p { margin: 0; font-size: 14px; }

.msg { font-size: 13px; color: #2b7a3d; margin: 10px 2px; }
.msg.err { color: #d4380d; }

.doc-table { width: 100%; border-collapse: collapse; background: #fff; margin-top: 8px; }
.doc-table th, .doc-table td {
  border-bottom: 1px solid #edf1f6; padding: 10px 12px; font-size: 13px; text-align: left;
}
.doc-table th { color: #8899ae; font-weight: normal; background: #fafbfd; }
.doc-table .name { max-width: 320px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.empty { text-align: center; color: #aaa; padding: 32px 0; }

.status { font-size: 12px; border-radius: 4px; padding: 2px 8px; color: #fff; background: #8a94a6; }
.status.SUCCESS { background: #52a36c; }
.status.FAILED { background: #d4380d; }
.status.PARSING { background: #2d5a8a; }
.status.PENDING { background: #b08900; }
.err-msg { color: #d4380d; margin-left: 6px; cursor: help; }

.del-btn {
  border: 1px solid #ffccc7; background: #fff; color: #d4380d;
  border-radius: 6px; padding: 4px 10px; font-size: 12px; cursor: pointer;
}
.del-btn:hover { background: #fff2f0; }

.pager { display: flex; align-items: center; gap: 14px; justify-content: center; margin-top: 16px; font-size: 13px; color: #778; }
.pager button {
  border: 1px solid #d0d7e2; background: #fff; border-radius: 6px;
  padding: 5px 14px; font-size: 13px; cursor: pointer;
}
.pager button:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
