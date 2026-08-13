<template>
  <div>
    <a-alert type="info" show-icon style="margin-bottom:16px"
             message="base-url / api-key / 向量模型为只读配置（变更需修改 yml 或环境变量后重启服务）；模型名与温度保存后立即生效。" />

    <a-spin :spinning="loading">
      <a-card title="智能问答模型" style="margin-bottom:16px">
        <a-form :label-col="{ span: 4 }" :wrapper-col="{ span: 14 }">
          <a-form-item label="模型名">
            <a-input v-model:value="form.chat.model" placeholder="如 qwen3.7-flash-2026-07-15" />
          </a-form-item>
          <a-form-item label="温度">
            <a-input-number v-model:value="form.chat.temperature" :min="0" :max="2" :step="0.1" style="width:200px" />
          </a-form-item>
          <a-form-item label="System Prompt">
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
          <a-form-item label="模型名">
            <a-input v-model:value="form.vision.model" placeholder="如 qwen3-vl:2b" />
          </a-form-item>
          <a-form-item label="识别提示词">
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
          <a-form-item label="向量权重">
            <a-input-number v-model:value="form.retrieval.vectorWeight" :min="0" :max="1" :step="0.05" style="width:200px" />
          </a-form-item>
          <a-form-item label="关键词权重">
            <a-input-number v-model:value="form.retrieval.keywordWeight" :min="0" :max="1" :step="0.05" style="width:200px" />
          </a-form-item>
          <a-form-item label="标题命中奖励">
            <a-input-number v-model:value="form.retrieval.titleBonus" :min="0" :max="1" :step="0.05" style="width:200px" />
          </a-form-item>
        </a-form>
        <a-alert type="info" show-icon style="margin:0 24px 16px"
                 message="融合分 = 向量权重×向量相似度 + 关键词权重×命中率 + 标题命中奖励。向量权重偏重语义理解、关键词权重偏重精确匹配，保存后立即生效，可配合「检索调试」对比效果。" />
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
import { getConfig, saveConfig } from '../api'

const loading = ref(false)
const saving = ref(false)
const form = ref({ chat: { model: '', temperature: 0.3, systemPrompt: '' }, vision: { model: '', prompt: '' },
                    retrieval: { vectorWeight: 0.6, keywordWeight: 0.4, titleBonus: 0.1 } })
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
                   titleBonus: String(form.value.retrieval.titleBonus) }
    })
    if (r.success) message.success('配置已保存并生效')
    else message.error(r.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
  finally { saving.value = false }
}
</script>
