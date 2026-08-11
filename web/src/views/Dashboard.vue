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
        <a-col :span="8"><a-statistic title="👍 有帮助" :value="fb.likes || 0" /></a-col>
        <a-col :span="8"><a-statistic title="👎 没帮助" :value="fb.dislikes || 0" /></a-col>
        <a-col :span="8"><a-statistic title="有引用标注的回答" :value="summary.citationRate || 0" suffix="%" /></a-col>
      </a-row>
    </a-card>

    <a-row :gutter="16">
      <a-col :span="12">
        <a-card title="热门问题 TOP10" size="small">
          <a-table :data-source="summary.topQuestions || []" :columns="qCols" size="small"
                   row-key="question" :pagination="false" :locale="{ emptyText: '暂无数据' }" />
        </a-card>
      </a-col>
      <a-col :span="12">
        <a-card title="无命中问题 TOP10（建议补充知识库）" size="small">
          <a-table :data-source="summary.noHitQuestions || []" :columns="qCols" size="small"
                   row-key="question" :pagination="false" :locale="{ emptyText: '暂无数据' }" />
        </a-card>
      </a-col>
    </a-row>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { getAnalytics } from '../api'

const qCols = [
  { title: '问题', dataIndex: 'question', key: 'question', ellipsis: true },
  { title: '次数', dataIndex: 'count', key: 'count', width: 80 }
]

const data = ref({})
const summary = computed(() => data.value || {})
const fb = computed(() => summary.value.feedback || {})

onMounted(async () => {
  try {
    const r = await getAnalytics()
    if (r.success) {
      data.value = r.data || {}
      // 补引用率统计（后端未直接给，用 total 与有引用推算：先展示 0，后续后端补）
      const logs = r.data
      if (logs && typeof logs.total === 'number') data.value = logs
    }
  } catch (e) { message.error('加载看板失败: ' + (e.message || '')) }
})
</script>
