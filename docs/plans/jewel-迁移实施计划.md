# Jewel 迁移实施计划(desktop 模块)

> 目标:desktop 模块 UI 从 Material3 全面迁移到 Jewel(IntelliJ 风格桌面 UI),消除当前两套主题体系共存的中间态。
> 现状:Jewel `0.39.1-262.9437.29` 已引入(见 `desktop/build.gradle.kts`),`App.kt`(JewelTheme + TabStrip 外壳)与 `DatasourcesView.kt`(表单页,ListComboBox/TextField/RadioButtonRow)已完成迁移,其余 6 个视图 + 5 个组件仍在 Material3 上。
> 原则:纯 UI 层替换,不动 service / scan / repository / dialect 任何一行;迁移期间保持 `./gradlew :desktop:run` 随时可跑。

## 组件缺口与对策(依据 ui/ 全量 import 扫描)

| Material3(当前用量) | Jewel 对策 | 级别 |
|---|---|---|
| Text / Button / OutlinedButton / CircularProgressIndicator / Checkbox / Tooltip | 同名替换(DefaultButton 等),已被 DatasourcesView 验证 | 机械替换 |
| ExposedDropdownMenuBox / DropdownMenuItem | `ListComboBox`(已验证) | 机械替换 |
| OutlinedTextField | `TextField`(已验证) | 机械替换 |
| MaterialTheme / Surface / HorizontalDivider | `JewelTheme` / 主题色直取 / `Divider(Orientation.Horizontal)` | 机械替换 |
| AlertDialog(确认框,4 处) | 无现成件:抽共用自绘 `ConfirmDialog`(`androidx.compose.ui.window.Dialog` + Jewel 样式,DatasourcesView 已有先例) | 自绘,先做 |
| LinearProgressIndicator(5 处) | 无:自绘共用 `LinearProgress`(几十行) | 自绘,先做 |
| SnackbarHost / SnackbarHostState(2 处) | 无:改内联横幅 `InlineBanner`(IntelliJ 风格,页面顶部黄色/红色提示条) | 自绘+交互变更 |
| Switch(1 处,TablesView) | 无:换 `CheckboxRow`,语义不变 | 交互微调 |
| FilterChip(1 处,TablesView) | 0.39 版确认有无 Chip;无则自绘可选中标签 | 自绘,一处 |

表格(DataTable/SortableTable)、JobTimeline 本来就是自绘,只换配色来源,不受影响。

## 实施步骤

### 第 0 步:共用自绘件先行(一切迁移的前提)
- [ ] `components/JewelDialog.kt`:共用 `ConfirmDialog`(标题/正文/确认取消按钮,Jewel 配色),替换全部 4 处 AlertDialog 确认框
- [ ] `components/LinearProgress.kt`:自绘线性进度条(支持确定/不确定两种模式,Jewel 配色)
- [ ] `components/InlineBanner.kt`:页面顶部内联提示条(info/warn/error 三级),替代 Snackbar
- [ ] 配色统一:把 `theme/Theme.kt` 里的 StatusSuccess/StatusDanger 等状态色改为挂到 JewelTheme 的 CompositionLocal 扩展,删掉 Material3 lightColorScheme/darkColorScheme

### 第 1 步:迁移共用组件(视图依赖它们,必须先做)
- [ ] `components/Common.kt`(EmptyHint 等,仅 Text/主题引用)
- [ ] `components/DataTable.kt`(HorizontalDivider/MaterialTheme → Jewel)
- [ ] `components/JobTimeline.kt`(TooltipBox/PlainTooltip → Jewel Tooltip)
- [ ] `components/ExportButton.kt`(AlertDialog 换 ConfirmDialog,Checkbox/Button 换 Jewel)
- [ ] `components/AiConfigDialog.kt`(AlertDialog 换自绘窗,OutlinedTextField → TextField)

### 第 2 步:逐页迁移视图(按依赖从少到多,每页迁移后跑一遍验证)
- [ ] `ScansView.kt`(依赖最少:进度条/按钮/文本)
- [ ] `ScanDetailView.kt`(进度条 + 确认框)
- [ ] `SchemasView.kt`(ExposedDropdownMenuBox → ListComboBox,表单较多)
- [ ] `TableColumnsView.kt`(Snackbar → InlineBanner,表单)
- [ ] `TablesView.kt`(最复杂:FilterChip、Switch、Snackbar 全在此页,最后做)
- [ ] `DashboardView.kt`(AlertDialog/ButtonDefaults,顺带评估看板视觉在 Jewel 下的表现)

### 第 3 步:收尾
- [ ] 删除 `theme/Theme.kt` 的 Material3 主题定义
- [ ] `desktop/build.gradle.kts` 移除 `compose.material3`(materialIconsExtended 视图标使用情况决定)
- [ ] `./gradlew :desktop:run` 全页面手动过一遍:每个页签打开、发起一次扫描、看进度条、触发一次错误提示、深浅色主题各看一遍
- [ ] `./gradlew :desktop:test` 确认无回归(UI 改动理论上不影响测试,但跑一遍兜底)
- [ ] 更新 `desktop/AGENTS.md` 技术栈一节(Material3 → Jewel)

## 验收标准
- 全项目 `grep -r material3 desktop/src` 零命中
- 视觉统一:无 Material 风格组件(圆角大按钮、涟漪、大留白)残留
- 深浅色主题下所有页面可读、状态色语义正确
