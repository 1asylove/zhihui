<template>
  <div class="page">
    <header class="top">
      <h1>数据看板</h1>
      <router-link class="back" to="/">← 返回问答</router-link>
    </header>

    <p v-if="error" class="err-bar">{{ error }}</p>

    <!-- 统计卡片 -->
    <div class="cards">
      <div class="card"><div class="num">{{ data.totalDocuments ?? '-' }}</div><div class="label">文档总数</div></div>
      <div class="card ok"><div class="num">{{ data.successDocuments ?? '-' }}</div><div class="label">已完成</div></div>
      <div class="card bad"><div class="num">{{ data.failedDocuments ?? '-' }}</div><div class="label">失败</div></div>
      <div class="card ing"><div class="num">{{ data.processingDocuments ?? '-' }}</div><div class="label">处理中</div></div>
      <div class="card"><div class="num">{{ data.chunkCount ?? '-' }}</div><div class="label">知识切片</div></div>
      <div class="card"><div class="num">{{ data.sessionCount ?? '-' }}</div><div class="label">问答会话</div></div>
    </div>

    <div class="panels">
      <!-- 文档类型分布：横向条形 -->
      <section class="panel">
        <h2>文档类型分布</h2>
        <template v-if="data.typeDistribution && data.typeDistribution.length">
          <div v-for="t in data.typeDistribution" :key="t.name" class="hbar-row">
            <span class="hbar-name">{{ t.name }}</span>
            <div class="hbar-track">
              <div class="hbar-fill" :style="{ width: pct(t.count, typeMax) + '%' }"></div>
            </div>
            <span class="hbar-count">{{ t.count }}</span>
          </div>
        </template>
        <p v-else class="empty">暂无数据</p>
      </section>

      <!-- 近 7 天上传趋势：纵向柱状 -->
      <section class="panel">
        <h2>近 7 天上传趋势</h2>
        <div v-if="data.recentUploads" class="vbars">
          <div v-for="d in data.recentUploads" :key="d.date" class="vbar-col">
            <span class="vbar-count">{{ d.count || '' }}</span>
            <div class="vbar" :style="{ height: barHeight(d.count) }"></div>
            <span class="vbar-date">{{ d.date.slice(5) }}</span>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api.js'

const data = ref({})
const error = ref('')

onMounted(async () => {
  try {
    data.value = await api.chartOverview()
  } catch (e) {
    error.value = e.message
  }
})

// 归一化：最大值占满，0 也要能算出宽度（避免空数组时 Math.max 得 -Infinity）
const typeMax = computed(() => Math.max(1, ...(data.value.typeDistribution || []).map(t => t.count)))
const dayMax = computed(() => Math.max(1, ...(data.value.recentUploads || []).map(d => d.count)))

function pct(n, max) { return Math.round(n / max * 100) }
function barHeight(n) { return Math.round(n / dayMax.value * 120) + 'px' }
</script>

<style scoped>
.page { max-width: 960px; margin: 0 auto; padding: 24px; font-family: 'Microsoft YaHei', sans-serif; }
.top { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
h1 { font-size: 20px; color: #1e3a5f; margin: 0; }
.back { color: #2d5a8a; text-decoration: none; font-size: 14px; }
.err-bar { color: #d4380d; font-size: 13px; }

.cards { display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px; }
.card {
  background: #fff; border: 1px solid #edf1f6; border-radius: 10px;
  padding: 14px 12px; text-align: center;
}
.card .num { font-size: 24px; font-weight: 600; color: #1e3a5f; }
.card .label { font-size: 12px; color: #8899ae; margin-top: 4px; }
.card.ok .num { color: #52a36c; }
.card.bad .num { color: #d4380d; }
.card.ing .num { color: #b08900; }

.panels { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-top: 12px; }
.panel { background: #fff; border: 1px solid #edf1f6; border-radius: 10px; padding: 16px; }
.panel h2 { font-size: 14px; color: #1e3a5f; margin: 0 0 14px; font-weight: 600; }
.empty { color: #aaa; font-size: 13px; text-align: center; padding: 24px 0; }

/* 横向条形图 */
.hbar-row { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.hbar-name { width: 44px; font-size: 12px; color: #5a7a9e; text-align: right; }
.hbar-track { flex: 1; height: 16px; background: #f0f4f9; border-radius: 4px; overflow: hidden; }
.hbar-fill { height: 100%; background: #2d5a8a; border-radius: 4px; transition: width 0.3s; }
.hbar-count { width: 32px; font-size: 12px; color: #1e3a5f; }

/* 纵向柱状图 */
.vbars { display: flex; align-items: flex-end; justify-content: space-around; height: 170px; }
.vbar-col { display: flex; flex-direction: column; align-items: center; justify-content: flex-end; height: 100%; }
.vbar-count { font-size: 11px; color: #1e3a5f; height: 14px; }
.vbar { width: 26px; background: #2d5a8a; border-radius: 4px 4px 0 0; transition: height 0.3s; }
.vbar-date { font-size: 11px; color: #8899ae; margin-top: 6px; }
</style>
