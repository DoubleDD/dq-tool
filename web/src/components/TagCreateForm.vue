<template>
  <!-- 打标弹窗的就地新建区:字段与「标记统计」页新建标记弹窗统一(名称/颜色/类型/描述),
       输入框不带 label,说明走 placeholder;类型的说明挂在 radio-group 的 title 上 -->
  <div>
    <div class="tag-edit-row">
      <el-input v-model="form.name" placeholder="新标记名称" maxlength="50" style="width: 220px" @keyup.enter="create" />
      <el-color-picker v-model="form.color" :predefine="presetColors" />
      <el-radio-group v-model="form.tagType" title="类型:仅用于人工打标 / 可用于AI打标(AI 自动打标候选)">
        <el-radio-button value="MANUAL">仅用于人工打标</el-radio-button>
        <el-radio-button value="AI">可用于AI打标</el-radio-button>
      </el-radio-group>
      <el-button type="primary" :loading="creating" @click="create">新建</el-button>
    </div>
    <div class="tag-edit-row">
      <el-input
        v-model="form.description"
        placeholder="描述(可选,供 AI 自动打标理解标记含义)"
        maxlength="500"
        type="textarea"
        :rows="4"
      />
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { ElMessage } from '../utils/notify'
import request from '../api'

// 新建成功后由父组件刷新标记列表
const emit = defineEmits(['created'])

const creating = ref(false)
// 默认「仅用于人工打标」,与「标记统计」页新建弹窗一致
const form = reactive({ name: '', color: '#409EFF', tagType: 'MANUAL', description: '' })

// 预设色板(与「标记统计」页一致)
const presetColors = ['#409EFF', '#67C23A', '#E6A23C', '#F56C6C', '#909399', '#9B59B6', '#16A085', '#D35400']

function reset() {
  form.name = ''
  form.description = ''
  form.tagType = 'MANUAL'
}

async function create() {
  const name = form.name.trim()
  if (!name) {
    ElMessage.warning('标记名称不能为空')
    return
  }
  creating.value = true
  try {
    const tag = await request.post('/tags', {
      name,
      color: form.color || '#409EFF',
      description: form.description.trim() || null,
      tagType: form.tagType
    })
    reset()
    ElMessage.success('已创建')
    // 回传新标记:打标场景下新建的基本就是要打的,父组件刷新列表后自动勾选
    emit('created', tag)
  } finally {
    creating.value = false
  }
}

// 父组件在弹窗打开时调用,清掉上次残留的输入
defineExpose({ reset })
</script>

<style scoped>
.tag-edit-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 10px;
}
</style>
