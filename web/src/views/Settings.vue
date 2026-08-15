<template>
  <div>
    <a-alert type="info" show-icon style="margin-bottom:16px"
             message="base-url / api-key / 向量模型为只读配置（变更需修改 yml 或环境变量后重启服务）；其余参数保存后立即生效。鼠标悬停参数名旁的 ? 可查看调整该参数的影响。" />

    <a-spin :spinning="loading">
      <a-card title="智能问答模型" style="margin-bottom:16px">
        <a-form :label-col="{ span: 4 }" :wrapper-col="{ span: 14 }">
          <a-form-item>
            <template #label><a-tooltip :title="tips.chatModel" placement="top">模型名 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
            <a-input v-model:value="form.chat.model" placeholder="如 qwen3.7-flash-2026-07-15" />
          </a-form-item>
          <a-form-item>
            <template #label><a-tooltip :title="tips.temperature" placement="top">温度 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
            <a-input-number v-model:value="form.chat.temperature" :min="0" :max="2" :step="0.1" style="width:200px" />
          </a-form-item>
          <a-form-item>
            <template #label><a-tooltip :title="tips.systemPrompt" placement="top">System Prompt <question-circle-outlined class="tip-icon" /></a-tooltip></template>
            <a-textarea v-model:value="form.chat.systemPrompt" :rows="4"
                        placeholder="AI 助手的角色与回答风格（引用/图片/追问规则由系统固定，不可修改）" />
          </a-form-item>
          <a-form-item label="Base URL">
            <a-input :value="ro.chat.baseUrl" disabled />
          </a-form-item>
          <a-form-item label="API Key">
            <a-input :value="ro.chat.apiKey" disabled />
          </a-form-item>
        </a-form>
      </a-card>

      <a-card title="视觉模型（图片识别）" style="margin-bottom:16px">
        <a-form :label-col="{ span: 4 }" :wrapper-col="{ span: 14 }">
          <a-form-item>
            <template #label><a-tooltip :title="tips.visionModel" placement="top">模型名 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
            <a-input v-model:value="form.vision.model" placeholder="如 qwen3-vl:2b" />
          </a-form-item>
          <a-form-item>
            <template #label><a-tooltip :title="tips.visionPrompt" placement="top">识别提示词 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
            <a-textarea v-model:value="form.vision.prompt" :rows="3"
                        placeholder="图片描述提示词（50字内描述界面/元素）" />
          </a-form-item>
          <a-form-item label="Base URL">
            <a-input :value="ro.vision.baseUrl" disabled />
          </a-form-item>
          <a-form-item label="API Key">
            <a-input :value="ro.vision.apiKey" disabled />
          </a-form-item>
        </a-form>
      </a-card>

      <a-card title="向量模型（Embedding）">
        <a-form :label-col="{ span: 4 }" :wrapper-col="{ span: 14 }">
          <a-form-item label="模型名">
            <a-input :value="ro.embedding.model" disabled />
          </a-form-item>
        </a-form>
        <a-alert type="warning" show-icon style="margin:0 24px 16px"
                 message="向量模型不支持页面修改：更换模型后维度可能变化，历史向量全部失效，需删除知识库重新上传文档。" />
      </a-card>

      <a-card title="检索设置（混合检索权重）" style="margin-top:16px">
        <a-form :label-col="{ span: 4 }" :wrapper-col="{ span: 14 }">
          <a-form-item>
            <template #label><a-tooltip :title="tips.vectorWeight" placement="top">向量权重 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
            <a-input-number v-model:value="form.retrieval.vectorWeight" :min="0" :max="1" :step="0.05" style="width:200px" />
          </a-form-item>
          <a-form-item>
            <template #label><a-tooltip :title="tips.keywordWeight" placement="top">关键词权重 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
            <a-input-number v-model:value="form.retrieval.keywordWeight" :min="0" :max="1" :step="0.05" style="width:200px" />
          </a-form-item>
          <a-form-item>
            <template #label><a-tooltip :title="tips.titleBonus" placement="top">标题命中奖励 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
            <a-input-number v-model:value="form.retrieval.titleBonus" :min="0" :max="1" :step="0.05" style="width:200px" />
          </a-form-item>
        </a-form>
        <a-alert type="info" show-icon style="margin:0 24px 16px"
                 message="融合分 = 向量权重×向量相似度 + 关键词权重×命中率 + 标题命中奖励。保存后立即生效，可配合「检索调试」对比效果。" />
      </a-card>

      <a-card title="上下文与长度控制" style="margin-top:16px">
        <a-form :label-col="{ span: 4 }" :wrapper-col="{ span: 14 }">
          <a-form-item>
            <template #label><a-tooltip :title="tips.modelWindows" placement="top">模型窗口映射 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
            <a-input v-model:value="form.context.modelWindows"
                     placeholder="模型名=token,逗号分隔，如 qwen3=131072" />
          </a-form-item>
          <a-form-item>
            <template #label><a-tooltip :title="tips.defaultWindow" placement="top">默认窗口 token <question-circle-outlined class="tip-icon" /></a-tooltip></template>
            <a-input-number v-model:value="form.context.defaultWindowTokens" :min="1000" :step="1000" style="width:200px" />
          </a-form-item>
          <a-form-item>
            <template #label><a-tooltip :title="tips.safetyFactor" placement="top">窗口安全系数 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
            <a-input-number v-model:value="form.context.safetyFactor" :min="0.1" :max="1" :step="0.05" style="width:200px" />
          </a-form-item>
          <a-form-item>
            <template #label><a-tooltip :title="tips.costCap" placement="top">成本软上限 token <question-circle-outlined class="tip-icon" /></a-tooltip></template>
            <a-input-number v-model:value="form.context.costCapTokens" :min="0" :step="500" style="width:200px" />
          </a-form-item>
          <a-form-item>
            <template #label><a-tooltip :title="tips.maxOutput" placement="top">输出限制 token <question-circle-outlined class="tip-icon" /></a-tooltip></template>
            <a-input-number v-model:value="form.context.maxOutputTokens" :min="100" :step="100" style="width:200px" />
          </a-form-item>
          <a-form-item>
            <template #label><a-tooltip :title="tips.historyMax" placement="top">历史注入上限 token <question-circle-outlined class="tip-icon" /></a-tooltip></template>
            <a-input-number v-model:value="form.context.historyMaxTokens" :min="0" :step="100" style="width:200px" />
          </a-form-item>
          <a-form-item>
            <template #label><a-tooltip :title="tips.historyPerMsg" placement="top">单条历史截断字符 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
            <a-input-number v-model:value="form.context.historyPerMsgChars" :min="0" :step="20" style="width:200px" />
          </a-form-item>
          <a-form-item>
            <template #label><a-tooltip :title="tips.snippetWindow" placement="top">命中片段窗口字符 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
            <a-input-number v-model:value="form.context.snippetWindowChars" :min="0" :step="20" style="width:200px" />
          </a-form-item>
          <a-form-item>
            <template #label><a-tooltip :title="tips.maxContextHits" placement="top">知识块填充上限 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
            <a-input-number v-model:value="form.context.maxContextHits" :min="1" :max="30" style="width:200px" />
          </a-form-item>
        </a-form>
        <a-alert type="info" show-icon style="margin:0 24px 16px"
                 message="预算 = min(模型窗口×安全系数−输出限制, 成本上限)，知识块按相关度降序累积填充，超出预算的块自动被裁；每块只取命中关键词±窗口片段。历史单条截断+总量限制，[图片N] 标记自动剥离避免编号冲突。保存后立即生效。" />
      </a-card>

      <div style="margin-top:20px">
        <a-button type="primary" :loading="saving" @click="save">
          保存配置
        </a-button>
      </div>
    </a-spin>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { QuestionCircleOutlined } from '@ant-design/icons-vue'
import { getConfig, saveConfig } from '../api'

// 参数说明（hover ? 查看"调整该参数会影响什么"）
const tips = {
  chatModel: '切换回答所用的底层大模型。不同模型的能力、速度、成本差异很大；更换模型后请同步确认下方「模型窗口映射」包含该模型，否则按默认窗口计算上下文预算。',
  temperature: '控制回答的随机性（0~2）：越低回答越稳定、严谨、贴近资料原文（操作手册问答建议 0.2~0.4）；越高越有创造性，但也更容易偏离事实或编造内容。',
  systemPrompt: '定义 AI 的角色与回答风格，会注入每次问答的系统提示。改动立即影响所有回答的语气与行为；引用标注、配图、追问的硬性规则由系统固定，不可在此修改。',
  visionModel: '图片识别所用的多模态模型，影响文档截图、流程图的描述质量（描述越准，回答配图与检索召回越准）。',
  visionPrompt: '图片描述的要求（如提取关键文字/界面元素、说明流程要点）。改动影响图片描述的内容倾向，进而影响检索与配图准确性。',
  vectorWeight: '向量语义相似度在最终排序分中的占比。调高更侧重"意思相近"的匹配（适合口语化、换说法的提问）；过高可能引入字面无关但语义相近的块。',
  keywordWeight: '关键词精确命中在排序分中的占比。调高更侧重"字面命中"（适合操作手册中的专有名词、按钮名）；过高会漏掉语义相关但字面不同的内容。',
  titleBonus: '知识块标题命中关键词时的额外加分。调高更倾向返回标题相关的块；适合章节结构清晰的文档，但可能挤占正文命中的块。',
  modelWindows: '声明各模型的上下文窗口大小（token），格式"模型名=token"逗号分隔，按当前模型名的包含关系匹配。设置过大有超窗报错风险，过小会浪费模型能力。',
  defaultWindow: '当「模型窗口映射」未匹配到当前模型时使用的窗口大小兜底值。',
  safetyFactor: '上下文预算 = 窗口 × 安全系数 − 输出限制。系数越高单次可塞入更多知识块和历史，但越接近模型窗口上限；建议 0.6~0.8。',
  costCap: '单次请求输入 token 的硬上限（0=不限制）。用于控制成本：即使模型窗口很大，也最多塞这么多内容；设小会减少知识块数量、回答可能不完整。',
  maxOutput: '回答生成的最大 token 数。设太短回答会被截断；设太长增加成本与等待时间。',
  historyMax: '注入对话历史的 token 总上限。越大多轮上下文越完整（追问更准），但会挤压知识块的空间，且历史可能引入过时信息。',
  historyPerMsg: '每条历史消息保留的最大字符数，超出部分截断。控制历史占用的空间，保留最近轮次。',
  snippetWindow: '每个知识块只取"命中关键词 ± 该字符数"的片段送入上下文（0=整块塞入）。调大上下文信息更全但 token 消耗增大；调小更省 token 但可能丢失上下文导致理解偏差。',
  maxContextHits: '上下文最多塞入的知识块数量上限。调大可能引入相关度低的块稀释注意力；调小可能漏掉有价值的参考资料。'
}

const loading = ref(false)
const saving = ref(false)
const form = ref({ chat: { model: '', temperature: 0.3, systemPrompt: '' }, vision: { model: '', prompt: '' },
                    retrieval: { vectorWeight: 0.6, keywordWeight: 0.4, titleBonus: 0.1 },
                    context: { modelWindows: '', defaultWindowTokens: 32768, safetyFactor: 0.7, costCapTokens: 8000,
                               maxOutputTokens: 2000, historyMaxTokens: 1200, historyPerMsgChars: 200,
                               snippetWindowChars: 150, maxContextHits: 8 } })
const ro = ref({ chat: {}, vision: {}, embedding: {} })

onMounted(async () => {
  loading.value = true
  try {
    const r = await getConfig()
    if (r.success && r.data) {
      const d = r.data
      form.value.chat.model = d.chat?.model?.value || ''
      form.value.chat.temperature = Number(d.chat?.temperature?.value ?? 0.3)
      form.value.chat.systemPrompt = d.chat?.systemPrompt?.value || ''
      form.value.vision.model = d.vision?.model?.value || ''
      form.value.vision.prompt = d.vision?.prompt?.value || ''
      form.value.retrieval.vectorWeight = Number(d.retrieval?.vectorWeight?.value ?? 0.6)
      form.value.retrieval.keywordWeight = Number(d.retrieval?.keywordWeight?.value ?? 0.4)
      form.value.retrieval.titleBonus = Number(d.retrieval?.titleBonus?.value ?? 0.1)
      const ctx = d.context || {}
      form.value.context.modelWindows = ctx.modelWindows?.value || ''
      form.value.context.defaultWindowTokens = Number(ctx.defaultWindowTokens?.value ?? 32768)
      form.value.context.safetyFactor = Number(ctx.safetyFactor?.value ?? 0.7)
      form.value.context.costCapTokens = Number(ctx.costCapTokens?.value ?? 8000)
      form.value.context.maxOutputTokens = Number(ctx.maxOutputTokens?.value ?? 2000)
      form.value.context.historyMaxTokens = Number(ctx.historyMaxTokens?.value ?? 1200)
      form.value.context.historyPerMsgChars = Number(ctx.historyPerMsgChars?.value ?? 200)
      form.value.context.snippetWindowChars = Number(ctx.snippetWindowChars?.value ?? 150)
      form.value.context.maxContextHits = Number(ctx.maxContextHits?.value ?? 8)
      ro.value.chat = { baseUrl: d.chat?.baseUrl?.value || '', apiKey: d.chat?.apiKey?.value || '' }
      ro.value.vision = { baseUrl: d.vision?.baseUrl?.value || '', apiKey: d.vision?.apiKey?.value || '' }
      ro.value.embedding = { model: d.embedding?.model?.value || '' }
    }
  } catch (e) { message.error(e.message || '加载配置失败') }
  finally { loading.value = false }
})

const save = async () => {
  saving.value = true
  try {
    const r = await saveConfig({
      chat: { model: form.value.chat.model?.trim(), temperature: String(form.value.chat.temperature),
              systemPrompt: form.value.chat.systemPrompt?.trim() },
      vision: { model: form.value.vision.model?.trim(), prompt: form.value.vision.prompt?.trim() },
      retrieval: { vectorWeight: String(form.value.retrieval.vectorWeight),
                   keywordWeight: String(form.value.retrieval.keywordWeight),
                   titleBonus: String(form.value.retrieval.titleBonus) },
      context: { modelWindows: form.value.context.modelWindows?.trim(),
                 defaultWindowTokens: String(form.value.context.defaultWindowTokens),
                 safetyFactor: String(form.value.context.safetyFactor),
                 costCapTokens: String(form.value.context.costCapTokens),
                 maxOutputTokens: String(form.value.context.maxOutputTokens),
                 historyMaxTokens: String(form.value.context.historyMaxTokens),
                 historyPerMsgChars: String(form.value.context.historyPerMsgChars),
                 snippetWindowChars: String(form.value.context.snippetWindowChars),
                 maxContextHits: String(form.value.context.maxContextHits) }
    })
    if (r.success) message.success('配置已保存并生效')
    else message.error(r.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
  finally { saving.value = false }
}
</script>

<style scoped>
.tip-icon {
  color: #bbb;
  font-size: 12px;
  margin-left: 4px;
  cursor: help;
}
.tip-icon:hover {
  color: #1677ff;
}
</style>
