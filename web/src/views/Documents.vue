<template>
  <a-card title="文档管理">
    <template #extra>
      <a-upload :before-upload="upload" :show-upload-list="false" accept=".docx">
        <a-button type="primary" :loading="uploading">
          <upload-outlined /> {{ uploading ? '上传中...' : '上传文档' }}
        </a-button>
      </a-upload>
    </template>

    <a-table :columns="cols" :data-source="list" :loading="loading" row-key="id" :pagination="{ pageSize: 10 }">
      <template #bodyCell="{ column, record, text }">
        <template v-if="column.key === 'status'">
          <a-tag :color="text === 0 ? 'green' : 'red'">{{ text === 0 ? '生效' : '已弃用' }}</a-tag>
        </template>
        <template v-else-if="column.key === 'fileSize'">{{ fmtSize(text) }}</template>
        <template v-else-if="column.key === 'createTime'">{{ fmtTime(text) }}</template>
        <template v-else-if="column.key === 'action'">
          <a-popconfirm title="确定删除？" @confirm="del(record.id)">
            <a-button type="link" danger><delete-outlined /> 删除</a-button>
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

const cols = [
  { title: '文件名', dataIndex: 'fileName', key: 'fileName' },
  { title: '描述', dataIndex: 'description', key: 'description' },
  { title: '知识片段数', dataIndex: 'chunkCount', key: 'chunkCount', width: 120 },
  { title: '文件大小', dataIndex: 'fileSize', key: 'fileSize', width: 120 },
  { title: '状态', dataIndex: 'status', key: 'status', width: 90 },
  { title: '上传时间', dataIndex: 'createTime', key: 'createTime', width: 180 },
  { title: '操作', key: 'action', width: 100 }
]

const list = ref([])
const loading = ref(false)
const uploading = ref(false)

onMounted(fetchList)

async function fetchList() {
  loading.value = true
  try {
    const r = await listDocuments()
    if (r.success) list.value = r.data || []
  } catch (e) { message.error('获取列表失败') }
  finally { loading.value = false }
}

async function upload(file) {
  if (!file.name.endsWith('.docx')) { message.error('仅支持 .docx'); return false }
  uploading.value = true
  try {
    const r = await uploadDocument(file, file.name)
    if (r.success) { message.success(r.msg || '上传成功'); fetchList() }
    else message.error(r.msg || '上传失败')
  } catch (e) { message.error('上传失败') }
  finally { uploading.value = false }
  return false
}

async function del(id) {
  try {
    const r = await deleteDocument(id)
    if (r.success) { message.success('删除成功'); fetchList() }
    else message.error(r.msg)
  } catch (e) { message.error('删除失败') }
}

const fmtSize = s => !s ? '-' : s < 1024 ? s + ' B' : s < 1048576 ? (s/1024).toFixed(1) + ' KB' : (s/1048576).toFixed(1) + ' MB'
const fmtTime = t => {
  if (!t) return '-'
  const d = new Date(t)
  return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')} ${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`
}
</script>