/**
 * 原路返回:应用内存在上一条浏览历史时 router.back(),从哪个页面进来就回哪个页面;
 * 直接粘贴 URL / 新窗口进入时没有历史记录,回退到兜底路径
 */
export function goBack(router, fallback) {
  if (window.history.state?.back) {
    router.back()
  } else {
    router.push(fallback)
  }
}
