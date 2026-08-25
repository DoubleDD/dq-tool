// 数据源收藏:前端本地偏好,按数据源 id 数组存 localStorage(下标即收藏先后,越靠后越新)
// 主界面卡片与侧边栏菜单共用同一排序:收藏的在前,同收藏按收藏时间倒序(后收藏的在前)
const KEY = 'dq-ds-favorites'
// 收藏变更时广播,侧边栏(App.vue)据此即时刷新排序,不等下次路由进入
export const DS_FAVORITES_CHANGED_EVENT = 'dq-ds-favorites-changed'

export function loadDsFavorites() {
  try {
    return JSON.parse(localStorage.getItem(KEY) || '[]')
  } catch {
    return []
  }
}

export function saveDsFavorites(ids) {
  localStorage.setItem(KEY, JSON.stringify(ids))
  window.dispatchEvent(new Event(DS_FAVORITES_CHANGED_EVENT))
}

/**
 * 收藏优先 + 收藏时间倒序(后收藏的在前);未收藏的保持原有相对顺序
 * @param {Array} arr 数据源列表
 * @param {Array} favorites 收藏 id 数组(下标越大的越晚收藏)
 */
export function sortDsByFavorite(arr, favorites) {
  // id 统一转字符串做键,避免数字/字符串类型不一致导致匹配失败
  const favIndex = new Map(favorites.map((id, i) => [String(id), i]))
  return [...arr].sort((a, b) => {
    const ai = favIndex.has(String(a.id)) ? favIndex.get(String(a.id)) : -1
    const bi = favIndex.has(String(b.id)) ? favIndex.get(String(b.id)) : -1
    if (ai >= 0 && bi >= 0) return bi - ai
    return Number(bi >= 0) - Number(ai >= 0)
  })
}
