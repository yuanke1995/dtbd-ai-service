<template>
  <a-card title="文档管理">
    <!-- 工具栏：右侧上传操作 -->
    <div class="toolbar">
      <div class="toolbar-upload">
        <a-input v-model:value="desc" placeholder="文档描述（可选）" style="width:170px" allow-clear />
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
          <a-tag v-if="text === 0" color="green">生效</a-tag>
          <a-tag v-else-if="text === 1" color="orange">已弃用</a-tag>
          <div v-else-if="text === 2" style="display:flex;flex-direction:column;gap:2px">
            <a-progress :percent="record.parseProgress || 0" size="small" style="width:110px;margin:0" />
            <span style="font-size:12px;color:#666;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:150px"
                  :title="record.parseDesc || '解析中'">{{ record.parseDesc || '解析中' }}</span>
          </div>
          <a-tag v-else color="red" style="cursor:pointer"
                 @click="showFailReason(record)">解析失败</a-tag>
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
      <a-spin :spinning="kbLoading">
        <a-table :data-source="kbList" size="small" row-key="id" :pagination="{ pageSize: 8 }"
                 :locale="{ emptyText: '暂无知识块' }"
                 :custom-row="r => ({ onClick: () => openKbDetail(r) })"
                 style="cursor:pointer">
          <a-table-column title="#" dataIndex="chunkIndex" key="chunkIndex" width="50" />
          <a-table-column title="标题" dataIndex="title" key="title" ellipsis />
          <a-table-column title="内容摘要" key="snippet">
            <template #default="{ record }">{{ (record.content || '').replace(/\s+/g, ' ').slice(0, 80) }}</template>
          </a-table-column>
          <a-table-column title="操作" key="action" width="120">
            <template #default="{ record }">
              <a-button type="link" size="small" @click.stop="openKbEdit(record)">编辑</a-button>
              <a-popconfirm title="确定删除该知识块？向量将同步移除" ok-text="删除" cancel-text="取消"
                            @confirm.stop="delKnowledge(record.id)">
                <a-button type="link" danger size="small" @click.stop>删除</a-button>
              </a-popconfirm>
            </template>
          </a-table-column>
        </a-table>
      </a-spin>
    </a-modal>

    <!-- 知识块编辑弹窗（title/content；图片保留） -->
    <a-modal v-model:open="kbEditVisible" title="编辑知识块" :footer="null" width="640">
      <a-form layout="vertical">
        <a-form-item label="标题">
          <a-input v-model:value="kbEditForm.title" maxlength="200" placeholder="知识块标题" />
        </a-form-item>
        <a-form-item label="内容">
          <a-textarea v-model:value="kbEditForm.content" :rows="10" placeholder="知识块正文" />
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

    <!-- 知识块详情 -->
    <a-modal v-model:open="kbDetailVisible" :title="kbDetail?.title || '知识块详情'" :footer="null" width="720">
      <div style="white-space:pre-wrap;font-size:13px;line-height:1.7">{{ kbDetail?.content }}</div>
      <div v-if="kbDetail?.images?.length" style="display:flex;flex-wrap:wrap;gap:8px;margin-top:12px">
        <img v-for="(u, ui) in kbDetail.images" :key="ui" :src="resolveImg(u)"
             style="width:120px;border-radius:6px;border:1px solid #eee" :alt="'知识块图片' + (ui + 1)" @error="onImgError" />
      </div>
    </a-modal>
  </a-card>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { UploadOutlined, DeleteOutlined } from '@ant-design/icons-vue'
import { listDocuments, uploadDocumentsBatch, updateDocumentStatus, reparseDocument, deleteDocument,
         batchDeleteDocuments, batchUpdateDocumentStatus, getDocumentStats, listKnowledgeByDoc, getKnowledgeDetail,
         updateKnowledge, deleteKnowledge, listDocumentVersions, rollbackDocument,
         getRuntimeConfig } from '../api'

// 知识块预览：图片 URL 兼容（/ai/ 前缀走 /proxy）
const resolveImg = u => u.startsWith('http') ? u : '/proxy' + u.replace(/^\/ai/, '')

// 图片加载兜底：加载失败替换为灰底占位图（签名过期/文件缺失等场景避免裂图）
const FALLBACK_IMG = 'data:image/svg+xml;utf8,' + encodeURIComponent(
  '<svg xmlns="http://www.w3.org/2000/svg" width="200" height="120"><rect width="100%" height="100%" fill="#f5f5f5"/><text x="50%" y="50%" fill="#999" font-size="14" text-anchor="middle" dominant-baseline="middle">图片加载失败</text></svg>')
const onImgError = e => { e.target.onerror = null; e.target.src = FALLBACK_IMG }

// 知识块预览状态
const kbVisible = ref(false)
const kbLoading = ref(false)
const kbList = ref([])
const kbDocName = ref('')
const kbDetailVisible = ref(false)
const kbDetail = ref(null)
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
const openKbEdit = row => {
  kbEditForm.value = { id: row.id, title: row.title || '', content: row.content || '' }
  kbEditVisible.value = true
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
const selectedKeys = ref([])
let pollTimer = null

onMounted(() => { fetchList(); loadUploadCfg() })
onUnmounted(() => { if (pollTimer) clearInterval(pollTimer) })

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
    const r = await uploadDocumentsBatch(files, pct => { uploadPercent.value = pct })
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
</style>
