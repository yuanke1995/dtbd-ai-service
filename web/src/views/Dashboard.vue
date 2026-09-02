<template>
  <div>
    <a-card title="数据看板" style="margin-bottom:16px">
      <a-spin :spinning="analyticsLoading">
        <a-row :gutter="16">
          <a-col :span="6"><a-statistic title="问答总数" :value="summary.total || 0" /></a-col>
          <a-col :span="6"><a-statistic title="无命中率" :value="summary.noHitRate || 0" suffix="%" /></a-col>
          <a-col :span="6"><a-statistic title="反馈数" :value="fb.total || 0" /></a-col>
          <a-col :span="6"><a-statistic title="满意率" :value="fb.likeRate || 0" suffix="%" :value-style="{ color: (fb.likeRate || 0) >= 80 ? '#3f8600' : '#cf1322' }" /></a-col>
        </a-row>
        <a-row :gutter="16" style="margin-top:16px">
          <a-col :span="8"><a-statistic title="有帮助" :value="fb.likes || 0" /></a-col>
          <a-col :span="8"><a-statistic title="没帮助" :value="fb.dislikes || 0" /></a-col>
          <a-col :span="8"><a-statistic title="有引用标注的回答" :value="summary.citationRate || 0" suffix="%" /></a-col>
        </a-row>
      </a-spin>
    </a-card>

    <!-- 检索质量自动体检（定时按线上参数跑评估集；下滑时红黄灯提醒，早于用户投诉发现问题） -->
    <a-card size="small" style="margin-bottom:16px">
      <template #title>
        检索质量自动体检
        <span style="font-size:12px;color:#999;font-weight:400;margin-left:8px">定时按线上参数跑评估集，指标较上期下滑即预警</span>
      </template>
      <template #extra>
        <a-button size="small" type="primary" ghost :loading="checkLoading" @click="doAutoCheck">立即体检</a-button>
      </template>
      <a-spin :spinning="checkLoading">
        <template v-if="report">
          <div style="margin-bottom:10px">
            <a-tag :color="checkStatusColor" style="margin-right:8px">{{ checkStatusText }}</a-tag>
            <span style="color:#666;font-size:13px">{{ report.message || '' }}</span>
            <span v-if="report.runTime" style="color:#bbb;font-size:12px;margin-left:12px">{{ report.runTime }}</span>
          </div>
          <a-table v-if="checkMetricRows.length" :data-source="checkMetricRows" :columns="checkCols" size="small"
                   row-key="name" :pagination="false" :locale="{ emptyText: '暂无指标' }" />
        </template>
        <a-empty v-else description="暂无体检记录（生成评估集后次日自动产生，或点右上角「立即体检」）" />
      </a-spin>
    </a-card>

    <a-row :gutter="16">
      <a-col :span="12">
        <a-card title="热门问题 TOP10" size="small">
          <a-table :data-source="summary.topQuestions || []" :columns="qCols" size="small"
                   row-key="question" :pagination="false" :loading="analyticsLoading"
                   :locale="{ emptyText: '暂无数据' }">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'action'">
                <a-button type="link" size="small" :loading="addingSuggested === record.question"
                          @click="addRecommended(record.question)">设为推荐</a-button>
              </template>
            </template>
          </a-table>
        </a-card>
      </a-col>
      <a-col :span="12">
        <a-card title="无命中问题 TOP10（建议补充知识库）" size="small">
          <a-table :data-source="summary.noHitQuestions || []" :columns="noHitCols" size="small"
                   row-key="question" :pagination="false" :loading="analyticsLoading"
                   :locale="{ emptyText: '暂无数据' }" />
        </a-card>
      </a-col>
    </a-row>

    <!-- 差评样本 · 反馈回流闭环 -->
    <a-card size="small" style="margin-top:16px">
      <template #title>差评样本（反馈回流）</template>
      <template #extra>
        <a-button size="small" @click="loadBadCases">刷新</a-button>
      </template>
      <a-table :data-source="badCases" :columns="badCols" size="small" row-key="messageId"
               :loading="badLoading"
               :pagination="badCases.length > 10 ? { pageSize: 10 } : false"
               :locale="{ emptyText: '暂无差评样本（👍 微笑曲线良好）' }">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'action'">
            <a-tooltip :title="record.knowledgeIds?.length ? '加入评估集（问题→引用过的知识块），防止后续调参丢召回' : '该轮回答无引用块，无法加入评估集'">
              <a-button type="link" size="small" :disabled="!record.knowledgeIds?.length"
                        :loading="evalAdding === record.messageId" @click="addToEval(record)">加入评估集</a-button>
            </a-tooltip>
            <a-button type="link" size="small" @click="openBadCaseAdd(record)">补知识块</a-button>
          </template>
        </template>
      </a-table>
    </a-card>

    <a-card title="知识库缺口管理（无命中问题汇总）" size="small" style="margin-top:16px">
      <template #extra>
        <a-button size="small" @click="loadUnmatched">刷新</a-button>
      </template>
      <a-table :data-source="unmatchedList" :columns="unmatchedCols" size="small"
               row-key="question" :pagination="{ pageSize: 10 }" :loading="unmatchedLoading"
               :locale="{ emptyText: '暂无数据' }" >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'action'">
            <a-button type="link" size="small" @click="openAdd(record)">入库</a-button>
          </template>
        </template>
      </a-table>
    </a-card>

    <!-- 入库弹窗 -->
    <a-modal v-model:open="addVisible" title="补充知识块" :footer="null" width="640" destroy-on-close>
      <a-form layout="vertical">
        <a-form-item label="问题（自动作为标题，推荐作为检索关键词）">
          <a-input v-model:value="addForm.title" disabled />
        </a-form-item>
        <a-form-item label="回答内容（请填写准确的回答内容或操作步骤）" required>
          <a-textarea v-model:value="addForm.content" :rows="6" placeholder="请根据你的知识补充准确的回答内容、操作步骤或说明" />
        </a-form-item>
        <a-form-item label="关联文档 ID（可选，留空则依靠关键词检索）">
          <a-input v-model:value="addForm.docId" placeholder="c_ai_document 中的文档 ID" />
        </a-form-item>
        <a-form-item>
          <a-button type="primary" :loading="addLoading" @click="submitAdd">确认入库</a-button>
          <a-button style="margin-left:8px" @click="addVisible = false">取消</a-button>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, h } from 'vue'
import { message } from 'ant-design-vue'
import { getAnalytics, getUnmatchedQuestions, createKnowledge, addSuggested, getBadCases, addEvalCase, getEvalLastReport, runEvalAutoCheck } from '../api'

const qCols = [
  { title: '问题', dataIndex: 'question', key: 'question', ellipsis: true },
  { title: '次数', dataIndex: 'count', key: 'count', width: 80 },
  { title: '操作', key: 'action', width: 100 }
]

// 热门问题一键加入推荐池（欢迎页展示）
const addingSuggested = ref('')
const addRecommended = async question => {
  addingSuggested.value = question
  try {
    const r = await addSuggested(question)
    if (r.success) message.success('已加入推荐问题池，用户侧欢迎页将展示')
    else message.error(r.msg || '加入失败')
  } catch (e) { message.error(e.message || '加入失败') }
  finally { addingSuggested.value = '' }
}

// 无命中问题 TOP10 带操作（跳转至缺口管理看全部）
const noHitCols = [
  { title: '问题', dataIndex: 'question', key: 'question', ellipsis: true },
  { title: '次数', dataIndex: 'count', key: 'count', width: 80 }
]

// 知识库缺口管理表格列
const unmatchedCols = [
  { title: '问题', dataIndex: 'question', key: 'question', ellipsis: true },
  { title: '次数', dataIndex: 'count', key: 'count', width: 80 },
  { title: '最近提问', dataIndex: 'latestTime', key: 'latestTime', width: 160 },
  { title: '操作', key: 'action', width: 100 }
]

const data = ref({})
const summary = computed(() => data.value || {})
const fb = computed(() => summary.value.feedback || {})

// 加载态（统计/TOP10 同源于 getAnalytics，避免首屏 0 值闪烁）
const analyticsLoading = ref(false)
const unmatchedLoading = ref(false)
const badLoading = ref(false)

// ==================== 检索质量自动体检 ====================
const report = ref(null)
const checkLoading = ref(false)
const checkCols = [
  { title: '指标', dataIndex: 'name', key: 'name', width: 160 },
  { title: '本期', dataIndex: 'value', key: 'value', width: 100 },
  { title: '较上期', key: 'delta', width: 110, customRender: ({ record }) => {
    if (record.delta === null || record.delta === undefined) return '—'
    const v = Number(record.delta)
    const color = v < -0.01 ? '#cf1322' : (v > 0.01 ? '#3f8600' : '#999')
    return h('span', { style: { color } }, (v > 0 ? '▲ +' : v < 0 ? '▼ ' : '') + v.toFixed(1) + '%')
  } },
  { title: '评价', key: 'note', width: 90, customRender: ({ record }) => {
    if (record.delta === null || record.delta === undefined) return '—'
    const v = Number(record.delta)
    return h('span', { style: { color: v < 0 ? '#cf1322' : '#3f8600', fontSize: 12 } }, v < 0 ? '下滑' : (v > 0 ? '提升' : '持平'))
  } }
]
const checkMetricRows = computed(() => {
  const m = report.value?.metrics
  const d = report.value?.deltaPct || {}
  if (!m || typeof m !== 'object') return []
  return Object.entries(m).map(([name, v]) => {
    const num = Number(v)
    return { name: name === 'MRR' ? name : name + (name.startsWith('recall') ? '（前' + name.replace('recall@', '') + '名命中率）' : '（命中率）'), value: (num * 100).toFixed(1) + '%', delta: d[name] === undefined ? null : Number(d[name]) }
  })
})
const checkStatusText = computed(() => ({
  ok: '正常', decline: '⚠ 检索质量下滑', empty: '待评估集', error: '执行异常'
}[report.value?.status] || '—'))
const checkStatusColor = computed(() => ({
  ok: 'green', decline: 'red', empty: 'orange', error: 'red'
}[report.value?.status] || 'default'))
const doAutoCheck = async () => {
  checkLoading.value = true
  try {
    const r = await runEvalAutoCheck()
    if (r.success) {
      report.value = r.data || null
      if (report.value?.status === 'decline') message.warning('体检完成：检测到检索质量下滑，建议到「检索评估」页对比调参')
      else message.success('体检完成')
    } else message.error(r.msg || '体检失败')
  } catch (e) { message.error(e.message || '体检失败') }
  finally { checkLoading.value = false }
}

// 知识库缺口管理
const unmatchedList = ref([])

// 入库弹窗
const addVisible = ref(false)
const addLoading = ref(false)
const addForm = ref({ title: '', content: '', docId: '' })

// ==================== 差评样本 · 反馈回流 ====================
const badCases = ref([])
const evalAdding = ref('')
const badCols = [
  { title: '问题', dataIndex: 'question', key: 'question', ellipsis: true },
  { title: '回答摘要', dataIndex: 'answer', key: 'answer', ellipsis: true },
  { title: '反馈说明', dataIndex: 'feedbackText', key: 'feedbackText', ellipsis: true,
    customRender: ({ text }) => text || '—' },
  { title: '时间', dataIndex: 'time', key: 'time', width: 160,
    customRender: ({ text }) => text ? String(text).replace('T', ' ').slice(0, 16) : '—' },
  { title: '操作', key: 'action', width: 170 }
]
const loadBadCases = async () => {
  badLoading.value = true
  try {
    const r = await getBadCases()
    if (r.success) badCases.value = r.data || []
  } catch (e) { /* 静默，看板不因差评卡片失败整体报错 */ }
  finally { badLoading.value = false }
}
// 差评回流①：加入评估集（问题 → 当轮引用过的知识块），防后续调参丢召回
const addToEval = async record => {
  evalAdding.value = record.messageId
  try {
    const r = await addEvalCase(record.question, record.knowledgeIds || [])
    if (r.success) {
      if (r.data?.added) message.success('已加入评估集，可在「检索评估」页验证调参不丢召回')
      else message.info(r.data?.reason || '评估集已存在相同问题')
    } else message.error(r.msg || '加入失败')
  } catch (e) { message.error(e.message || '加入失败') }
  finally { evalAdding.value = '' }
}
// 差评回流②：补知识块（预填问题为标题、回答摘要为初稿，管理员修改后入库）
const openBadCaseAdd = record => {
  openAdd({ question: record.question, answer: record.answer, docId: '' })
}

const openAdd = record => {
  addForm.value = { title: record.question, content: record.answer || '', docId: record.docId || '' }
  addVisible.value = true
}

const submitAdd = async () => {
  const f = addForm.value
  if (!f.content.trim()) {
    message.warning('请填写回答内容')
    return
  }
  addLoading.value = true
  try {
    const r = await createKnowledge(f.title, f.content.trim(), f.docId.trim() || null)
    if (r.success) {
      message.success('知识块已创建（含向量召回）')
      addVisible.value = false
      loadUnmatched()  // M4：入库后刷新缺口列表
    } else {
      message.error(r.msg || '创建失败')
    }
  } catch (e) {
    message.error(e.message || '创建失败')
  } finally {
    addLoading.value = false
  }
}

const loadUnmatched = async () => {
  unmatchedLoading.value = true
  try {
    const r = await getUnmatchedQuestions()
    if (r.success) {
      unmatchedList.value = Array.isArray(r.data) ? r.data : []
    }
  } catch (e) { /* 静默 */ }
  finally { unmatchedLoading.value = false }
}

onMounted(async () => {
  loadBadCases()
  // 自动体检上次报告（失败静默：首次使用/后端未启动定时前不打扰）
  getEvalLastReport().then(r => { if (r.success) report.value = r.data || null }).catch(() => {})
  analyticsLoading.value = true
  try {
    const r = await getAnalytics()
    if (r.success) {
      data.value = r.data || {}
      const logs = r.data
      if (logs && typeof logs.total === 'number') data.value = logs
    }
  } catch (e) { message.error('加载看板失败: ' + (e.message || '')) }
  finally { analyticsLoading.value = false }
  // 加载无命中问题列表
  loadUnmatched()
})
</script>
