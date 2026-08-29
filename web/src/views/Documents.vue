<template>
  <div class="doc-page" @dragover.prevent @dragenter.prevent="dragDepth++" @dragleave.prevent="dragDepth = Math.max(0, dragDepth - 1)" @drop.prevent="onDrop">
    <!-- 拖拽遮罩：拖入文件时全页提示 -->
    <div v-if="dragDepth > 0" class="drag-mask">
      <div class="drag-mask-tip"><upload-outlined style="font-size:40px" /><div>松开鼠标上传到知识库</div></div>
    </div>
    <a-card title="文档管理">
    <!-- 工具栏：右侧上传操作 -->
    <div class="toolbar">
      <div class="toolbar-upload">
        <a-input v-model:value="desc" placeholder="文档描述（可选）" style="width:170px" allow-clear />
        <a-button @click="openGlobalSearch"><search-outlined /> 全局搜索</a-button>
        <a-upload :before-upload="beforeUpload" :show-upload-list="false" :accept="'.' + uploadCfg.allowedExts.join(',.')" multiple :disabled="uploading">
          <a-button type="primary" :loading="uploading">
            <upload-outlined /> {{ uploading ? '上传中...' : '上传文档' }}
          </a-button>
        </a-upload>
      </div>
    </div>
    <a-progress v-if="uploading" :percent="uploadPercent" size="small" class="upload-progress" />

    <!-- 批量操作栏 -->
    <div v-if="selectedKeys.length" class="batch-bar">
      <span>已选 {{ selectedKeys.length }} 项：</span>
      <a-button size="small" type="primary" @click="batchStatus(0)">批量启用</a-button>
      <a-button size="small" @click="batchStatus(1)">批量弃用</a-button>
      <a-popconfirm title="确定删除选中的文档？知识库将同步移除" @confirm="batchDelete">
        <a-button size="small" danger>批量删除</a-button>
      </a-popconfirm>
      <a-popconfirm title="对选中文档重新解析+向量化？" @confirm="batchReparse">
        <a-button size="small">批量重解析</a-button>
      </a-popconfirm>
      <a-button size="small" type="text" @click="selectedKeys = []">取消选择</a-button>
    </div>

    <a-table
      :columns="cols" :data-source="list" :loading="loading" row-key="id"
      :pagination="{ pageSize: 10 }"
      :row-selection="{ selectedRowKeys: selectedKeys, onChange: k => selectedKeys = k }"
      :locale="{ emptyText: '暂无文档，点击右上角上传 .docx / .pdf / .xlsx' }"
    >
      <template #bodyCell="{ column, record, text }">
        <template v-if="column.key === 'status'">
          <!-- fail-loud：生效/弃用/失败状态也悬浮展示 parseDesc（含截断/跳图统计），不再只在解析中可见 -->
          <a-tooltip v-if="text === 0 || text === 1 || text === 3" :title="record.parseDesc || ''" placement="top">
            <a-tag v-if="text === 0" color="green">生效</a-tag>
            <a-tag v-else-if="text === 1" color="orange">已弃用</a-tag>
            <a-tag v-else color="red" style="cursor:pointer"
                   @click="showFailReason(record)">解析失败</a-tag>
          </a-tooltip>
          <div v-else-if="text === 2" style="display:flex;flex-direction:column;gap:2px">
            <a-progress :percent="record.parseProgress || 0" size="small" style="width:110px;margin:0" />
            <span style="font-size:12px;color:#666;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:150px"
                  :title="record.parseDesc || '解析中'">{{ record.parseDesc || '解析中' }}</span>
          </div>
        </template>
        <template v-else-if="column.key === 'hitCount'">{{ text || 0 }}</template>
        <template v-else-if="column.key === 'fileSize'">{{ fmtSize(text) }}</template>
        <template v-else-if="column.key === 'createTime'">{{ fmtTime(text) }}</template>
        <template v-else-if="column.key === 'action'">
          <a-button v-if="record.status === 0" type="link" size="small" @click="openKb(record)">知识块</a-button>
          <a-button v-if="record.status === 0" type="link" size="small" @click="openVersions(record)">版本</a-button>
          <a-button v-if="record.status === 0" type="link" size="small" @click="toggleStatus(record, 1)">弃用</a-button>
          <a-button v-else-if="record.status === 1" type="link" size="small" @click="toggleStatus(record, 0)">启用</a-button>
          <a-button v-if="record.status === 0 || record.status === 3" type="link" size="small"
                    :loading="reparsingId === record.id" @click="reparse(record.id)">重解析</a-button>
          <a-popconfirm title="确定删除该文档？删除后知识库将同步移除" @confirm="del(record.id)">
            <a-button type="link" danger size="small" :loading="deletingId === record.id">
              <delete-outlined /> 删除
            </a-button>
          </a-popconfirm>
        </template>
      </template>
    </a-table>

    <!-- 知识块预览：列表（点击行查看详情；编辑/删除操作） -->
    <a-modal v-model:open="kbVisible" :title="'知识块预览 · ' + kbDocName" :footer="null" width="820">
      <div style="margin-bottom:10px">
        <a-input-search v-model:value="kbSearch" placeholder="按标题/内容过滤知识块" allow-clear />
      </div>
      <a-spin :spinning="kbLoading">
        <a-table :data-source="kbFilteredList" size="small" row-key="id" :pagination="{ pageSize: 20 }"
                 :locale="{ emptyText: '暂无知识块' }"
                 :custom-row="r => ({ onClick: () => openKbDetail(r) })"
                 style="cursor:pointer">
          <a-table-column title="#" dataIndex="chunkIndex" key="chunkIndex" width="50" />
          <a-table-column title="状态" key="status" width="70">
            <template #default="{ record }">
              <a-tag v-if="(record.status ?? 0) === 0" color="green">生效</a-tag>
              <a-tag v-else color="orange">已停用</a-tag>
            </template>
          </a-table-column>
          <a-table-column title="标题" key="title" ellipsis>
            <!-- M11 fail-loud：vectorId 为空 = 未向量化（仅关键词可召回），角标提示 -->
            <template #default="{ record }">
              <span>{{ record.title }}</span>
              <a-tag v-if="!record.vectorId" color="orange" style="margin-left:6px;font-size:11px">未向量化</a-tag>
            </template>
          </a-table-column>
          <a-table-column title="内容摘要" key="snippet">
            <template #default="{ record }">{{ (record.content || '').replace(/\s+/g, ' ').slice(0, 80) }}</template>
          </a-table-column>
          <a-table-column title="操作" key="action" width="120">
            <template #default="{ record }">
              <a-button type="link" size="small" @click.stop="openKbEdit(record)">编辑</a-button>
              <a-button v-if="(record.status ?? 0) === 0" type="link" size="small" @click.stop="toggleKbStatus(record, 1)">停用</a-button>
              <a-button v-else type="link" size="small" @click.stop="toggleKbStatus(record, 0)">启用</a-button>
              <a-popconfirm title="确定删除该知识块？向量将同步移除" ok-text="删除" cancel-text="取消"
                            @confirm.stop="delKnowledge(record.id)">
                <a-button type="link" danger size="small" @click.stop>删除</a-button>
              </a-popconfirm>
            </template>
          </a-table-column>
        </a-table>
      </a-spin>
    </a-modal>

    <!-- 跨文档全局搜索知识块 -->
    <a-modal v-model:open="gSearchVisible" title="知识块全局搜索" :footer="null" width="820">
      <div style="display:flex;gap:8px;margin-bottom:12px">
        <a-input-search v-model:value="gSearchKw" placeholder="输入关键词，跨全部文档搜索知识块（含已停用）"
                        enter-button="搜索" :loading="gSearchLoading" @search="doGlobalSearch" />
      </div>
      <a-table :data-source="gResults" size="small" row-key="id"
               :pagination="gResults.length > 20 ? { pageSize: 20 } : false"
               :locale="{ emptyText: '输入关键词后搜索' }"
               :custom-row="r => ({ onClick: () => openGlobalDetail(r) })" style="cursor:pointer">
        <a-table-column title="文档" dataIndex="docName" key="docName" width="160" ellipsis />
        <a-table-column title="标题" key="title" width="150" ellipsis>
          <template #default="{ record }">
            <span>{{ record.title || '（无标题）' }}</span>
            <a-tag v-if="(record.status ?? 0) === 1" color="orange" style="margin-left:4px;font-size:11px">已停用</a-tag>
          </template>
        </a-table-column>
        <a-table-column title="内容摘要" key="snippet" ellipsis>
          <template #default="{ record }">{{ record.snippet }}</template>
        </a-table-column>
      </a-table>
    </a-modal>

    <!-- 知识块编辑弹窗（title/content；图片保留；编辑/预览切换） -->
    <a-modal v-model:open="kbEditVisible" title="编辑知识块" :footer="null" width="640">
      <a-form layout="vertical">
        <a-form-item label="标题">
          <a-input v-model:value="kbEditForm.title" maxlength="200" placeholder="知识块标题" />
        </a-form-item>
        <a-form-item label="内容">
          <a-tabs v-model:active-key="kbEditTab" size="small">
            <a-tab-pane key="edit" tab="编辑">
              <a-textarea v-model:value="kbEditForm.content" :rows="10" placeholder="知识块正文（支持 Markdown 语法，图片用 [图片N] 占位）" />
            </a-tab-pane>
            <a-tab-pane key="preview" tab="预览">
              <div class="md" style="max-height:280px;overflow-y:auto;font-size:14px;line-height:1.7;color:#333;padding:2px 4px" v-html="kbEditPreviewHtml"></div>
            </a-tab-pane>
          </a-tabs>
        </a-form-item>
      </a-form>
      <div style="text-align:right">
        <a-button style="margin-right:8px" @click="kbEditVisible = false">取消</a-button>
        <a-button type="primary" :loading="kbEditSaving" @click="saveKnowledgeEdit">保存</a-button>
      </div>
    </a-modal>

    <!-- 版本管理弹窗 -->
    <a-modal v-model:open="verVisible" :title="'版本历史 · ' + verDocName" :footer="null" width="560">
      <a-spin :spinning="verLoading">
        <a-table :data-source="verList" size="small" row-key="version" :pagination="false"
                 :locale="{ emptyText: '暂无版本记录' }">
          <a-table-column title="版本" dataIndex="version" key="version" width="80" />
          <a-table-column title="知识块数" dataIndex="chunkCount" key="chunkCount" width="100" />
          <a-table-column title="创建时间" dataIndex="createTime" key="createTime" />
          <a-table-column title="操作" key="action" width="100">
            <template #default="{ record }">
              <a-popconfirm :title="`确定回滚到 v${record.version}？当前版本将被覆盖`" ok-text="回滚" cancel-text="取消"
                            @confirm="doRollback(record.version)">
                <a-button type="link" size="small">回滚</a-button>
              </a-popconfirm>
            </template>
          </a-table-column>
        </a-table>
      </a-spin>
    </a-modal>

    <!-- 知识块详情（markdown 渲染 + 图片按原文位置交错，与问答页引用弹窗一致） -->
    <a-modal v-model:open="kbDetailVisible" :title="kbDetail?.title || '知识块详情'" :footer="null" width="720">
      <div class="md" style="max-height:60vh;overflow-y:auto;font-size:14px;line-height:1.7;color:#333;padding-right:6px" v-html="kbDetailHtml"></div>
    </a-modal>
  </a-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { UploadOutlined, DeleteOutlined, SearchOutlined } from '@ant-design/icons-vue'
import { listDocuments, uploadDocumentsBatch, updateDocumentStatus, reparseDocument, deleteDocument,
         batchDeleteDocuments, batchUpdateDocumentStatus, getDocumentStats, listKnowledgeByDoc, getKnowledgeDetail,
         updateKnowledge, deleteKnowledge, listDocumentVersions, rollbackDocument,
         getRuntimeConfig, batchReparseDocuments, updateKnowledgeStatus, searchKnowledge } from '../api'
import { renderMd } from '../utils/markdown'

// 知识块预览状态
const kbVisible = ref(false)
const kbLoading = ref(false)
const kbList = ref([])
const kbDocName = ref('')
const kbDetailVisible = ref(false)
const kbDetail = ref(null)
// 详情弹窗：markdown 渲染 + 图片按原文位置交错（与问答页引用弹窗一致）
const kbDetailHtml = computed(() => renderMd(kbDetail.value?.content, kbDetail.value?.images))
const openKb = async record => {
  kbDocName.value = record.fileName
  kbDocId.value = record.id
  kbVisible.value = true
  kbLoading.value = true
  kbList.value = []
  try {
    const r = await listKnowledgeByDoc(record.id)
    kbList.value = r.success && Array.isArray(r.data) ? r.data : []
  } catch (e) { message.error(e.message || '加载知识块失败') }
  finally { kbLoading.value = false }
}
const openKbDetail = async row => {
  kbDetail.value = null
  kbDetailVisible.value = true
  try {
    const r = await getKnowledgeDetail(row.id)
    if (r.success) kbDetail.value = r.data
    else message.error(r.msg || '加载详情失败')
  } catch (e) { message.error(e.message || '加载详情失败') }
}

// ==================== 知识块编辑/删除 ====================
const kbEditVisible = ref(false)
const kbEditSaving = ref(false)
const kbEditForm = ref({ id: '', title: '', content: '' })
const kbEditTab = ref('edit')        // 编辑弹窗内 tab：edit 源码 / preview 渲染
const kbEditImages = ref([])         // 预览用图片（列表 record 无 images，打开时异步补）
const kbEditPreviewHtml = computed(() => renderMd(kbEditForm.value.content, kbEditImages.value))
const openKbEdit = async row => {
  kbEditForm.value = { id: row.id, title: row.title || '', content: row.content || '' }
  kbEditImages.value = []
  kbEditTab.value = 'edit'
  kbEditVisible.value = true
  // 预览需要图片：正文含 [图片 占位时异步补充 images（失败则空数组，预览时图片位显示 [图片N] 原文）
  if (/\s*\[图片/.test(kbEditForm.value.content)) {
    try {
      const r = await getKnowledgeDetail(row.id)
      if (r.success && Array.isArray(r.data?.images)) kbEditImages.value = r.data.images
    } catch (e) { /* 预览降级为无图，不阻塞编辑 */ }
  }
}
const saveKnowledgeEdit = async () => {
  if (!kbEditForm.value.title.trim()) { message.warning('标题不能为空'); return }
  if (!kbEditForm.value.content.trim()) { message.warning('内容不能为空'); return }
  kbEditSaving.value = true
  try {
    const r = await updateKnowledge(kbEditForm.value.id, kbEditForm.value.title.trim(), kbEditForm.value.content)
    if (r.success) {
      message.success('知识块已更新')
      kbEditVisible.value = false
      if (kbVisible.value) {
        const rr = await listKnowledgeByDoc(kbDocId.value)
        kbList.value = rr.success && Array.isArray(rr.data) ? rr.data : []
      }
    } else message.error(r.msg || '更新失败')
  } catch (e) { message.error(e.message || '更新失败') }
  finally { kbEditSaving.value = false }
}
const kbDocId = ref('')
const delKnowledge = async id => {
  try {
    const r = await deleteKnowledge(id)
    if (r.success) {
      message.success('知识块已删除')
      if (kbVisible.value) {
        const rr = await listKnowledgeByDoc(kbDocId.value)
        kbList.value = rr.success && Array.isArray(rr.data) ? rr.data : []
      }
      fetchList()
    } else message.error(r.msg || '删除失败')
  } catch (e) { message.error(e.message || '删除失败') }
}

// ==================== 文档版本管理 ====================
const verVisible = ref(false)
const verLoading = ref(false)
const verList = ref([])
const verDocName = ref('')
const verDocId = ref('')
const openVersions = async record => {
  verDocName.value = record.fileName
  verDocId.value = record.id
  verVisible.value = true
  verLoading.value = true
  verList.value = []
  try {
    const r = await listDocumentVersions(record.id)
    verList.value = r.success && Array.isArray(r.data) ? r.data : []
  } catch (e) { message.error(e.message || '加载版本失败') }
  finally { verLoading.value = false }
}
const doRollback = async version => {
  try {
    const r = await rollbackDocument(verDocId.value, version)
    if (r.success) {
      message.success(`已回滚到 v${version}`)
      verVisible.value = false
      fetchList()
    } else message.error(r.msg || '回滚失败')
  } catch (e) { message.error(e.message || '回滚失败') }
}

// ==================== 上传限制（默认与后端 multipart 一致；启动时从 /config/public 动态获取，改后端配置前端自动同步）====================
const MAX_SIZE = 200 * 1024 * 1024
const ALLOWED = ['docx', 'pdf', 'xlsx']
const uploadCfg = ref({ maxFileSize: MAX_SIZE, maxFileSizeLabel: '200MB', allowedExts: ALLOWED })

async function loadUploadCfg() {
  try {
    const r = await getRuntimeConfig()
    if (r.success && r.data?.upload) {
      const u = r.data.upload
      if (Number(u.maxFileSize) > 0) uploadCfg.value.maxFileSize = Number(u.maxFileSize)
      if (u.maxFileSizeLabel) uploadCfg.value.maxFileSizeLabel = u.maxFileSizeLabel
      if (Array.isArray(u.allowedExts) && u.allowedExts.length) uploadCfg.value.allowedExts = u.allowedExts
    }
  } catch (e) { /* 接口失败保持默认值 */ }
}

const cols = [
  { title: '文件名', dataIndex: 'fileName', key: 'fileName', ellipsis: true },
  { title: '类型', dataIndex: 'fileType', key: 'fileType', width: 70 },
  { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true },
  { title: '知识片段数', dataIndex: 'chunkCount', key: 'chunkCount', width: 110 },
  { title: '命中次数', dataIndex: 'hitCount', key: 'hitCount', width: 90 },
  { title: '文件大小', dataIndex: 'fileSize', key: 'fileSize', width: 110 },
  { title: '状态', dataIndex: 'status', key: 'status', width: 170 },
  { title: '上传时间', dataIndex: 'createTime', key: 'createTime', width: 170 },
  { title: '操作', key: 'action', width: 220 }
]

const list = ref([])
const loading = ref(false)
const uploading = ref(false)
const uploadPercent = ref(0)
const deletingId = ref('')
const reparsingId = ref('')
const desc = ref('')
// 拖拽上传：dragenter/dragleave 嵌套计数，>0 显示遮罩
const dragDepth = ref(0)
// 知识块预览搜索（前端过滤）
const kbSearch = ref('')
const kbFilteredList = computed(() => {
  const kw = kbSearch.value.trim().toLowerCase()
  if (!kw) return kbList.value
  return kbList.value.filter(k =>
    (k.title || '').toLowerCase().includes(kw) || (k.content || '').toLowerCase().includes(kw))
})

// 知识块级启停用：切换后刷新预览列表（状态列即时反映）
const toggleKbStatus = async (record, status) => {
  try {
    const r = await updateKnowledgeStatus(record.id, status)
    if (r.success) {
      message.success(status === 1 ? '已停用，该知识块不再参与召回' : '已启用，恢复召回')
      kbSearch.value = ''
      const res = await listKnowledgeByDoc(record.docId)
      if (res.success) kbList.value = res.data || []
    } else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
}

// 跨文档全局搜索
const gSearchVisible = ref(false)
const gSearchKw = ref('')
const gSearchLoading = ref(false)
const gResults = ref([])
const openGlobalSearch = () => { gSearchVisible.value = true; gSearchKw.value = ''; gResults.value = [] }
const doGlobalSearch = async () => {
  const kw = gSearchKw.value.trim()
  if (!kw) { message.warning('请输入关键词'); return }
  gSearchLoading.value = true
  try {
    const r = await searchKnowledge(kw)
    if (r.success) gResults.value = r.data || []
    else message.error(r.msg || '搜索失败')
  } catch (e) { message.error(e.message || '搜索失败') }
  finally { gSearchLoading.value = false }
}
// 点结果行 → 打开既有知识块详情弹窗（复用渲染管线）
const openGlobalDetail = async row => {
  kbDetail.value = null
  kbDetailVisible.value = true
  try {
    const r = await getKnowledgeDetail(row.id)
    if (r.success) kbDetail.value = r.data
    else message.error(r.msg || '加载详情失败')
  } catch (e) { message.error(e.message || '加载详情失败') }
}
const selectedKeys = ref([])
let pollTimer = null

onMounted(() => { fetchList(); loadUploadCfg(); window.addEventListener('paste', onPaste) })
onUnmounted(() => { if (pollTimer) clearInterval(pollTimer); window.removeEventListener('paste', onPaste) })

async function fetchList() {
  loading.value = true
  try {
    const [r, stats] = await Promise.all([listDocuments(), getDocumentStats()])
    if (r.success) {
      const hitMap = (stats && stats.success && stats.data) ? stats.data : {}
      list.value = (r.data || []).map(d => ({ ...d, hitCount: hitMap[d.id] || 0 }))
      // 有解析中的文档则轮询（直到全部完成）
      if (list.value.some(d => d.status === 2)) startPolling()
      else stopPolling()
    }
  } catch (e) { message.error(e.message || '获取列表失败') }
  finally { loading.value = false }
}

function startPolling() {
  if (pollTimer) return
  pollTimer = setInterval(async () => {
    try {
      const r = await listDocuments()
      if (r.success) {
        list.value = r.data || []
        if (!list.value.some(d => d.status === 2)) stopPolling()
      }
    } catch (e) { /* 轮询失败忽略 */ }
  }, 3000)
}
function stopPolling() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
}

// 多文件上传（批量接口）
async function beforeUpload(fileList) {
  const files = Array.isArray(fileList) ? fileList : [fileList]
  const bad = files.find(f => {
    const ext = (f.name.split('.').pop() || '').toLowerCase()
    return !uploadCfg.value.allowedExts.includes(ext) || f.size > uploadCfg.value.maxFileSize
  })
  if (bad) {
    message.error(`${bad.name} 不支持或超过 ${uploadCfg.value.maxFileSizeLabel}（支持 ${uploadCfg.value.allowedExts.join('/')}）`)
    return false
  }
  uploading.value = true
  uploadPercent.value = 0
  try {
    const r = await uploadDocumentsBatch(files, pct => { uploadPercent.value = pct }, desc.value?.trim() || undefined)
    if (r.success) {
      const failed = (r.data || []).filter(x => !x.success)
      if (failed.length) {
        message.warning(`${files.length - failed.length} 个提交成功，${failed.length} 个失败: ${failed[0].msg || ''}`)
      } else {
        message.success(`已提交 ${files.length} 个文档解析`)
      }
      desc.value = ''
      fetchList()
    } else {
      message.error(r.msg || '上传失败')
    }
  } catch (e) { message.error(e.message || '上传失败') }
  finally { uploading.value = false }
  return false
}

// 拖拽上传：drop 落下的文件走与手动选择相同的校验+上传路径
const onDrop = e => {
  dragDepth.value = 0
  const files = Array.from(e.dataTransfer?.files || [])
  if (!files.length) return
  // 先过滤掉不支持的类型，给出明确提示
  const ok = files.filter(f => uploadCfg.value.allowedExts.includes((f.name.split('.').pop() || '').toLowerCase()))
  const skipped = files.length - ok.length
  if (skipped > 0) message.warning(`跳过 ${skipped} 个不支持的文件`)
  if (ok.length) beforeUpload(ok)
}
// 粘贴上传：仅接受文档类型文件（截图/图片在文档库无意义，忽略）
const onPaste = e => {
  const files = Array.from(e.clipboardData?.files || [])
    .filter(f => uploadCfg.value.allowedExts.includes((f.name.split('.').pop() || '').toLowerCase()))
  if (files.length) beforeUpload(files)
}

// 批量重解析：逐个提交，返回明细提示
const batchReparse = async () => {
  try {
    const r = await batchReparseDocuments(selectedKeys.value)
    if (r.success) {
      const failed = (r.data || []).filter(x => !x.success)
      if (failed.length) message.warning(`已提交 ${r.data.length - failed.length} 个，${failed.length} 个失败: ${failed[0].msg || ''}`)
      else message.success(`已提交 ${r.data.length} 个重解析`)
      selectedKeys.value = []
      fetchList()
      startPolling()
    } else message.error(r.msg || '批量重解析失败')
  } catch (e) { message.error(e.message || '批量重解析失败') }
}

async function toggleStatus(record, status) {
  try {
    const r = await updateDocumentStatus(record.id, status)
    if (r.success) { message.success(status === 0 ? '已启用' : '已弃用'); fetchList() }
    else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
}

async function batchStatus(status) {
  if (!selectedKeys.value.length) return
  try {
    const r = await batchUpdateDocumentStatus(selectedKeys.value, status)
    if (r.success) { message.success(`已${status === 0 ? '启用' : '弃用'} ${selectedKeys.value.length} 个文档`); selectedKeys.value = []; fetchList() }
    else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
}

async function batchDelete() {
  if (!selectedKeys.value.length) return
  try {
    const r = await batchDeleteDocuments(selectedKeys.value)
    if (r.success) { message.success(`已删除 ${selectedKeys.value.length} 个文档`); selectedKeys.value = []; fetchList() }
    else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
}

async function reparse(id) {
  reparsingId.value = id
  try {
    const r = await reparseDocument(id)
    if (r.success) { message.success('已重新提交解析'); fetchList() }
    else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
  finally { reparsingId.value = '' }
}

async function del(id) {
  deletingId.value = id
  try {
    const r = await deleteDocument(id)
    if (r.success) { message.success('删除成功'); fetchList() }
    else message.error(r.msg || '删除失败')
  } catch (e) { message.error(e.message || '删除失败') }
  finally { deletingId.value = '' }
}

function showFailReason(record) {
  Modal.info({
    title: `解析失败 - ${record.fileName}`,
    content: record.failReason || '未知原因，可点击"重解析"重试'
  })
}

const fmtSize = s => !s ? '-' : s < 1024 ? s + ' B' : s < 1048576 ? (s/1024).toFixed(1) + ' KB' : (s/1048576).toFixed(1) + ' MB'
const fmtTime = t => {
  if (!t) return '-'
  const d = new Date(t)
  return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')} ${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`
}
</script>

<style scoped>
/* 工具栏：筛选在左、上传操作在右，窄屏自动换行 */
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px 16px;
  margin-bottom: 14px;
}
.toolbar-upload {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.upload-progress {
  margin-bottom: 14px;
  max-width: 420px;
}
.batch-bar {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 12px; margin-bottom: 10px;
  background: #e6f4ff; border: 1px solid #91caff; border-radius: 6px;
  font-size: 13px; color: #0958d9;
}
/* 拖拽上传遮罩：全页半透明提示 */
.drag-mask {
  position: fixed; inset: 0; z-index: 1000;
  background: rgba(22,119,255,.08); backdrop-filter: blur(1px);
  display: flex; align-items: center; justify-content: center;
  pointer-events: none;   /* 不拦截拖拽事件，保证 drop 落到容器 */
}
.drag-mask-tip {
  text-align: center; color: #1677ff; font-size: 16px;
  background: #fff; border: 2px dashed #1677ff; border-radius: 12px;
  padding: 24px 40px; display: flex; flex-direction: column; gap: 8px; align-items: center;
}
</style>
