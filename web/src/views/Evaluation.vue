<template>
  <a-card title="检索评估">
    <!-- 使用说明（默认展开，可折叠） -->
    <a-collapse :bordered="false" style="background:transparent;margin-bottom:8px">
      <a-collapse-panel key="help" header="怎么用（三步）">
        <ol style="margin:0;padding-left:20px;line-height:2;color:#555;font-size:13px">
          <li><b>生成评估集</b>：点下方「从历史问答重新生成」——自动从真实问答记录提取（问题 + 回答引用过的知识块为期望答案），存 <code>data/eval/retrieval-eval.json</code> 可手工编辑。</li>
          <li><b>跑基线</b>：默认一组"当前配置"（参数留空 = 用设置页当前值），运行后得到当前检索水平的指标。</li>
          <li><b>批量对比</b>：点「+ 添加参数组」改参数（权重/阈值/topK/重排区间/单路多路），最多 8 组一次跑完，不污染设置页配置。</li>
        </ol>
        <div style="margin-top:8px;color:#888;font-size:12px">
          判断标准：recall@5 提升 = 期望内容排得更靠前；MRR 提升 = 第一个答案就是想要的；命中率 = 整体没有"完全找不着"的题。指标全面优于基线 → 去设置页保存该参数；某类题下跌 → 展开逐问题明细看是哪几题被挤掉。
        </div>
      </a-collapse-panel>
    </a-collapse>

    <!-- 评估集 -->
    <a-space wrap style="margin-bottom:16px">
      <span style="color:#666">评估集：</span>
      <a-tag color="blue">{{ evalSet.cases?.length || 0 }} 条</a-tag>
      <a-tag v-if="evalSet.generatedAt">{{ evalSet.generatedAt }}</a-tag>
      <a-input-number v-model:value="maxCases" :min="1" :max="500" style="width:90px" />
      <a-button :loading="generating" @click="doGenerate">从历史问答重新生成</a-button>
      <span style="color:#999;font-size:12px">评估集存 data/eval/retrieval-eval.json，可手动编辑</span>
    </a-space>

    <!-- 运行配置 -->
    <a-divider style="margin:12px 0">运行配置</a-divider>
    <a-space style="margin-bottom:12px">
      <span>K 值：</span>
      <a-select v-model:value="kList" mode="tags" style="width:200px" :open="false" placeholder="默认 5,10,20" />
      <span style="color:#999;font-size:12px">评估位次（recall@k）</span>
    </a-space>

    <div class="group-box" style="background:transparent;border:none;padding:0 0 4px">
      <span style="color:#999;font-size:12px;line-height:1.8">
        参数留空 = 不覆盖（用设置页当前值）；想对比哪项就填哪项。<b>multi 多路</b> = 模拟深度思考的多路检索（拆子问题合并），与 normal 对比可量化"深度思考检索增益"。<b>重排</b>开关打开会在评估中真实调用重排服务。
      </span>
    </div>
    <div v-for="(g, gi) in groups" :key="gi" class="group-box">
      <div class="group-head">
        <span class="group-title">参数组 {{ gi + 1 }}</span>
        <a-input v-model:value="g.name" placeholder="组名" style="width:140px" />
        <a-select v-model:value="g.mode" style="width:120px" :options="[
          { value: 'normal', label: '单路 normal' },
          { value: 'multi', label: '多路 multi' }
        ]" />
        <a-button v-if="groups.length > 1" type="text" danger size="small" @click="groups.splice(gi, 1)">删除</a-button>
      </div>
      <div class="group-params">
        <span class="p-item"><span class="p-label">向量权重</span><a-input-number v-model:value="g.vectorWeight" :step="0.05" style="width:90px" /></span>
        <span class="p-item"><span class="p-label">关键词权重</span><a-input-number v-model:value="g.keywordWeight" :step="0.05" style="width:90px" /></span>
        <span class="p-item"><span class="p-label">标题奖励</span><a-input-number v-model:value="g.titleBonus" :step="0.01" style="width:90px" /></span>
        <span class="p-item"><span class="p-label">向量阈值</span><a-input-number v-model:value="g.vecThreshold" :step="0.05" style="width:90px" /></span>
        <span class="p-item"><span class="p-label">关键词上限</span><a-input-number v-model:value="g.keywordLimit" :min="1" style="width:90px" /></span>
        <span class="p-item"><span class="p-label">topK</span><a-input-number v-model:value="g.topK" :min="1" style="width:90px" /></span>
        <span class="p-item"><span class="p-label">重排下限</span><a-input-number v-model:value="g.rerankMinHits" :min="0" style="width:80px" /></span>
        <span class="p-item"><span class="p-label">重排上限</span><a-input-number v-model:value="g.rerankMaxHits" :min="1" style="width:80px" /></span>
        <span class="p-item"><span class="p-label">重排</span><a-switch v-model:checked="g.rerankEnabled" /></span>
      </div>
    </div>
    <a-button type="dashed" block style="margin-bottom:16px" @click="addGroup">+ 添加参数组（对比组）</a-button>

    <a-space>
      <a-button type="primary" :loading="running" @click="doRun">
        <template #icon><thunderbolt-outlined /></template>
        {{ running ? '评估中...' : '运行评估' }}
      </a-button>
      <span v-if="result" style="color:#999;font-size:12px">耗时 {{ result.elapsedMs }}ms</span>
    </a-space>

    <!-- 结果 -->
    <template v-if="result">
      <a-alert style="margin-top:16px" :type="result.deprecatedCheck?.ok ? 'success' : 'warning'" show-icon
               :message="result.deprecatedCheck?.ok
                 ? '弃用过滤断言通过：已弃用文档的知识块未出现在任何命中'
                 : `弃用过滤断言失败：${result.deprecatedCheck?.violations?.length || 0} 处命中已弃用知识块`"
               :description="result.deprecatedCheck?.ok ? '' : (result.deprecatedCheck?.violations || []).join('；')" />

      <a-table style="margin-top:16px" size="small" row-key="name" :data-source="result.groups" :pagination="false"
               :columns="metricsColumns" />

      <a-collapse style="margin-top:16px" :bordered="false">
        <a-collapse-panel v-for="g in result.groups" :key="g.name" :header="`${g.name}（${g.mode}）— 逐问题明细`">
          <a-collapse accordion :bordered="false">
            <a-collapse-panel v-for="c in g.cases" :key="c.id"
                              :header="`${c.question} ｜ 期望 ${c.expected.length} 块 ｜ recall@${firstK}=${c.recallAtK ? c.recallAtK['recall@' + firstK] : '-'} MRR=${c.mrr}`">
              <div style="font-size:12px;color:#666;margin-bottom:6px">
                期望块：<a-tag v-for="e in c.expected" :key="e" style="margin-right:4px">{{ e }}</a-tag>
              </div>
              <div style="font-size:12px">
                实际命中 Top10：
                <div v-for="(h, hi) in c.hits.slice(0, 10)" :key="hi" style="padding:2px 0;border-bottom:1px dashed #f0f0f0">
                  <a-tag :color="c.expected.includes(h.knowledgeId) ? 'green' : 'default'" style="margin-right:6px">
                    #{{ hi + 1 }} {{ h.knowledgeId }}
                  </a-tag>
                  <span :style="c.expected.includes(h.knowledgeId) ? 'color:#52c41a' : 'color:#666'">{{ h.title || '(无标题)' }}</span>
                  <span style="color:#999;margin-left:8px">score={{ h.score?.toFixed?.(4) }}</span>
                </div>
              </div>
            </a-collapse-panel>
          </a-collapse>
        </a-collapse-panel>
      </a-collapse>
    </template>
  </a-card>
</template>

<script setup>
import { ref, computed, h, onMounted } from 'vue'
import { message, Tooltip } from 'ant-design-vue'
import { ThunderboltOutlined, QuestionCircleOutlined } from '@ant-design/icons-vue'
import { evalGenerate, getEvalSet, runEvaluation } from '../api'

// 指标列头：文字 + ? 图标（hover 看含义）
const metricTitle = (text, tip) =>
  h('span', null, [
    text,
    h(Tooltip, { title: tip }, () =>
      h(QuestionCircleOutlined, { style: 'color:#bbb;font-size:12px;margin-left:4px;cursor:help' }))
  ])

const evalSet = ref({ cases: [] })
const maxCases = ref(100)
const kList = ref([5, 10, 20])
const generating = ref(false)
const running = ref(false)
const result = ref(null)

// 默认一组"当前配置"（全部参数留空 = 不覆盖）
const defaultGroup = () => ({
  name: '当前配置', mode: 'normal',
  vectorWeight: null, keywordWeight: null, titleBonus: null,
  vecThreshold: null, keywordLimit: null, topK: null,
  rerankMinHits: null, rerankMaxHits: null, rerankEnabled: null
})
const groups = ref([defaultGroup()])
const addGroup = () => groups.value.push({ ...defaultGroup(), name: `对比组${groups.value.length}` })

const firstK = computed(() => (kList.value.length ? kList.value[0] : 5))

const metricsColumns = computed(() => {
  const ks = kList.value.length ? kList.value : [5, 10, 20]
  const cols = [
    { title: '参数组', dataIndex: 'name', width: 130 },
    { title: '模式', dataIndex: 'mode', width: 90 }
  ]
  ks.forEach(k => cols.push({
    title: metricTitle(`recall@${k}`, `期望知识块中，有多少比例出现在检索结果前 ${k} 名（越高越好，recall@5 提升 = 好内容排得更靠前）`),
    dataIndex: `metrics.recall@${k}`, width: 110
  }))
  cols.push({
    title: metricTitle('MRR', '第一个期望知识块排名的倒数（0~1）。越高 = 用户第一个看到的就是想要的，0.5 以上算不错'),
    dataIndex: 'metrics.MRR', width: 100
  })
  cols.push({
    title: metricTitle('命中率', '至少命中一个期望知识块的提问占比。越低说明"完全找不着"的题越多'),
    dataIndex: 'metrics.hitRate', width: 100
  })
  return cols
})

onMounted(loadSet)
async function loadSet() {
  try {
    const r = await getEvalSet()
    if (r.success) evalSet.value = r.data || { cases: [] }
  } catch (e) { /* 后端未部署评估接口时静默 */ }
}

async function doGenerate() {
  generating.value = true
  try {
    const r = await evalGenerate(maxCases.value)
    if (r.success) {
      evalSet.value = r.data
      message.success(`评估集已生成：${r.data?.cases?.length || 0} 条`)
    } else message.error(r.msg || '生成失败')
  } catch (e) { message.error(e.message || '生成失败') }
  finally { generating.value = false }
}

async function doRun() {
  if (!evalSet.value.cases?.length) {
    message.warning('评估集为空，请先"从历史问答重新生成"')
    return
  }
  running.value = true
  result.value = null
  try {
    const payload = {
      kList: kList.value.length ? kList.value.map(Number) : [5, 10, 20],
      groups: groups.value.map(g => ({
        name: g.name || '未命名',
        mode: g.mode,
        vectorWeight: g.vectorWeight, keywordWeight: g.keywordWeight, titleBonus: g.titleBonus,
        vecThreshold: g.vecThreshold, keywordLimit: g.keywordLimit, topK: g.topK,
        rerankMinHits: g.rerankMinHits, rerankMaxHits: g.rerankMaxHits, rerankEnabled: g.rerankEnabled
      }))
    }
    const r = await runEvaluation(payload)
    if (r.success) result.value = r.data
    else message.error(r.msg || '评估失败')
  } catch (e) { message.error(e.message || '评估失败') }
  finally { running.value = false }
}
</script>

<style scoped>
.group-box {
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 10px;
  background: #fafafa;
}
.group-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.group-title { font-weight: 600; color: #333; }
.group-params {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
}
.p-item { display: inline-flex; align-items: center; gap: 4px; }
.p-label { font-size: 12px; color: #666; white-space: nowrap; }
</style>
