<template>
  <!-- 关系详情抽屉:点边打开;确认/否决/删除后 emit changed 由父级处理(RelationGraph 页与 TableDetail ER 页签共用:
       确认=回源刷新图;否决/删除=父级就地从内存图数据剔除该边,画布 watch 整体重绘) -->
  <el-drawer
    :model-value="modelValue"
    title="关系详情"
    size="420px"
    @update:model-value="(v) => emit('update:modelValue', v)"
  >
    <template v-if="edge">
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="一端(唯一侧)">{{ edge.oneTable }}.{{ edge.oneColumn }}</el-descriptions-item>
        <el-descriptions-item label="多端(重复侧)">{{ edge.manyTable }}.{{ edge.manyColumn }}</el-descriptions-item>
        <el-descriptions-item label="基数">
          <el-tag size="small" :type="edge.cardinality === 'SUSPECT_MANY_TO_MANY' ? 'danger' : 'primary'">
            {{ cardinalityText(edge.cardinality) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag size="small" :type="statusTagType(edge.status)">{{ statusText(edge.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="来源">{{ sourceText(edge.source) }}</el-descriptions-item>
        <el-descriptions-item label="置信度">{{ confidenceText(edge.confidence) }}</el-descriptions-item>
        <el-descriptions-item label="值交集率">
          {{ edge.overlapRatio === null || edge.overlapRatio === undefined ? '-' : `${Math.round(edge.overlapRatio * 100)}%` }}
        </el-descriptions-item>
        <el-descriptions-item v-if="edge.remark" label="备注">{{ edge.remark }}</el-descriptions-item>
      </el-descriptions>
      <div class="drawer-actions">
        <el-button v-if="edge.status !== 'CONFIRMED'" type="primary" :loading="opLoading" @click="doConfirm">确认</el-button>
        <el-button v-if="edge.status !== 'REJECTED'" type="warning" :loading="opLoading" @click="doReject">否决</el-button>
        <el-button type="danger" :loading="opLoading" @click="doDelete">删除</el-button>
      </div>
    </template>
  </el-drawer>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { ElMessage } from '../utils/notify'
import { confirmRelation, deleteRelation, rejectRelation } from '../api'

// 关系详情抽屉(共用组件):展示单条关系(含疑似多对多 remark 原因),承载确认/否决/删除三态操作;
// 操作成功后 emit changed,父级处理(确认=回源刷新图;否决/删除=就地剔除内存中的边后画布整体重绘;否决/删除同时关闭抽屉)
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  // 当前点中的边(TableRelation);null 时抽屉内容为空
  edge: { type: Object, default: null }
})

// changed:关系状态/存续发生变化(确认/否决/删除成功),父级刷新或就地剔除后重绘图
const emit = defineEmits(['update:modelValue', 'changed'])

const opLoading = ref(false)

async function doConfirm() {
  opLoading.value = true
  try {
    await confirmRelation(props.edge.id)
    ElMessage.success('已确认该关系')
    emit('changed', { action: 'confirm' })
  } finally {
    opLoading.value = false
  }
}

async function doReject() {
  opLoading.value = true
  try {
    await rejectRelation(props.edge.id)
    ElMessage.success('已否决该关系;再次推导命中时会重新验证')
    emit('update:modelValue', false) // 否决的关系不再出现在图上
    emit('changed', { action: 'reject' })
  } finally {
    opLoading.value = false
  }
}

async function doDelete() {
  try {
    await ElMessageBox.confirm('删除后该关系不再保留(误删可重新推导找回),确定删除?', '删除关系', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch { return }
  opLoading.value = true
  try {
    await deleteRelation(props.edge.id)
    ElMessage.success('已删除')
    emit('update:modelValue', false)
    emit('changed', { action: 'delete' })
  } finally {
    opLoading.value = false
  }
}

function cardinalityText(c) {
  return { ONE_TO_ONE: '一对一 (1:1)', ONE_TO_MANY: '一对多 (1:N)', SUSPECT_MANY_TO_MANY: '疑似多对多' }[c] || c
}

function statusText(s) {
  return { CANDIDATE: '候选', CONFIRMED: '确认', REJECTED: '否决' }[s] || s
}

function statusTagType(s) {
  return { CANDIDATE: 'warning', CONFIRMED: 'success', REJECTED: 'info' }[s] || 'info'
}

function sourceText(s) {
  return { NAME_MATCH: '名字匹配', SEMANTIC: '语义匹配', MANUAL: '人工补充' }[s] || s
}

function confidenceText(c) {
  return { HIGH: '高', MEDIUM: '中', LOW: '低' }[c] || '-'
}
</script>

<style scoped>
/* label 列按内容展开不换行(抽屉宽有限,长字段值列会挤压 label 列导致「一端(唯一侧)」等折行) */
:deep(.el-descriptions__label) {
  white-space: nowrap;
}
.drawer-actions {
  margin-top: 16px;
  display: flex;
  gap: 12px;
}
.drawer-actions :deep(.el-button) {
  margin-left: 0;
}
</style>
