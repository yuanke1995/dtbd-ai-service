<template>
  <a-card title="文档管理">
    <template #extra>
      <div style="display:flex;align-items:center;gap:12px">
        <a-input v-model:value="desc" placeholder="文档描述（可选）" style="width:220px" allow-clear />
        <a-upload :before-upload="upload" :show-upload-list="false" accept=".docx" :disabled="uploading">
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
      :locale="{ emptyText: '暂无文档，点击右上角上传 .docx 操作手册' }"
    >
      <template #bodyCell="{ column, record, text }">
        <template v-if="column.key === 'status'">
          <a-tag :color="text === 0 ? 'green' : 'red'">{{ text === 0 ? '生效' : '已弃用' }}</a-tag>
        </template>
        <template v-else-if="column.key === 'fileSize'">{{ fmtSize(text) }}</template>
        <template v-else-if="column.key === 'createTime'">{{ fmtTime(text) }}</template>
        <template v-else-if="column.key === 'action'">
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
import { ref, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { UploadOutlined, DeleteOutlined } from '@ant-design/icons-vue'
import { listDocuments, uploadDocument, deleteDocument } from '../api'

const MAX_SIZE = 50 * 1024 * 1024 // 与后端 multipart 限制一致

const cols = [
  { title: '文件名', dataIndex: 'fileName', key: 'fileName', ellipsis: true },
  { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true },
  { title: '知识片段数', dataIndex: 'chunkCount', key: 'chunkCount', width: 120 },
  { title: '文件大小', dataIndex: 'fileSize', key: 'fileSize', width: 120 },
  { title: '状态', dataIndex: 'status', key: 'status', width: 90 },
  { title: '上传时间', dataIndex: 'createTime', key: 'createTime', width: 180 },
  { title: '操作', key: 'action', width: 110 }
]

const list = ref([])
const loading = ref(false)
const uploading = ref(false)
const uploadPercent = ref(0)
const deletingId = ref('')
const desc = ref('')

onMounted(fetchList)

async function fetchList() {
  loading.value = true
  try {
    const r = await listDocuments()
    if (r.success) list.value = r.data || []
  } catch (e) { message.error(e.message || '获取列表失败') }
  finally { loading.value = false }
}

async function upload(file) {
  if (!file.name.toLowerCase().endsWith('.docx')) { message.error('仅支持 .docx 格式'); return false }
  if (file.size > MAX_SIZE) { message.error('文件超过 50MB ���制'); return false }
  uploading.value = true
  uploadPercent.value = 0
  try {
    const r = await uploadDocument(file, desc.value.trim() || undefined, pct => {
      uploadPercent.value = pct
    })
    if (r.success) {
      message.success(r.msg || '上传成功')
      desc.value = ''
      fetchList()
    } else message.error(r.msg || '上传失败')
  } catch (e) { message.error(e.message || '上传失败') }
  finally { uploading.value = false }
  return false
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

const fmtSize = s => !s ? '-' : s < 1024 ? s + ' B' : s < 1048576 ? (s/1024).toFixed(1) + ' KB' : (s/1048576).toFixed(1) + ' MB'
const fmtTime = t => {
  if (!t) return '-'
  const d = new Date(t)
  return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')} ${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`
}
</script>
