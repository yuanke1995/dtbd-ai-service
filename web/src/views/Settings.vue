<template>
  <div>
    <a-alert type="info" show-icon style="margin-bottom:16px"
             message="base-url / api-key / 向量模型为只读配置（变更需修改 yml 或环境变量后重启服务）；其余参数保存后立即生效。鼠标悬停参数名旁的 ? 可查看调整该参数的影响。" />

    <!-- 分区锚点：点击展开并平滑定位到对应配置分组 -->
    <div class="cfg-anchor">
      <template v-for="a in anchors" :key="a.key">
        <a :class="{ 'anchor-active': currentAnchor === a.key }" href="javascript:void(0)" @click="jumpTo(a.key)">{{ a.label }}</a>
      </template>
    </div>

    <a-spin :spinning="loading">
      <a-collapse v-model:activeKey="activeKeys" :bordered="false" class="cfg-collapse">

        <a-collapse-panel key="chat" header="智能问答模型" :id="'cfg-anchor-chat'">
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
            <a-form-item>
              <template #label><a-tooltip :title="tips.historyRounds" placement="top">多轮记忆轮数 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.chat.historyRounds" :min="0" :max="20" style="width:200px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.remainTokenFloor" placement="top">上下文保留下限 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.chat.remainTokenFloor" :min="0" :step="100" style="width:200px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.truncateFallbackChars" placement="top">截断兜底字符 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.chat.truncateFallbackChars" :min="0" :step="50" style="width:200px" />
            </a-form-item>
            <a-form-item label="Base URL">
              <a-input :value="ro.chat.baseUrl" disabled />
            </a-form-item>
            <a-form-item label="API Key">
              <a-input :value="ro.chat.apiKey" disabled />
            </a-form-item>
          </a-form>
        </a-collapse-panel>

        <a-collapse-panel key="vision" :id="'cfg-anchor-vision'" header="视觉模型（图片识别）">
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
            <a-form-item>
              <template #label><a-tooltip :title="tips.visionConcurrency" placement="top">图片描述并发 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.vision.concurrency" :min="1" :max="16" style="width:200px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.userImageConcurrency" placement="top">用户图片并发 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.vision.userImageConcurrency" :min="1" :max="16" style="width:200px" />
            </a-form-item>
            <a-form-item label="Base URL">
              <a-input :value="ro.vision.baseUrl" disabled />
            </a-form-item>
            <a-form-item label="API Key">
              <a-input :value="ro.vision.apiKey" disabled />
            </a-form-item>
          </a-form>
        </a-collapse-panel>

        <a-collapse-panel key="chunk" :id="'cfg-anchor-chunk'" header="文档解析（上传上限/分块/图片）">
          <a-form :label-col="{ span: 4 }" :wrapper-col="{ span: 14 }">
            <a-form-item>
              <template #label><a-tooltip :title="tips.uploadMaxSize" placement="top">上传大小上限 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.upload.maxFileSizeMB" :min="1" :max="1024" :step="50" style="width:200px" />
              <span style="margin-left:12px;color:#999;font-size:12px">MB，保存即生效</span>
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.maxChunks" placement="top">最大知识块数 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.chunk.maxChunks" :min="0" :step="500" style="width:200px" />
              <span style="margin-left:12px;color:#999;font-size:12px">0=不限制</span>
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.maxImages" placement="top">最多提取图片 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.chunk.maxImages" :min="0" :step="20" style="width:200px" />
              <span style="margin-left:12px;color:#999;font-size:12px">0=不限制</span>
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.parseConcurrency" placement="top">解析并发数 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.chunk.concurrency" :min="1" :max="8" style="width:200px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.ocrMinText" placement="top">PDF 扫描件阈值 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.chunk.ocrMinText" :min="0" :step="5" style="width:200px" />
              <span style="margin-left:12px;color:#999;font-size:12px">页文本少于该长度触发 OCR</span>
            </a-form-item>
          </a-form>
          <a-alert type="info" show-icon style="margin:0 24px 16px"
                   message="上传大小上限保存即生效（新上传按新限制校验）；分块/图片上限对超大文档保护：知识块数超上限截断入库，图片数超上限不再提取描述。保存后对新上传文档立即生效。" />
        </a-collapse-panel>

        <a-collapse-panel key="embedding" :id="'cfg-anchor-embedding'" header="向量模型（Embedding）">
          <a-form :label-col="{ span: 4 }" :wrapper-col="{ span: 14 }">
            <a-form-item label="模型名">
              <a-input :value="ro.embedding.model" disabled />
            </a-form-item>
          </a-form>
          <a-alert type="warning" show-icon style="margin:0 24px 16px"
                   message="向量模型不支持页面修改：更换模型后维度可能变化，历史向量全部失效，需删除知识库重新上传文档。" />
        </a-collapse-panel>

        <a-collapse-panel key="retrieval" :id="'cfg-anchor-retrieval'" header="检索设置（混合检索权重 + 重排）">
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
            <a-form-item>
              <template #label><a-tooltip :title="tips.vecThreshold" placement="top">向量阈值 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.retrieval.vecThreshold" :min="0" :max="1" :step="0.05" style="width:200px" />
              <span style="margin-left:12px;color:#999;font-size:12px">相似度归一化基准/下限</span>
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.keywordLimit" placement="top">关键词召回上限 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.retrieval.keywordLimit" :min="1" :step="5" style="width:200px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.retrievalTimeout" placement="top">检索超时(ms) <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.retrieval.searchTimeoutMs" :min="500" :step="500" style="width:200px" />
              <span style="margin-left:12px;color:#999;font-size:12px">关键词/总检索超时</span>
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.positionBonus" placement="top">位置奖励 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.retrieval.positionBonus" :min="0" :max="0.5" :step="0.01" style="width:200px" />
              <span style="margin-left:12px;color:#999;font-size:12px">首块 / 前段奖励</span>
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.rerankEnabled" placement="top">启用重排 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-switch v-model:checked="form.retrieval.rerank.enabled" :loading="rerankChecking" @change="onRerankEnabledChange" />
              <span style="margin-left:12px;color:#999;font-size:12px">开启前自动校验服务可用性</span>
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.rerankBaseUrl" placement="top">服务地址 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input v-model:value="form.retrieval.rerank.baseUrl" placeholder="http://localhost:7997" style="width:320px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.rerankModel" placement="top">模型名 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input v-model:value="form.retrieval.rerank.model" placeholder="BAAI/bge-reranker-v2-m3" style="width:320px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.rerankTimeout" placement="top">超时(ms) <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.retrieval.rerank.timeoutMillis" :min="1000" :step="1000" style="width:200px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.rerankMinHits" placement="top">候选区间 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.retrieval.rerank.minHits" :min="1" style="width:90px" />
              <span style="margin:0 6px;color:#999">~</span>
              <a-input-number v-model:value="form.retrieval.rerank.maxHits" :min="2" style="width:90px" />
              <span style="margin-left:8px;color:#999;font-size:12px">候选数在此区间才重排</span>
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.rerankFailCooldown" placement="top">失败冷却(ms) <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.retrieval.rerank.failCooldownMs" :min="1000" :step="5000" style="width:200px" />
            </a-form-item>
          </a-form>
          <a-alert type="info" show-icon style="margin:0 24px 16px"
                   message="融合分 = 向量权重×向量相似度 + 关键词权重×命中率 + 标题命中奖励。保存后立即生效，可配合「检索调试」对比效果。" />
          <a-alert type="info" show-icon style="margin:0 24px 16px"
                   message="重排：OpenAI 兼容 /v1/rerank 服务（sentence-transformers CrossEncoder，bge-reranker-v2-m3）。未启动或不可用时自动回退融合分排序，不影响正常问答。" />
        </a-collapse-panel>

        <a-collapse-panel key="context" :id="'cfg-anchor-context'" header="上下文与长度控制">
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
        </a-collapse-panel>

        <a-collapse-panel key="deepReasoning" :id="'cfg-anchor-deepReasoning'" header="深度思考设置">
          <a-form :label-col="{ span: 4 }" :wrapper-col="{ span: 14 }">
            <a-form-item>
              <template #label><a-tooltip :title="tips.drEnabled" placement="top">总开关 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-switch v-model:checked="form.deepReasoning.enabled" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.drMode" placement="top">思考模式 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-select v-model:value="form.deepReasoning.thinkingMode" style="width:220px" :options="[
                { value: 'model', label: 'model（透传 enable_thinking，从 reasoning_content 提取）' },
                { value: 'prompt', label: 'prompt（提示词引导输出到正文）' }
              ]" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.drEnableThinking" placement="top">透传 enable_thinking <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-switch v-model:checked="form.deepReasoning.enableThinking" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.drPrompt" placement="top">思考引导提示词 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-textarea v-model:value="form.deepReasoning.prompt" :rows="6"
                          placeholder="引导模型先深度思考、末尾输出 <search>精化query|子问题1|子问题2</search> 检索计划" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.drSearchTag" placement="top">检索计划标签名 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input v-model:value="form.deepReasoning.searchTag" style="width:200px" placeholder="search" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.drMaxSub" placement="top">最大子问题数 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.deepReasoning.maxSubQueries" :min="0" :max="8" style="width:200px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.drMultiRetrieval" placement="top">多路并行检索 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-switch v-model:checked="form.deepReasoning.multiRetrieval" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.drTimeout" placement="top">思考阶段超时 ms <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.deepReasoning.timeoutMillis" :min="1000" :step="1000" style="width:200px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.drMaxTokens" placement="top">思考输出上限 token <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.deepReasoning.maxThinkingTokens" :min="0" :step="100" style="width:200px" />
            </a-form-item>
          </a-form>
          <a-alert type="info" show-icon style="margin:0 24px 16px"
                   message="深度思考：AI 先流式展示思维链（回答上方折叠面板），思考末尾输出 <search> 检索计划（精化 query + 子问题），多路并行检索合并后回答。默认 maxThinkingTokens=0 不设上限（qwen 思考模式设 max_tokens 会空输出）。失败自动降级为普通回答。" />
        </a-collapse-panel>

      </a-collapse>

      <!-- 悬浮保存按钮：固定在右下角，无需滚动到底部 -->
      <div style="position:fixed; right:24px; bottom:24px; z-index:100; margin:0">
        <a-button type="primary" :loading="saving" @click="save"
                  style="box-shadow:0 4px 12px rgba(0,0,0,0.18)">
          <template #icon><save-outlined /></template>
          保存配置
        </a-button>
      </div>
    </a-spin>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { message } from 'ant-design-vue'
import { QuestionCircleOutlined, SaveOutlined } from '@ant-design/icons-vue'
import { getConfig, saveConfig, checkRerank } from '../api'

// 折叠面板：默认展开常用分组（chat / retrieval / context / deepReasoning），vision / embedding 收起
const activeKeys = ref(['chat', 'retrieval', 'context', 'deepReasoning'])

// 分区锚点：点击展开 + 平滑滚动定位
const anchors = [
  { key: 'chat', label: '智能问答' },
  { key: 'vision', label: '视觉模型' },
  { key: 'chunk', label: '文档解析' },
  { key: 'embedding', label: '向量模型' },
  { key: 'retrieval', label: '检索设置' },
  { key: 'context', label: '上下文控制' },
  { key: 'deepReasoning', label: '深度思考' }
]
const currentAnchor = ref('')
let anchorObserver = null
const jumpTo = (key) => {
  currentAnchor.value = key
  // 展开该分组（若收起）
  if (!activeKeys.value.includes(key)) activeKeys.value = [...activeKeys.value, key]
  // 平滑滚动到分组
  requestAnimationFrame(() => {
    document.getElementById('cfg-anchor-' + key)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  })
}

// 参数说明（hover ? 查看"调整该参数会影响什么"）
const tips = {
  chatModel: '切换回答所用的底层大模型。不同模型的能力、速度、成本差异很大；更换模型后请同步确认下方「模型窗口映射」包含该模型，否则按默认窗口计算上下文预算。',
  temperature: '控制回答的随机性（0~2）：越低回答越稳定、严谨、贴近资料原文（操作手册问答建议 0.2~0.4）；越高越有创造性，但也更容易偏离事实或编造内容。',
  systemPrompt: '定义 AI 的角色与回答风格，会注入每次问答的系统提示。改动立即影响所有回答的语气与行为；引用标注、配图、追问的硬性规则由系统固定，不可在此修改。',
  visionModel: '图片识别所用的多模态模型，影响文档截图、流程图的描述质量（描述越准，回答配图与检索召回越准）。',
  visionPrompt: '图片描述的要求（如提取关键文字/界面元素、说明流程要点）。改动影响图片描述的内容倾向，进而影响检索与配图准确性。',
  visionConcurrency: '文档解析时图片描述的最大并发数。调高解析更快，但占用更多显存/推理资源（本地 Ollama 需设 OLLAMA_NUM_PARALLEL 才能并行）；调低更稳。',
  maxChunks: '单文档解析的最大知识块数（0=不限制）。超大文档超出部分截断不入库，防止 embedding 调用数万次导致解析失控。',
  maxImages: '单文档最多提取并描述的图片数（0=不限制）。图片爆炸的文档（上百张图）解析会非常慢，设上限可避免。',
  uploadMaxSize: '文档上传大小上限（MB）。保存即生效（新上传按新限制校验）；物理上限 1GB 由容器兜底，不可超过。',
  vectorWeight: '向量语义相似度在最终排序分中的占比。调高更侧重"意思相近"的匹配（适合口语化、换说法的提问）；过高可能引入字面无关但语义相近的块。',
  keywordWeight: '关键词精确命中在排序分中的占比。调高更侧重"字面命中"（适合操作手册中的专有名词、按钮名）；过高会漏掉语义相关但字面不同的内容。',
  titleBonus: '知识块标题命中关键词时的额外加分。调高更倾向返回标题相关的块；适合章节结构清晰的文档，但可能挤占正文命中的块。',
  rerankEnabled: '对混合检索候选再做一次精排（真交叉编码）。需先启动本地服务 scripts/win 或 scripts/mac 的 start_rerank_server（bge-reranker-v2-m3）；服务不可用时自动回退融合分排序。',
  rerankBaseUrl: '重排服务地址（OpenAI 兼容 /v1/rerank），默认本地 http://localhost:7997。',
  rerankModel: '重排模型名，与本地服务一致即可，默认 BAAI/bge-reranker-v2-m3。',
  rerankTimeout: '单次重排超时时间（毫秒），超过则回退融合分排序。建议 3000~8000。',
  modelWindows: '声明各模型的上下文窗口大小（token），格式"模型名=token"逗号分隔，按当前模型名的包含关系匹配。设置过大有超窗报错风险，过小会浪费模型能力。',
  defaultWindow: '当「模型窗口映射」未匹配到当前模型时使用的窗口大小兜底值。',
  safetyFactor: '上下文预算 = 窗口 × 安全系数 − 输出限制。系数越高单次可塞入更多知识块和历史，但越接近模型窗口上限；建议 0.6~0.8。',
  costCap: '单次请求输入 token 的硬上限（0=不限制）。用于控制成本：即使模型窗口很大，也最多塞这么多内容；设小会减少知识块数量、回答可能不完整。',
  maxOutput: '回答生成的最大 token 数。设太短回答会被截断；设太长增加成本与等待时间。',
  historyMax: '注入对话历史的 token 总上限。越大多轮上下文越完整（追问更准），但会挤压知识块的空间，且历史可能引入过时信息。',
  historyPerMsg: '每条历史消息保留的最大字符数，超出部分截断。控制历史占用的空间，保留最近轮次。',
  snippetWindow: '每个知识块只取"命中关键词 ± 该字符数"的片段送入上下文（0=整块塞入）。调大上下文信息更全但 token 消耗增大；调小更省 token 但可能丢失上下文导致理解偏差。',
  maxContextHits: '上下文最多塞入的知识块数量上限。调大可能引入相关度低的块稀释注意力；调小可能漏掉有价值的参考资料。',
  drEnabled: '深度思考总开关。关闭后即使前端开启"深度思考"开关也走普通回答流程（前端开关独立控制）。',
  drMode: '思考模式：model=通过 extraBody 透传 enable_thinking=true，从模型 reasoning_content 提取思维链（qwen3 系原生支持）；prompt=用提示词引导模型把思考输出到正文 content（兼容不支持思考参数的模型/网关）。',
  drEnableThinking: 'thinkingMode=model 时是否透传 enable_thinking=true。若网关静默忽略或返回异常，可关闭此项并切到 prompt 模式。',
  drPrompt: '思考阶段的引导提示词：要求模型先深度分析不直接作答，并在末尾输出 <search> 检索计划（精化 query | 子问题1 | 子问题2）。改坏可能导致检索计划提取失败（自动降级普通检索）。',
  drSearchTag: '检索计划包裹标签名，默认 search（即 <search>...</search>）。需与提示词中的标签一致。',
  drMaxSub: '从检索计划中最多取多少个子问题参与多路并行检索（不含精化 query）。越大召回越广但更慢、成本更高。',
  drMultiRetrieval: '是否多路并行检索（精化 query + 子问题分别检索后按最高分合并）。关闭则只用精化 query 单路检索（更快但召回面窄）。',
  drTimeout: '思考阶段最大等待时间(ms)。超时用已收集的思考内容降级为普通检索回答，不阻塞。',
  drMaxTokens: '思考输出的 token 上限（0=不设）。qwen3 思考模式下设 max_tokens 会导致空输出，默认 0；仅当思考过长需裁剪时设置。',
  historyRounds: '问答时注入对话历史的轮数（多轮记忆）。调大更连贯但占上下文预算；0=不注入历史。',
  remainTokenFloor: '上下文预算保留下限（token）：扣除系统提示与问题后至少保留的量，低于则不再填充知识块。',
  truncateFallbackChars: '知识块超出预算时的截断兜底字符数（至少保留的字数）。',
  parseConcurrency: '文档异步解析的并发数（同时解析几个文档）。调高多文档上传更快，但并发解析会同时占用 embedding/Ollama 资源；保存后对新任务生效。',
  ocrMinText: 'PDF 页文本少于该长度判定为扫描件/图片型，触发 OCR 识别（0=总是 OCR）。调高更激进触发 OCR，调低更依赖 PDF 自带文本。',
  userImageConcurrency: '用户在对话中上传图片的识别并发数（区别于文档解析的图片描述并发）。',
  vecThreshold: '向量相似度归一化基准：低于该分的向量命中归一化为 0 分，也是向量检索的相似度下限。调高更严格（召回更少但更相关）。',
  keywordLimit: '关键词检索最多返回的知识块数（SQL LIMIT）。调高召回更全但更慢、融合分计算更重。',
  retrievalTimeout: '混合检索超时（ms）：关键词子检索与总检索的超时上限，超时降级返回已收集结果。',
  positionBonus: '知识块位置奖励：位于文档首块/前段的内容额外加分。适合"文档开头是摘要"的结构；对顺序无关的文档可调低。',
  rerankMinHits: '触发重排的候选数区间（下限~上限）：候选太少（无意义）或太多（超时风险）时跳过重排，直接用融合分排序。',
  rerankFailCooldown: '重排服务失败后的冷却时间(ms)：冷却期内不再探测/调用（避免每个请求都撞一次），冷却结束后自动恢复。'
}

const loading = ref(false)
const saving = ref(false)
const rerankChecking = ref(false)

// 启用重排开关：打开前先校验服务可用性，服务不正常阻止开启并回滚
const onRerankEnabledChange = async checked => {
  if (!checked) return            // 关闭无需校验
  rerankChecking.value = true
  try {
    const r = await checkRerank()
    if (r.success && r.data?.available) {
      message.success('重排服务正常，已启用')
    } else {
      form.value.retrieval.rerank.enabled = false
      message.error('重排服务不可用：请先启动本地服务（scripts/win 或 scripts/mac 的 start_rerank_server），或检查服务地址')
    }
  } catch (e) {
    form.value.retrieval.rerank.enabled = false
    message.error('重排服务校验失败：' + (e.message || '服务不可用'))
  } finally {
    rerankChecking.value = false
  }
}
const form = ref({ chat: { model: '', temperature: 0.3, systemPrompt: '', remainTokenFloor: 800, truncateFallbackChars: 200, historyRounds: 5 },
                    vision: { model: '', prompt: '', concurrency: 4, userImageConcurrency: 2 },
                    chunk: { maxChunks: 3000, maxImages: 100, concurrency: 2, ocrMinText: 20 },
                    upload: { maxFileSizeMB: 200 },
                    retrieval: { vectorWeight: 0.6, keywordWeight: 0.4, titleBonus: 0.1,
                                 vecThreshold: 0.3, keywordLimit: 20, keywordTimeoutMs: 800, searchTimeoutMs: 8000,
                                 positionBonus: 0.03, sectionBonus: 0.01, keywordMaxTerms: 6, keywordMaxTotal: 12,
                                 rerank: { enabled: false, baseUrl: 'http://localhost:7997',
                                           model: 'BAAI/bge-reranker-v2-m3', timeoutMillis: 5000,
                                           minHits: 6, maxHits: 15, failCooldownMs: 60000 } },
                    context: { modelWindows: '', defaultWindowTokens: 32768, safetyFactor: 0.7, costCapTokens: 8000,
                               maxOutputTokens: 2000, historyMaxTokens: 1200, historyPerMsgChars: 200,
                               snippetWindowChars: 150, maxContextHits: 8 },
                    deepReasoning: { enabled: true, thinkingMode: 'model', enableThinking: true, prompt: '',
                                     searchTag: 'search', maxSubQueries: 3, multiRetrieval: true,
                                     timeoutMillis: 30000, maxThinkingTokens: 0 } })
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
      form.value.chat.remainTokenFloor = Number(d.chat?.remainTokenFloor?.value ?? 800)
      form.value.chat.truncateFallbackChars = Number(d.chat?.truncateFallbackChars?.value ?? 200)
      form.value.chat.historyRounds = Number(d.chat?.historyRounds?.value ?? 5)
      form.value.vision.model = d.vision?.model?.value || ''
      form.value.vision.prompt = d.vision?.prompt?.value || ''
      form.value.vision.concurrency = Number(d.vision?.concurrency?.value ?? 4)
      form.value.vision.userImageConcurrency = Number(d.vision?.userImageConcurrency?.value ?? 2)
      const ck = d.chunk || {}
      form.value.chunk.maxChunks = Number(ck.maxChunks?.value ?? 3000)
      form.value.chunk.maxImages = Number(ck.maxImages?.value ?? 100)
      form.value.chunk.concurrency = Number(ck.concurrency?.value ?? 2)
      form.value.chunk.ocrMinText = Number(ck.ocrMinText?.value ?? 20)
      const up = d.upload || {}
      form.value.upload.maxFileSizeMB = Math.round(Number(up.maxFileSize?.value ?? 209715200) / 1024 / 1024)
      form.value.retrieval.vectorWeight = Number(d.retrieval?.vectorWeight?.value ?? 0.6)
      form.value.retrieval.keywordWeight = Number(d.retrieval?.keywordWeight?.value ?? 0.4)
      form.value.retrieval.titleBonus = Number(d.retrieval?.titleBonus?.value ?? 0.1)
      form.value.retrieval.vecThreshold = Number(d.retrieval?.vecThreshold?.value ?? 0.3)
      form.value.retrieval.keywordLimit = Number(d.retrieval?.keywordLimit?.value ?? 20)
      form.value.retrieval.keywordTimeoutMs = Number(d.retrieval?.keywordTimeoutMs?.value ?? 800)
      form.value.retrieval.searchTimeoutMs = Number(d.retrieval?.searchTimeoutMs?.value ?? 8000)
      form.value.retrieval.positionBonus = Number(d.retrieval?.positionBonus?.value ?? 0.03)
      form.value.retrieval.sectionBonus = Number(d.retrieval?.sectionBonus?.value ?? 0.01)
      form.value.retrieval.keywordMaxTerms = Number(d.retrieval?.keywordMaxTerms?.value ?? 6)
      form.value.retrieval.keywordMaxTotal = Number(d.retrieval?.keywordMaxTotal?.value ?? 12)
      // rerank 是独立分组（d.rerank），勿用 retrieval 组
      const rr = d.rerank || {}
      form.value.retrieval.rerank.enabled = rr.enabled?.value === 'true'
      form.value.retrieval.rerank.baseUrl = rr.baseUrl?.value || 'http://localhost:7997'
      form.value.retrieval.rerank.model = rr.model?.value || 'BAAI/bge-reranker-v2-m3'
      form.value.retrieval.rerank.timeoutMillis = Number(rr.timeoutMillis?.value ?? 5000)
      form.value.retrieval.rerank.minHits = Number(rr.minHits?.value ?? 6)
      form.value.retrieval.rerank.maxHits = Number(rr.maxHits?.value ?? 15)
      form.value.retrieval.rerank.failCooldownMs = Number(rr.failCooldownMs?.value ?? 60000)
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
      const dr = d.deepReasoning || {}
      form.value.deepReasoning.enabled = dr.enabled?.value === 'true'
      form.value.deepReasoning.thinkingMode = dr.thinkingMode?.value || 'model'
      form.value.deepReasoning.enableThinking = dr.enableThinking?.value === 'true'
      form.value.deepReasoning.prompt = dr.prompt?.value || ''
      form.value.deepReasoning.searchTag = dr.searchTag?.value || 'search'
      form.value.deepReasoning.maxSubQueries = Number(dr.maxSubQueries?.value ?? 3)
      form.value.deepReasoning.multiRetrieval = dr.multiRetrieval?.value === 'true'
      form.value.deepReasoning.timeoutMillis = Number(dr.timeoutMillis?.value ?? 30000)
      form.value.deepReasoning.maxThinkingTokens = Number(dr.maxThinkingTokens?.value ?? 0)
      ro.value.chat = { baseUrl: d.chat?.baseUrl?.value || '', apiKey: d.chat?.apiKey?.value || '' }
      ro.value.vision = { baseUrl: d.vision?.baseUrl?.value || '', apiKey: d.vision?.apiKey?.value || '' }
      ro.value.embedding = { model: d.embedding?.model?.value || '' }
    }
  } catch (e) { message.error(e.message || '加载配置失败') }
  finally { loading.value = false }

  // 滚动高亮跟随：视口内最靠上的分组自动点亮对应锚点
  anchorObserver = new IntersectionObserver(entries => {
    entries.forEach(en => {
      if (en.isIntersecting) currentAnchor.value = en.target.id.replace('cfg-anchor-', '')
    })
  }, { rootMargin: '-20px 0px -70% 0px', threshold: 0 })
  anchors.forEach(a => {
    const el = document.getElementById('cfg-anchor-' + a.key)
    if (el) anchorObserver.observe(el)
  })
})

onUnmounted(() => {
  if (anchorObserver) { anchorObserver.disconnect(); anchorObserver = null }
})

const save = async () => {
  saving.value = true
  try {
    const r = await saveConfig({
      chat: { model: form.value.chat.model?.trim(), temperature: String(form.value.chat.temperature),
              systemPrompt: form.value.chat.systemPrompt?.trim(),
              remainTokenFloor: String(form.value.chat.remainTokenFloor),
              truncateFallbackChars: String(form.value.chat.truncateFallbackChars),
              historyRounds: String(form.value.chat.historyRounds) },
      vision: { model: form.value.vision.model?.trim(), prompt: form.value.vision.prompt?.trim(),
                concurrency: String(form.value.vision.concurrency),
                userImageConcurrency: String(form.value.vision.userImageConcurrency) },
      chunk: { maxChunks: String(form.value.chunk.maxChunks), maxImages: String(form.value.chunk.maxImages),
               concurrency: String(form.value.chunk.concurrency), ocrMinText: String(form.value.chunk.ocrMinText) },
      upload: { maxFileSize: String(form.value.upload.maxFileSizeMB * 1024 * 1024) },
      retrieval: { vectorWeight: String(form.value.retrieval.vectorWeight),
                   keywordWeight: String(form.value.retrieval.keywordWeight),
                   titleBonus: String(form.value.retrieval.titleBonus),
                   vecThreshold: String(form.value.retrieval.vecThreshold),
                   keywordLimit: String(form.value.retrieval.keywordLimit),
                   keywordTimeoutMs: String(form.value.retrieval.keywordTimeoutMs),
                   searchTimeoutMs: String(form.value.retrieval.searchTimeoutMs),
                   positionBonus: String(form.value.retrieval.positionBonus),
                   sectionBonus: String(form.value.retrieval.sectionBonus),
                   keywordMaxTerms: String(form.value.retrieval.keywordMaxTerms),
                   keywordMaxTotal: String(form.value.retrieval.keywordMaxTotal) },
      // rerank 是独立分组（后端 key 前缀 rerank.*），不可嵌套在 retrieval 下
      rerank: { enabled: String(form.value.retrieval.rerank.enabled),
                baseUrl: form.value.retrieval.rerank.baseUrl?.trim(),
                model: form.value.retrieval.rerank.model?.trim(),
                timeoutMillis: String(form.value.retrieval.rerank.timeoutMillis),
                minHits: String(form.value.retrieval.rerank.minHits),
                maxHits: String(form.value.retrieval.rerank.maxHits),
                failCooldownMs: String(form.value.retrieval.rerank.failCooldownMs) },
      context: { modelWindows: form.value.context.modelWindows?.trim(),
                 defaultWindowTokens: String(form.value.context.defaultWindowTokens),
                 safetyFactor: String(form.value.context.safetyFactor),
                 costCapTokens: String(form.value.context.costCapTokens),
                 maxOutputTokens: String(form.value.context.maxOutputTokens),
                 historyMaxTokens: String(form.value.context.historyMaxTokens),
                 historyPerMsgChars: String(form.value.context.historyPerMsgChars),
                 snippetWindowChars: String(form.value.context.snippetWindowChars),
                 maxContextHits: String(form.value.context.maxContextHits) },
      deepReasoning: { enabled: String(form.value.deepReasoning.enabled),
                       thinkingMode: form.value.deepReasoning.thinkingMode,
                       enableThinking: String(form.value.deepReasoning.enableThinking),
                       prompt: form.value.deepReasoning.prompt,
                       searchTag: form.value.deepReasoning.searchTag?.trim(),
                       maxSubQueries: String(form.value.deepReasoning.maxSubQueries),
                       multiRetrieval: String(form.value.deepReasoning.multiRetrieval),
                       timeoutMillis: String(form.value.deepReasoning.timeoutMillis),
                       maxThinkingTokens: String(form.value.deepReasoning.maxThinkingTokens) }
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
/* 折叠面板：去掉卡片默认背景与边框，保持与页面一致的浅色观感 */
.cfg-collapse {
  background: transparent;
}
/* 分区锚点导航条：吸顶常驻（滚动时保持可见，随时可跳任意分组） */
.cfg-anchor {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 6px;
  margin-bottom: 14px;
  padding: 8px 12px;
  background: #fafafa;
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  position: sticky;
  top: 0;
  z-index: 10;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}
.cfg-anchor a {
  font-size: 13px;
  color: #555;
  padding: 3px 10px;
  border-radius: 12px;
  text-decoration: none;
  transition: all 0.2s;
}
.cfg-anchor a:hover {
  color: #1677ff;
  background: #e6f4ff;
}
.cfg-anchor a.anchor-active {
  color: #fff;
  background: #1677ff;
}
.cfg-collapse :deep(.ant-collapse-item) {
  background: #fff;
  border-radius: 8px;
  margin-bottom: 12px;
  border: 1px solid #f0f0f0;
}
.cfg-collapse :deep(.ant-collapse-header) {
  font-weight: 500;
}
</style>
