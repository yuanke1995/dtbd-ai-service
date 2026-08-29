<template>
  <div>
    <a-card title="数据看板" style="margin-bottom:16px">
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
    </a-card>

    <a-row :gutter="16">
      <a-col :span="12">
        <a-card title="热门问题 TOP10" size="small">
          <a-table :data-source="summary.topQuestions || []" :columns="qCols" size="small"
                   row-key="question" :pagination="false" :locale="{ emptyText: '暂无数据' }">
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
                   row-key="question" :pagination="false" :locale="{ emptyText: '暂无数据' }" />
        </a-card>
      </a-col>
    </a-row>

    <a-card title="知识库缺口管理（无命中问题汇总）" size="small" style="margin-top:16px">
      <template #extra>
        <a-button size="small" @click="loadUnmatched">刷新</a-button>
      </template>
      <a-table :data-source="unmatchedList" :columns="unmatchedCols" size="small"
               row-key="question" :pagination="{ pageSize: 10 }" :locale="{ emptyText: '暂无数据' }" >
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
import { ref, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { getAnalytics, getUnmatchedQuestions, createKnowledge, addSuggested } from '../api'

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

// 知识库缺口管理
const unmatchedList = ref([])

// 入库弹窗
const addVisible = ref(false)
const addLoading = ref(false)
const addForm = ref({ title: '', content: '', docId: '' })

const openAdd = record => {
  addForm.value = { title: record.question, content: '', docId: '' }
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
  try {
    const r = await getUnmatchedQuestions()
    if (r.success) {
      unmatchedList.value = Array.isArray(r.data) ? r.data : []
    }
  } catch (e) { /* 静默 */ }
}

onMounted(async () => {
  try {
    const r = await getAnalytics()
    if (r.success) {
      data.value = r.data || {}
      const logs = r.data
      if (logs && typeof logs.total === 'number') data.value = logs
    }
  } catch (e) { message.error('加载看板失败: ' + (e.message || '')) }
  // 加载无命中问题列表
  loadUnmatched()
})
</script>
