<template>
  <a-card title="检索评估">
    <!-- 使用说明（默认展开，可折叠） -->
    <a-collapse :bordered="false" style="background:transparent;margin-bottom:8px">
      <a-collapse-panel key="help" header="怎么用（三步）">
        <ol style="margin:0;padding-left:20px;line-height:2;color:#555;font-size:13px">
          <li><b>生成评估集</b>：点「从历史问答重新生成」——自动从真实问答记录提取（问题 + 回答引用过的知识块为期望答案），存 <code>data/eval/retrieval-eval.json</code> 可手工编辑；问答里的差评案例也会自动补充进来。</li>
          <li><b>一键体检</b>：用当前配置跑一遍评估集，直接告诉你检索健不健康、哪几题落空——不需要理解任何参数。</li>
          <li><b>对比调优</b>：点「+ 添加参数组」选预设（关键词优先/向量优先/向量+重排/多路）或自定义，跑完看对比结论；更好的组点「应用此组」直接写入配置并生效，不用去设置页手抄。</li>
        </ol>
        <div style="margin-top:8px;color:#888;font-size:12px">
          指标含义：recall@K = 期望内容排进前 K 名的比例（找得全不全）；MRR = 第一条期望内容排名的倒数（排得靠不靠前，0.5 以上算不错）；命中率 = 没有完全落空的题占比。评估集来自"当时回答引用过的块"（弱监督），<b>看相对变化比看绝对值有意义</b>。
        </div>
      </a-collapse-panel>
    </a-collapse>

    <!-- 体检结论（一键体检后显示，可关闭） -->
    <a-alert v-if="health" style="margin-bottom:16px" :type="health.level" show-icon closable
             :message="health.title" :description="health.description" @close="health = null" />

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
        参数留空 = 不覆盖（用设置页当前值，<b>空输入框的灰色占位就是当前值</b>）；想对比哪项就填哪项。<b>multi 多路</b> = 模拟深度思考的多路检索（拆子问题合并），与 normal 对比可量化"深度思考检索增益"。<b>重排</b>开关打开会在评估中真实调用重排服务。
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
        <span class="p-item"><span class="p-label">向量权重</span><a-input-number v-model:value="g.vectorWeight" :step="0.05" :placeholder="cur('retrieval.vectorWeight')" style="width:90px" /></span>
        <span class="p-item"><span class="p-label">关键词权重</span><a-input-number v-model:value="g.keywordWeight" :step="0.05" :placeholder="cur('retrieval.keywordWeight')" style="width:90px" /></span>
        <span class="p-item"><span class="p-label">标题奖励</span><a-input-number v-model:value="g.titleBonus" :step="0.01" :placeholder="cur('retrieval.titleBonus')" style="width:90px" /></span>
        <span class="p-item"><span class="p-label">向量阈值</span><a-input-number v-model:value="g.vecThreshold" :step="0.05" :placeholder="cur('retrieval.vecThreshold')" style="width:90px" /></span>
        <span class="p-item"><span class="p-label">关键词上限</span><a-input-number v-model:value="g.keywordLimit" :min="1" :placeholder="cur('retrieval.keywordLimit')" style="width:90px" /></span>
        <span class="p-item"><span class="p-label">topK</span><a-input-number v-model:value="g.topK" :min="1" :placeholder="cur('retrieval.vectorTopK')" style="width:90px" /></span>
        <span class="p-item"><span class="p-label">重排下限</span><a-input-number v-model:value="g.rerankMinHits" :min="0" :placeholder="cur('rerank.minHits')" style="width:80px" /></span>
        <span class="p-item"><span class="p-label">重排上限</span><a-input-number v-model:value="g.rerankMaxHits" :min="1" :placeholder="cur('rerank.maxHits')" style="width:80px" /></span>
        <span class="p-item"><span class="p-label">重排</span>
          <a-switch :checked="g.rerankEnabled ?? cur('rerank.enabled') === 'true'"
                    title="未手动拨动 = 跟随线上当前配置；拨动后为该组显式覆盖"
                    @change="v => g.rerankEnabled = v" />
          <span v-if="g.rerankEnabled === null || g.rerankEnabled === undefined" class="cur-hint">跟随当前（{{ cur('rerank.enabled') === 'true' ? '开' : '关' }}）</span>
        </span>
      </div>
    </div>
    <!-- 添加参数组：预设一键生成，免去手填数字 -->
    <a-dropdown :trigger="['click']">
      <a-button type="dashed" block style="margin-bottom:16px">
        + 添加参数组（选预设，最多 8 组）<down-outlined style="font-size:10px;margin-left:4px" />
      </a-button>
      <template #overlay>
        <a-menu @click="({ key }) => addPreset(key)">
          <a-menu-item key="kw">关键词优先（向量 0.3 / 关键词 0.7）</a-menu-item>
          <a-menu-item key="vec">向量优先（向量 0.7 / 关键词 0.3）</a-menu-item>
          <a-menu-item key="rerank">向量 + 重排（0.7/0.3，开重排 5~20）</a-menu-item>
          <a-menu-item key="multi">多路模拟（深度思考拆子问题，不填参数）</a-menu-item>
          <a-menu-item key="blank">空白自定义</a-menu-item>
        </a-menu>
      </template>
    </a-dropdown>

    <a-space>
      <a-button type="primary" :loading="running" @click="doHealth">
        <template #icon><thunderbolt-outlined /></template>
        一键体检（当前配置）
      </a-button>
      <a-button :loading="running" @click="doRun">{{ running ? '评估中...' : '运行对比评估' }}</a-button>
      <span v-if="result" style="color:#999;font-size:12px">耗时 {{ result.elapsedMs }}ms</span>
    </a-space>

    <!-- 结果 -->
    <template v-if="result">
      <a-alert style="margin-top:16px" :type="result.deprecatedCheck?.ok ? 'success' : 'warning'" show-icon
               :message="result.deprecatedCheck?.ok
                 ? '弃用过滤断言通过：已弃用文档的知识块未出现在任何命中'
                 : `弃用过滤断言失败：${result.deprecatedCheck?.violations?.length || 0} 处命中已弃用知识块`"
               :description="result.deprecatedCheck?.ok ? '' : (result.deprecatedCheck?.violations || []).join('；')" />

      <!-- 对比结论（自动生成，第一组为基线） -->
      <a-alert v-if="conclusion" style="margin-top:12px" type="info" show-icon :message="conclusion" />

      <a-table style="margin-top:12px" size="small" row-key="name" :data-source="result.groups" :pagination="false"
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
import { message, Modal, Tooltip } from 'ant-design-vue'
import { ThunderboltOutlined, QuestionCircleOutlined, DownOutlined } from '@ant-design/icons-vue'
import { evalGenerate, getEvalSet, runEvaluation, saveConfig, getConfig } from '../api'

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
const health = ref(null)   // 一键体检结论 {level, title, description}
const curCfg = ref({})     // 当前线上配置（扁平 key→value），用于空参数框占位回显

// 当前值占位：留空参数框灰色显示设置页当前值，语义仍是"留空=不覆盖"
const cur = k => curCfg.value[k] ?? ''
const flattenConfig = d => {
  const flat = {}
  for (const [g, keys] of Object.entries(d || {})) {
    for (const [k, meta] of Object.entries(keys || {})) {
      if (meta && typeof meta === 'object' && 'value' in meta) flat[`${g}.${k}`] = meta.value
    }
  }
  return flat
}
async function loadCur() {
  try {
    const r = await getConfig()
    if (r.success) curCfg.value = flattenConfig(r.data)
  } catch (e) { /* 静默：占位为空不影响功能 */ }
}

// 默认一组"当前配置"（全部参数留空 = 不覆盖）
const defaultGroup = () => ({
  name: '当前配置', mode: 'normal',
  vectorWeight: null, keywordWeight: null, titleBonus: null,
  vecThreshold: null, keywordLimit: null, topK: null,
  rerankMinHits: null, rerankMaxHits: null, rerankEnabled: null
})
const groups = ref([defaultGroup()])

// 预设参数组：一键生成对比组（后端上限 8 组）
const PRESETS = {
  kw:     { name: '关键词优先', mode: 'normal', vectorWeight: 0.3, keywordWeight: 0.7 },
  vec:    { name: '向量优先', mode: 'normal', vectorWeight: 0.7, keywordWeight: 0.3 },
  rerank: { name: '向量+重排', mode: 'normal', vectorWeight: 0.7, keywordWeight: 0.3, rerankEnabled: true, rerankMinHits: 5, rerankMaxHits: 20 },
  multi:  { name: '多路模拟', mode: 'multi' },
  blank:  { name: '' }
}
const addPreset = key => {
  if (groups.value.length >= 8) { message.warning('参数组最多 8 组'); return }
  const p = PRESETS[key] || {}
  const g = { ...defaultGroup(), ...structuredClone(p) }
  if (!g.name) g.name = `对比组${groups.value.length}`
  groups.value.push(g)
}

const firstK = computed(() => (kList.value.length ? kList.value[0] : 5))

// 基线 = 结果里的第一组（通常是"当前配置"）
const baselineGroup = computed(() => result.value?.groups?.[0] || null)

// 指标单元格：不依赖 dataIndex 嵌套取值（antd4 下 metrics.recall@5 解析不到，text 为空），
// 直接从 record.metrics[key] 取数；非基线组显示相对基线的 ↑↓ 差值（绿=更好）
const metricCell = key => ({ record }) => {
  const v = Number(record?.metrics?.[key])
  const text = Number.isNaN(v) ? '-' : String(v)
  const base = Number(baselineGroup.value?.metrics?.[key])
  if (!baselineGroup.value || record === baselineGroup.value || Number.isNaN(base)) return text
  const d = v - base
  if (Math.abs(d) < 1e-6) return text
  const color = d > 0 ? '#52c41a' : '#ff4d4f'
  return h('span', null, [
    text,
    h('span', { style: `color:${color};font-size:11px;margin-left:4px;font-weight:600` }, `${d > 0 ? '↑' : '↓'}${Math.abs(d).toFixed(2)}`)
  ])
}

// 应用列：有可写参数的组才显示「应用此组」
const APPLY_FIELDS = [
  ['vectorWeight', 'retrieval', 'vectorWeight', '向量权重'],
  ['keywordWeight', 'retrieval', 'keywordWeight', '关键词权重'],
  ['titleBonus', 'retrieval', 'titleBonus', '标题奖励'],
  ['vecThreshold', 'retrieval', 'vecThreshold', '向量阈值'],
  ['keywordLimit', 'retrieval', 'keywordLimit', '关键词上限'],
  ['topK', 'retrieval', 'vectorTopK', '向量topK'],
  ['rerankMinHits', 'rerank', 'minHits', '重排下限'],
  ['rerankMaxHits', 'rerank', 'maxHits', '重排上限']
]
const hasApplyable = g => {
  const p = g?.params || g || {}
  return APPLY_FIELDS.some(([f]) => p[f] !== null && p[f] !== undefined) ||
    (p.rerankEnabled !== null && p.rerankEnabled !== undefined)
}

const applyGroup = g => {
  const p = g?.params || g || {}
  const payload = {}
  const desc = []
  for (const [f, prefix, key, label] of APPLY_FIELDS) {
    const v = p[f]
    if (v === null || v === undefined) continue
    ;(payload[prefix] = payload[prefix] || {})[key] = String(v)
    desc.push(`${label}=${v}`)
  }
  if (p.rerankEnabled !== null && p.rerankEnabled !== undefined) {
    ;(payload.rerank = payload.rerank || {}).enabled = String(p.rerankEnabled)
    desc.push(`重排开关=${p.rerankEnabled ? '开' : '关'}`)
  }
  if (!desc.length) return
  const modeNote = g.mode === 'multi' ? '（该组为 multi 模拟模式，模式本身不写入配置，仅应用数值参数）' : ''
  Modal.confirm({
    title: `应用「${g.name}」到线上配置？`,
    content: `将保存：${desc.join('、')}，保存后立即生效${modeNote}`,
    okText: '应用', cancelText: '取消',
    onOk: async () => {
      try {
        const r = await saveConfig(payload)
        if (r.success) message.success(`「${g.name}」已应用并生效`)
        else message.error(r.msg || '应用失败')
      } catch (e) { message.error(e.message || '应用失败') }
    }
  })
}

const metricsColumns = computed(() => {
  const ks = kList.value.length ? kList.value : [5, 10, 20]
  const cols = [
    {
      title: '参数组', dataIndex: 'name', width: 130,
      customRender: ({ record }) => record === baselineGroup.value
        ? h('span', null, [record.name, h('span', { style: 'color:#999;font-size:11px;margin-left:4px' }, '（基线）')])
        : String(record.name ?? '')
    },
    { title: '模式', dataIndex: 'mode', width: 90 }
  ]
  ks.forEach(k => cols.push({
    title: metricTitle(`recall@${k}`, `期望知识块中，有多少比例出现在检索结果前 ${k} 名（越高越好，recall@5 提升 = 好内容排得更靠前）`),
    key: `recall@${k}`, width: 110, customRender: metricCell(`recall@${k}`)
  }))
  cols.push({
    title: metricTitle('MRR', '第一个期望知识块排名的倒数（0~1）。越高 = 用户第一个看到的就是想要的，0.5 以上算不错'),
    key: 'MRR', width: 100, customRender: metricCell('MRR')
  })
  cols.push({
    title: metricTitle('命中率', '至少命中一个期望知识块的提问占比（越高越好）'),
    key: 'hitRate', width: 100, customRender: metricCell('hitRate')
  })
  cols.push({
    title: '操作', key: 'apply', width: 90,
    customRender: ({ record }) => hasApplyable(record)
      ? h('a', { style: 'color:#1677ff;cursor:pointer;font-size:12px', onClick: () => applyGroup(record) }, '应用此组')
      : h('span', { style: 'color:#ccc;font-size:12px' }, '—')
  })
  return cols
})

// 对比结论（自动生成）：有组全面不差于基线且至少一项更优时给出建议
const conclusion = computed(() => {
  const gs = result.value?.groups || []
  if (gs.length < 2) return ''
  const base = gs[0]
  const key = `recall@${firstK.value}`
  const num = (g, k) => Number(g.metrics?.[k] ?? 0)
  const better = gs.slice(1).filter(g =>
    num(g, key) >= num(base, key) && num(g, 'MRR') >= num(base, 'MRR') && num(g, 'hitRate') >= num(base, 'hitRate') &&
    (num(g, key) > num(base, key) || num(g, 'MRR') > num(base, 'MRR')))
  if (!better.length) return `结论：暂无参数组全面优于「${base.name}」，可再试其他预设，或先看逐问题明细定位落空题目。`
  const d = (num(better[0], key) - num(base, key)).toFixed(2)
  return `结论：${better.map(g => `「${g.name}」`).join('、')}各项指标不差于基线且至少一项更优（recall@${firstK.value} +${d}），可点「应用此组」直接生效；应用前建议展开逐问题明细确认没有个别题被挤掉。`
})

const buildPayload = gs => ({
  kList: kList.value.length ? kList.value.map(Number) : [5, 10, 20],
  groups: gs.map(g => ({
    name: g.name || '未命名',
    mode: g.mode,
    vectorWeight: g.vectorWeight, keywordWeight: g.keywordWeight, titleBonus: g.titleBonus,
    vecThreshold: g.vecThreshold, keywordLimit: g.keywordLimit, topK: g.topK,
    rerankMinHits: g.rerankMinHits, rerankMaxHits: g.rerankMaxHits, rerankEnabled: g.rerankEnabled
  }))
})

onMounted(() => { loadSet(); loadCur() })
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

async function runEval(payload) {
  running.value = true
  result.value = null
  try {
    const r = await runEvaluation(payload)
    if (r.success) {
      result.value = r.data
      return r.data
    }
    message.error(r.msg || '评估失败')
  } catch (e) { message.error(e.message || '评估失败') }
  finally { running.value = false }
  return null
}

async function doRun() {
  if (!evalSet.value.cases?.length) {
    message.warning('评估集为空，请先"从历史问答重新生成"')
    return
  }
  await runEval(buildPayload(groups.value))
}

// 一键体检：不动参数组编辑器，用当前配置跑一遍并给出红绿灯结论
async function doHealth() {
  if (!evalSet.value.cases?.length) {
    message.warning('评估集为空，请先"从历史问答重新生成"')
    return
  }
  const data = await runEval(buildPayload([{ name: '当前配置', mode: 'normal' }]))
  if (!data) return
  health.value = buildHealth(data)
}

const buildHealth = data => {
  const g = data.groups?.[0]
  if (!g) return null
  const k = firstK.value
  const key = `recall@${k}`
  const num = x => Number(x ?? 0)
  const cases = g.cases || []
  const miss = cases.filter(c => num(c.recallAtK?.[key]) === 0)
  const low = cases.filter(c => num(c.recallAtK?.[key]) > 0 && num(c.recallAtK?.[key]) < 0.5)
  const hitRate = num(g.metrics?.hitRate)
  const rK = num(g.metrics?.[key])
  const worst = [...miss, ...low].slice(0, 5)
    .map(c => `${c.question}（recall@${k}=${num(c.recallAtK?.[key]).toFixed(2)}，MRR=${num(c.mrr).toFixed(2)}）`)
  const ok = hitRate >= 0.9 && rK >= 0.7
  const title = ok
    ? `体检通过：命中率 ${(hitRate * 100).toFixed(0)}%，${key} ${rK.toFixed(2)}，MRR ${num(g.metrics?.MRR).toFixed(2)} —— 检索整体健康`
    : `发现 ${miss.length} 题完全落空、${low.length} 题相关度偏低（命中率 ${(hitRate * 100).toFixed(0)}%，${key} ${rK.toFixed(2)}，MRR ${num(g.metrics?.MRR).toFixed(2)}）`
  const description = [
    `共评估 ${cases.length} 题。`,
    worst.length ? `重点检查：${worst.join('；')}` : '未发现明显落空问题。',
    ok ? '' : '改善建议：添加参数组做对比（预设一键生成），或到「文档管理」检查这些问题的相关知识块是否缺失/停用。'
  ].filter(Boolean).join(' ')
  return { level: ok ? 'success' : 'warning', title, description }
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
.cur-hint { font-size: 11px; color: #bbb; white-space: nowrap; }
</style>
