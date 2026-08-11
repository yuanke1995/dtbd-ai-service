<template>
  <a-card title="文档管理">
    <template #extra>
      <div style="display:flex;align-items:center;gap:12px">
        <a-input v-model:value="desc" placeholder="文档描述（可选，仅单文件上传时生效）" style="width:220px" allow-clear />
        <a-upload :before-upload="beforeUpload" :show-upload-list="false" accept=".docx,.pdf,.xlsx" multiple :disabled="uploading">
          <a-button type="primary" :loading="uploading">
            <upload-outlined /> {{ uploading ? '上传中...' : '上传文档' }}
          </a-button>
        </a-upload>
      </div>
      <a-progress v-if="uploading" :percent="uploadPercent" size="small" style="margin-top:8px" />
    </template>

    <a-table
      :columns="cols" :data-source="list" :loading="loading" row-key="id"
      :pagination="{ pageSize: 10 }"
      :locale="{ emptyText: '暂无文档，点击右上角上传 .docx / .pdf / .xlsx' }"
    >
      <template #bodyCell="{ column, record, text }">
        <template v-if="column.key === 'status'">
          <a-tag v-if="text === 0" color="green">生效</a-tag>
          <a-tag v-else-if="text === 1" color="orange">已弃用</a-tag>
          <a-tag v-else-if="text === 2" color="processing"><a-spin size="small" style="margin-right:4px" />解析中</a-tag>
          <a-tag v-else color="red" style="cursor:pointer"
                 @click="showFailReason(record)">解析失败</a-tag>
        </template>
        <template v-else-if="column.key === 'fileSize'">{{ fmtSize(text) }}</template>
        <template v-else-if="column.key === 'createTime'">{{ fmtTime(text) }}</template>
        <template v-else-if="column.key === 'action'">
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
  </a-card>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { UploadOutlined, DeleteOutlined } from '@ant-design/icons-vue'
import { listDocuments, uploadDocumentsBatch, updateDocumentStatus, reparseDocument, deleteDocument } from '../api'

const MAX_SIZE = 50 * 1024 * 1024 // 与后端 multipart 限制一致
const ALLOWED = ['docx', 'pdf', 'xlsx']

const cols = [
  { title: '文件名', dataIndex: 'fileName', key: 'fileName', ellipsis: true },
  { title: '类型', dataIndex: 'fileType', key: 'fileType', width: 70 },
  { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true },
  { title: '知识片段数', dataIndex: 'chunkCount', key: 'chunkCount', width: 110 },
  { title: '文件大小', dataIndex: 'fileSize', key: 'fileSize', width: 110 },
  { title: '状态', dataIndex: 'status', key: 'status', width: 100 },
  { title: '上传时间', dataIndex: 'createTime', key: 'createTime', width: 170 },
  { title: '操作', key: 'action', width: 180 }
]

const list = ref([])
const loading = ref(false)
const uploading = ref(false)
const uploadPercent = ref(0)
const deletingId = ref('')
const reparsingId = ref('')
const desc = ref('')
let pollTimer = null

onMounted(fetchList)
onUnmounted(() => { if (pollTimer) clearInterval(pollTimer) })

async function fetchList() {
  loading.value = true
  try {
    const r = await listDocuments()
    if (r.success) {
      list.value = r.data || []
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
    return !ALLOWED.includes(ext) || f.size > MAX_SIZE
  })
  if (bad) {
    message.error(`${bad.name} 不支持或超过 50MB（支持 docx/pdf/xlsx）`)
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
