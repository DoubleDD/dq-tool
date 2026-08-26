// 数据源列表变更(新增/编辑/删除/导入,含分组调整)广播:
// 侧边栏(App.vue)据此即时刷新菜单,不等下次路由进入或重新展开「数据源」子菜单
export const DS_LIST_CHANGED_EVENT = 'dq-ds-list-changed'

export function notifyDsListChanged() {
  window.dispatchEvent(new Event(DS_LIST_CHANGED_EVENT))
}
