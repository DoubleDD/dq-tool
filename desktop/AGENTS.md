# AGENTS.md — desktop(Compose Desktop 桌面壳)

> **⚠️ 已放弃,不再维护(2026-08)**:Compose Desktop 生态不足、组件缺口太大(基础组件基本都要自绘)、
> 细节处理工作量大、时间不允许。桌面分发已完全转向 B/S + 套壳架构
> (server 安装版浏览器 --app 窗口 / JCEF shell / Tauri 2)。
> 本目录代码仅留档参考,**勿在其上新增功能或投入修复**;以下内容均为历史记录。

> 本文件面向 AI 编码代理。这是 dq-tool 的 Kotlin + Compose Desktop 桌面版,项目文档与代码注释统一使用中文。业务逻辑(dialect/repository/scan/service/license/config)全部在共享内核 `common` 模块,见 common/AGENTS.md;本目录只剩 UI。

## 项目概述

与 Web 版相同的业务:关系型数据库数据质量检测(表级行数/体积估算、字段级 NULL/空串/自定义空值规则统计、大表并发分段扫描、断点续扫、Excel 导出、AI 表说明)。差异在形态:

- **纯 JVM 桌面壳**,无 Web 前端、无 REST 层;UI 直接调用 common 模块的 `ServiceEnv`(服务容器)
- UI 用 JetBrains Compose Multiplatform Desktop + Jewel(IntelliJ 风格主题),多页签模型(平移自原 web/src/stores/tabs.js)
- 无登录/权限控制,仅适合内网单机使用;授权码模块在 common 已就绪,桌面 UI 尚未接(见下文已知差异)

## 技术栈

- Kotlin 2.4、Compose Multiplatform 1.11.1(Gradle 插件)+ Jewel 0.39.1(IntUiTheme/DecoratedWindow)、kotlinx-coroutines(UI 异步/轮询)
- UI 全部使用 Jewel 组件,Material3 与 material-icons-extended 依赖已随 2026-08 Jewel 迁移移除(状态色等少量 androidx.compose.ui.graphics 基础 API 除外)
- 业务依赖全部经 `implementation(project(":common"))` 传入(H2/HikariCP/POI/Jackson2/Flyway/JDBC 驱动等)
- 日志:Logback(logback.xml 在本模块 resources)
- 打包:Compose 插件 `packageDmg/packageMsi/packageDeb`(内嵌 JRE);开发与打包统一用 JBR 25(自动探测 ~/.jdks/jbrsdk-25*)
- 启动 JVM 参数含 `--enable-native-access=ALL-UNNAMED` + `--sun-misc-unsafe-memory-access=allow`:压住 skiko 加载 native 与 Jewel 反射 Unsafe 的 JDK 25 告警(上游最新版行为,见 build.gradle.kts 注释);logback.xml 另把 `#MacPlatformServicesDefaultImpl` 压到 ERROR(JBR 25 上必现的 Jewel macOS 反射失败,只影响标题栏颜色同步)
- 根 Gradle wrapper 9.2.1,JDK toolchain 25

## 项目结构

```
build.gradle.kts           Compose/Jewel/协程依赖与打包配置;业务依赖来自 project(":common")
src/main/kotlin/com/example/dq/
  Main.kt                  入口:ServiceEnv(AppConfig.load()) → Compose 窗口
  ui/
    App.kt                 页签框架
    TabsModel.kt + Screen.kt   多页签 + 页签内下钻导航(含回退栈)
    theme/                 Jewel(IntUiTheme)主题装配(AppTheme)+ 状态色(对齐 Element Plus 配色,LocalStatusColors 跟随明暗;StatusSuccess 等顶层常量保留给非组合场景);圆角令牌也在此(ControlCorner/CardCorner 等,控件 8dp、徽章 6dp、卡片/弹窗 12dp,经 ComponentStyling.dark/light 覆盖 Jewel 组件 metrics,自绘区块直接引用常量);层级配色 LevelColors/LocalLevels(页面底色比容器低一层)+ floatingSurface 修饰符(柔和投影 + 面板底色 + 圆角,卡片/表格/弹窗的浮起容器统一走它,不用硬边框)
    components/            全部已迁移 Jewel:共用件 JewelDialog(含 ConfirmDialog)/LinearProgress/InlineBanner,业务件 DataTable/StatusTag/JobTimeline/ExportButton/AiConfigDialog/Common/Format
    views/                 Datasources/Dashboard/Schemas/Tables/Scans/ScanDetail/TableColumns
src/main/resources/
  logback.xml              桌面版日志配置
src/main/composeResources/ 字体等 Compose 资源
```

## 构建与测试

```bash
./gradlew :desktop:run / :desktop:packageDmg   # 在仓库根目录执行;本模块无测试,业务测试在 :common
```

## 框架知识查证纪律(重要)

AI 代理对 Compose Multiplatform / Jewel 的**最新版本特性知识滞后**,使用这两个框架的 API 时必须查证,不得凭记忆写:

1. **签名级问题以 Gradle 缓存的 sources jar 为最高事实源**(就是你编译用的那个版本):
   `~/.gradle/caches/modules-2/files-2.1/org.jetbrains.jewel/` 与 `org.jetbrains.compose*` 下找到对应版本的 sources jar,`unzip -p` 读源码核实函数签名、参数默认值、实验注解(如 Tooltip 的 TooltipPlacement 需要 @OptIn(ExperimentalFoundationApi::class))
2. **用法/设计意图查本地文档存档** `docs/compose-multiplatform/`(源自官方文档仓库 JetBrains/kotlin-multiplatform-dev-docs,含桌面组件、鼠标/右键菜单/滚动条/工具提示/窗口管理、版本兼容矩阵、1.11/1.12 新特性;可用 scripts 下的命令或手动重新下载更新)
3. 在线文档(可能需 JS 渲染,优先用上面两个本地来源):https://kotlinlang.org/docs/multiplatform/get-started.html;Jewel 的文档与变更见其源码仓库 https://github.com/JetBrains/jewel
4. 版本升级后(改 build.gradle.kts 的 CMP/Jewel 版本)必须重读 `docs/compose-multiplatform/whats-new-*.md` 对应版本,并重新核实受影响 API

## 代码约定

- 注释、提交信息、文档全部使用中文;代码标识符用英文
- 本模块只写 UI:不得在此新增业务逻辑/ SQL / HTTP 调用,业务需求一律改 common
- UI:服务调用全部阻塞,必须 `withContext(Dispatchers.IO)`;轮询用 `LaunchedEffect { while(isActive){ load(); delay(...) } }`,切走页签自动停止
- 页签导航:固定页签 home/dashboard;数据源、扫描任务各占一个可关闭页签;页签内用 `TabsModel.navigate/back` 下钻
- 跨模块 smart cast:common 的 data class 属性在另一模块,`var` 属性不能 smart cast,先取局部 `val` 再判空使用
- data/ 目录含连接信息与扫描结果,不应提交或外发(见 desktop/.gitignore)

## 与 Web 版的已知功能差异

- 无授权码激活界面(common 的 LicenseService 已可用,待接 UI)
- 导出列选择对话框保留(ExportButton);Scans 页导出为默认全列
- 表格排序仅 Tables/TableColumns 页支持(自绘 SortableTable)
- 无浏览器/端口避让逻辑;卡片无数据库 SVG 图标(文字标签代替)
