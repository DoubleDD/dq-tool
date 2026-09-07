import { h } from 'vue'
import { ElMessageBox } from 'element-plus'
import request from '../api'

/**
 * 扫描提交前的 AI 可用性校验(生成表说明/自动打标勾选时调用):
 * 1. 先查配置完整性(GET /ai-config 的 available,秒回);
 * 2. 配置完整再实测连通(POST /ai-config/test 传 {},后端按已存生效配置实测);
 * 3. 任一步失败弹确认框:「继续扫描」照常提交(AI 功能由后端静默跳过),「取消」留在弹窗。
 * 未勾选任何 AI 功能时直接放行。返回 true 表示继续提交扫描。
 */
export async function confirmAiUsable({ autoTag, genDoc }) {
  if (!autoTag && !genDoc) return true
  const features = [genDoc && '生成表说明', autoTag && '自动打标'].filter(Boolean).join('、')

  // summary: 一句话结论;detail: 辅助说明/可读错误,可空
  let summary = ''
  let detail = ''
  const cfg = await request.get('/ai-config', { _silent: true }).catch(() => null)
  if (!cfg?.available) {
    summary = 'AI 未配置或配置不完整'
    detail = '可前往「系统设置」完成大模型接口配置后再使用 AI 功能。'
  } else {
    try {
      // 实测单独收窄超时,避免 AI 端点假死时提交按钮长时间转圈
      await request.post('/ai-config/test', {}, { _silent: true, timeout: 15000 })
    } catch (err) {
      summary = 'AI 配置无效或服务不可用'
      detail = readableError(err)
    }
  }
  if (!summary) return true

  try {
    await ElMessageBox.confirm(
      h('div', { style: 'line-height:1.6' }, [
        h('p', { style: 'margin:0 0 4px;font-weight:600' }, summary),
        detail && h('div', {
          style: [
            'margin:0 0 12px',
            'padding:8px 10px',
            'font-size:12px',
            'line-height:1.5',
            'color:var(--el-text-color-secondary)',
            'background:var(--el-fill-color-light)',
            'border:1px solid var(--el-border-color-lighter)',
            'border-radius:6px',
            'overflow-wrap:anywhere',
            'max-height:120px',
            'overflow-y:auto'
          ].join(';')
        }, detail),
        h('p', { style: 'margin:0' }, `本次扫描将跳过 AI 相关功能(${features})。是否继续扫描?`)
      ]),
      'AI 不可用',
      {
        type: 'warning',
        confirmButtonText: '继续扫描',
        cancelButtonText: '取消',
        closeOnPressEscape: false
      }
    )
    return true
  } catch {
    return false
  }
}

/**
 * 把后端错误消息整理成可展示的一句话:
 * 消息里常内嵌上游接口返回的 JSON 错误体(可能被后端截断),
 * 能解析则提取其中的 message 字段,否则丢掉半截 JSON 只留头部;最终截断过长文本。
 */
function readableError(err) {
  const raw = err.response?.data?.message || err.message || '连接失败'
  let msg = raw
  const jsonStart = raw.indexOf('{')
  if (jsonStart >= 0) {
    const head = raw.slice(0, jsonStart).trim().replace(/[:：]\s*$/, '')
    try {
      const body = JSON.parse(raw.slice(jsonStart))
      const inner = body?.error?.message || body?.message
      msg = inner ? `${head}:${inner}` : head
    } catch {
      // JSON 可能被后端截断导致解析失败,尝试正则提取 message 字段,仍不行则只留头部
      const m = raw.slice(jsonStart).match(/"message"\s*:\s*"((?:[^"\\]|\\.)*)"/)
      msg = m ? `${head}:${m[1].replace(/\\"/g, '"')}` : head
    }
  }
  // 详情区可滚动,截断阈值放宽到 300 字符
  return msg.length > 300 ? `${msg.slice(0, 300)}…` : msg
}
