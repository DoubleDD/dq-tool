# JVM 桌面 UI 框架调研对比

> 调研时间:2026-08。调研动机:项目在 2026-08 已放弃 Compose Desktop(生态不足、基础组件需自绘)与 JCEF(体积约 200MB)两条桌面 UI 路线,本文系统梳理 JVM 上主流桌面 UI 工具包,回答「组件库最全 / 自绘最简单 / 样式最丰富 / 性能最强」分别是哪个,供后续桌面端技术选型参考。
>
> 参评对象:**Swing、JavaFX(OpenJFX)、SWT/JFace、Compose Multiplatform(Compose Desktop)**。Web 套壳类(JCEF、Tauri、Electron+JVM 后端)不属于"JVM 桌面 UI 框架",只在文末简要对照。

## 一句话结论

| 维度 | 赢家 | 说明 |
|---|---|---|
| 组件库最全 | **JavaFX**(现代场景)/ **Swing**(历史存量) | JavaFX 内置控件全 + ControlsFX/MaterialFX/TilesFX 等活跃第三方;Swing 胜在二十多年积累(JTable/JTree 等复杂控件久经考验) |
| 自绘最简单 | **Compose Desktop** | Canvas + DrawScope 声明式 API,心智负担最低;其次是 JavaFX Canvas/Region |
| 样式最丰富 | **JavaFX** | 完整 CSS 引擎(伪类、继承、动画),主题就是换 CSS 文件;Compose 的 Modifier/Theme 紧随其后 |
| 性能最强 | 分场景:**SWT**(标准原生控件)/ **Compose Desktop**(重度自绘+动画) | 原生控件开销最小的是 SWT;GPU 加速自绘管线最强的是 Skia(Compose) |
| 打包体积最小 | **Swing** | 随 JDK 自带,零额外体积 |
| 桌面端最不成熟 | **Compose Desktop** | 官方至今没有 Table/Tree 组件([compose-multiplatform#344](https://github.com/JetBrains/compose-multiplatform/issues/344)) |

---

## 1. 四个框架基本面

### Swing(1997-,JDK 内置)

- **渲染**:Java2D 软件渲染为主,轻量组件全部自绘,跨平台像素级一致。
- **状态**:功能冻结(feature-frozen),只修 bug 不加新特性,但随 JDK 永远存在、永远稳定。官方定位已是"维护遗留系统"。
- **现代外观靠 FlatLaf**:FlatLaf(开源,IntelliJ IDEA 自 2020 起内置使用)提供现代扁平主题,支持 `JComponent.putClientProperty("JComponent.roundRect", true)` 这类 client property 做圆角/聚焦框/错误描边等细粒度定制,不用重写 `paintComponent` 也能获得 2025 年水准的外观。([FlatLaf Customizing](https://www.formdev.com/flatlaf/customizing/)、[Client Properties](https://www.formdev.com/flatlaf/client-properties/))
- **组件库**:JDK 内置 JTable、JTree、JFormattedTextField、JSpinner 等全部高级控件;第三方生态历史最深(SwingX、MigLayout、JIDE 商业组件、JFreeChart、RSyntaxTextArea……)。**论"现成组件总数量",Swing 仍然第一**,只是新增组件已基本停止。

### JavaFX / OpenJFX(2011-,Java 9 起独立模块)

- **渲染**:Prism 硬件加速管线(Windows Direct3D / macOS Metal / Linux OpenGL ES),保留模式场景图(Scene Graph),重绘由框架自动做脏区域管理。
- **状态**:OpenJFX 社区活跃,Gluon 商业支持,Oracle 官方 FAQ 定位其为 Swing 的继任者。**是"新建 JVM 桌面项目"目前最普遍的推荐**。([TheServerSide 对比](https://www.theserverside.com/tip/Swing-vs-JavaFX-Compare-Java-GUI-frameworks))
- **组件库**:内置 TableView、TreeView、TreeTableView、WebView、Charts、Media 等;第三方活跃且质量高:
  - [ControlsFX](https://github.com/controlsfx/controlsfx/) —— 高质量扩展控件集合(对话框、PropertySheet、Notification 等)
  - [MaterialFX](https://github.com/palexdev/materialfx) —— Material Design 全套(重制控件 + 新控件)
  - [AtlantaFX](https://mkpaz.github.io/atlantafx/) —— 现代 CSS 主题集(Primer/Nord/Cupertino 等)+ 少量附加控件
  - [TilesFX](https://github.com/hansolo/tilesfx) —— 仪表盘 tile/数据可视化
  - 汇总索引:[AwesomeJavaFX](https://github.com/mhrimaz/AwesomeJavaFX)
- **样式**:完整 CSS 引擎,支持伪类(`:hover`/`:focused`/自定义)、属性继承、外部样式表、CSS 动画。([JavaFX CSS Reference](https://docs.oracle.com/en/java/java-components/javafx/25/docs/javafx.graphics/javafx/scene/doc-files/cssref.html))**换主题 = 换一份 CSS,是四者中"样式表达能力 × 使用成本"最优的**。
- **自绘**:`Canvas` + `GraphicsContext` 立即模式绘制,或继承 `Region` 重写布局;FXML 声明式布局 + Scene Builder 可视化设计器。

### SWT / JFace(2001-,Eclipse 基金会)

- **渲染**:**直接包装操作系统原生控件**(Win32/Cocoa/GTK),原生控件的性能和外观就是 OS 本身的水准。Eclipse IDE 即 SWT 构建。
- **组件库**:SWT 本体只有基础控件(Button/Table/Tree/StyledText 等,数量明显少于 Swing/JavaFX);复杂控件靠 JFace viewers 和 Nebula 等扩展。Table/Tree 是原生控件,大数据量下有虚拟模式,表现极好。
- **样式**:**最弱**。原生控件不接受自定义样式,只能改字体/前景背景色,做不到圆角、阴影、暗色主题这类现代定制——"样式丰富"与 SWT 的设计哲学冲突。
- **自绘**:`Canvas` + `GC` 手工绘制,无硬件加速保证,Eclipse 官方文档自己就承认手工 GC 绘制性能常落后于原生控件;社区有 [Skia 化 SWT 自绘的实验](https://github.com/swt-initiative31/documents/blob/main/results/custom.md)。自绘体验四者中最繁琐。
- **部署**:每个平台带对应原生库 jar,打包分平台,稍麻烦但体积不大。
- **适合**:需要"看起来像系统应用"的工具(Eclipse RCP 生态),或极端要求原生控件行为的场景。

### Compose Multiplatform / Compose Desktop(JetBrains,2021 桌面稳定版)

- **渲染**:Skia(经 [Skiko](https://github.com/JetBrains/skiko) 绑定),GPU 加速(macOS Metal / Windows DirectX / Linux OpenGL),立即模式全量自绘——所有像素都是自己画的,跨平台完全一致。
- **编程模型**:声明式 + 状态驱动重组(recomposition),Kotlin 专属;可与 Swing 双向互操作(`ComposePanel` / `SwingPanel`,[官方文档](https://kotlinlang.org/docs/multiplatform/compose-desktop-swing-interoperability.html))。
- **组件库**:**桌面端是四者中最弱的**。Material 3 基础组件齐全,但**官方至今没有 Table 和 Tree 组件**([issue #344](https://github.com/JetBrains/compose-multiplatform/issues/344)、[issue #1561](https://github.com/JetBrains/compose-jb/issues/1561)),社区方案(如 sproctor/compose-data-table)功能有限,官方建议用 `SwingPanel` 嵌 JTable 或自己拿 LazyColumn 拼。这正是本项目 2026-08 放弃它的原因,调研结论与当时判断一致。
  - 值得注意的增量:**JetBrains [Jewel](https://github.com/JetBrains/jewel)** —— IntelliJ New UI 风格的 Compose Desktop 组件库(独立应用用 `jewel-int-ui-standalone`,IntelliJ 平台 251.2+ 已内置),补了一部分桌面组件缺口,适合做 IDE 风格工具。
- **样式**:Theme + Modifier 体系,表达能力很强(任何组件可完全重写外观),但**没有 CSS 那样的"外部主题文件"概念**,主题靠代码;写起来灵活,维护大型设计系统时不如 JavaFX CSS 直观。
- **自绘**:**四者中最简单**。`Canvas { drawCircle(...) }` + `DrawScope` API,声明式、可组合、动画 API(animate*AsState / Animatable)与自绘天然一体;还可直接下潜到 Skia shader 做毛玻璃等效果。
- **代价**:打包体积大(JVM + Skiko 原生库,比 JavaFX 更大)、启动偏慢;Skia Ganesh 后端存在运行时 shader 编译,复杂 UI 首次渲染可能有 jank([与 Flutter Impeller 的对比分析](https://guoshuyu.cn/home/wx/extra/si))。

---

## 2. 分维度详评

### 2.1 组件库最全

1. **JavaFX(现代开发实际最优)** —— 内置控件覆盖 95% 桌面需求,第三方生态仍在增长(MaterialFX/AtlantaFX 近两年活跃)。复杂控件(TableView 虚拟化、TreeTableView)质量高。
2. **Swing(存量冠军)** —— 高级控件全部久经考验,第三方历史积累最厚;但没有"新增",商业组件商(如 JIDE)也逐渐转向维护模式。
3. **Compose Desktop** —— Material 基础组件够用,缺 Table/Tree 是硬伤;Jewel 在补,但面向 IDE 风格。
4. **SWT** —— 本体组件最少,靠 JFace/Nebula 补。

### 2.2 自绘最简单

1. **Compose Desktop** —— 声明式 Canvas/DrawScope,和状态、动画一体,心智负担最低。
2. **JavaFX** —— Canvas 立即模式 API 干净;场景图模式下很多时候不用自绘,CSS + 节点组合就能解决。
3. **Swing** —— `paintComponent(Graphics2D)` 模型成熟简单,抗锯齿/变换都要手动开,双缓冲框架已处理;量大但直白。
4. **SWT** —— GC 手工绘制,无硬件加速保证,还要自己处理平台差异,最繁琐。

### 2.3 样式最丰富

1. **JavaFX** —— 真 CSS:外部样式表、伪类、继承、动画,主题即文件;AtlantaFX 证明能做出不输 Web 的现代主题。
2. **Compose Desktop** —— 表达上限其实更高(一切可代码定制),但没有声明式主题文件,全靠代码组织;Modifier 链灵活但复杂界面容易冗长。
3. **Swing + FlatLaf** —— FlatLaf 把 Swing 拉到了"现代好看"的水平,client properties + UIDefaults + `.properties` 主题文件可定制面广,但终归受 L&F 架构约束,做不到任意效果。
4. **SWT** —— 原生控件不接受深度定制,垫底。

### 2.4 性能最强(分场景)

| 场景 | 最强 | 原因 |
|---|---|---|
| 标准控件为主、大表格/大树 | **SWT** | 原生控件,OS 级优化,虚拟模式 Table 百万行无压力 |
| 重度自绘、动画、可视化 | **Compose Desktop(Skia)** | 全链路 GPU 加速,立即模式无场景图开销;注意 shader 冷启动 jank |
| 复杂场景图 + 中等自绘 | **JavaFX(Prism)** | 硬件加速 + 脏区域管理,综合均衡;Canvas 用法不当(如循环 strokeLine 代替 strokePolygon)会明显掉帧([openjfx-dev 讨论](https://mail.openjdk.org/archives/list/openjfx-dev@openjdk.org/message/SNCRTU2XCHRFVU2OQWR5OFKNMNIADSMH/)) |
| 内存/启动/小工具 | **Swing** | 无原生库加载,启动最快,内存占用最小;Java2D 渲染对常规 UI 绰绰有余 |

"性能最强"没有绝对答案:**原生控件赛 SWT 赢,自绘渲染赛 Compose 赢,综合均衡 JavaFX 赢,轻量启动 Swing 赢**。

### 2.5 其他工程因素

| 因素 | Swing | JavaFX | SWT | Compose Desktop |
|---|---|---|---|---|
| 语言 | Java 为主 | Java/Kotlin | Java | **仅 Kotlin** |
| 打包增量 | ≈0(JDK 自带) | +10~20MB/平台 | +几 MB 原生库/平台 | 最大(JVM+Skiko,≈JCEF 的一半量级) |
| 启动速度 | 最快 | 中 | 快 | 最慢 |
| 跨平台一致性 | 像素一致 | 像素一致 | 各平台原生(不一致是特性) | 像素一致 |
| 维护状态 | 冻结但永生 | 活跃(OpenJFX/Gluon) | 活跃(Eclipse) | 活跃但桌面优先级低于 Android |
| 可视化设计器 | 多(NetBeans/IDEA GUI Designer/JFormDesigner) | Scene Builder | WindowBuilder | 无(Preview 注解) |
| 与现有代码互操作 | —— | JFXPanel 可嵌 Swing | 可与 AWT/Swing 桥接 | 与 Swing 双向互操作 |

---

## 3. 对照:Web 套壳路线(非 JVM UI 框架)

| 方案 | 体积 | 备注 |
|---|---|---|
| JCEF | ~200MB | 本项目 2026-08 已因此放弃 |
| Tauri 2 + JVM 后端 | 小(系统 WebView) | 本项目现用路线之一;UI 是 Web 技术栈,不占 JVM UI 框架的名额 |
| 浏览器 --app 窗口 | 0 | 本项目现用路线之一 |

如果目标是"桌面工具、UI 复杂度高",Web 套壳在组件生态(Element Plus 等)上仍然碾压所有 JVM 原生方案——这也是项目当前架构的现实依据。

---

## 4. 对本项目的建议

1. **维持现状(Tauri/--app + Web UI)的结论是稳固的**:JVM 原生四强里,组件生态最接近 Element Plus 水平的 JavaFX,也仍差一个量级;且本项目前端已有完整 Vue 工程,迁移任何 JVM UI 框架都是重写。
2. 如果未来出现"必须纯 JVM 原生 UI"的约束(如客户环境禁 WebView、要求单 jar 零依赖):
   - 首选 **JavaFX + AtlantaFX**(组件最全、样式最丰富、Kotlin 可用);
   - 极简单内部工具可用 **Swing + FlatLaf**(零额外体积、零学习成本、永不过时);
   - **Compose Desktop 仍不推荐**:Table/Tree 缺失的问题至今未解决,与本项目 2026-08 的放弃决策一致;若 reconsider,先评估 Jewel 是否覆盖了缺口。
   - SWT 只在做"另一个 Eclipse"时才值得考虑。

## 参考来源

- [The state of JVM desktop frameworks 系列(Nicolas Fränkel)](https://blog.frankel.ch/state-jvm-desktop-frameworks/1/)
- [Swing vs. JavaFX(TheServerSide)](https://www.theserverside.com/tip/Swing-vs-JavaFX-Compare-Java-GUI-frameworks)
- [A Comprehensive Guide to Java GUI Frameworks(Java Code Geeks, 2025)](https://www.javacodegeeks.com/2025/09/a-comprehensive-guide-to-java-gui-frameworks.html)
- [Oracle JavaFX FAQ](https://www.oracle.com/java/technologies/javafx/faq-javafx.html)
- [JavaFX CSS Reference Guide](https://docs.oracle.com/en/java/java-components/javafx/25/docs/javafx.graphics/javafx/scene/doc-files/cssref.html)
- [FlatLaf 官方文档](https://www.formdev.com/flatlaf/)
- [SWT 自定义绘制调研(swt-initiative31)](https://github.com/swt-initiative31/documents/blob/main/results/custom.md)
- [Compose Multiplatform 缺 Table 组件 issue #344](https://github.com/JetBrains/compose-multiplatform/issues/344)
- [Compose/Swing 互操作官方文档](https://kotlinlang.org/docs/multiplatform/compose-desktop-swing-interoperability.html)
- [JetBrains Jewel](https://github.com/JetBrains/jewel)
- [AwesomeJavaFX](https://github.com/mhrimaz/AwesomeJavaFX)
- [Compose 开发桌面程序的一些问题(掘金)](https://juejin.cn/post/7568690340884103206)
