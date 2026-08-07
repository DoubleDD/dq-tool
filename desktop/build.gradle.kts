import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.compose") version "1.11.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
}

group = "com.example"
version = "0.1.0"

kotlin {
    jvmToolchain(25)
}

dependencies {
    // 共享内核:业务逻辑(dialect/repository/scan/service/config)全部在此
    implementation(project(":common"))

    // Compose Desktop
    implementation(compose.desktop.currentOs)
    // compose.* 依赖访问器在 CMP 1.9+ 已弃用,改为直接指定坐标;
    // components-resources 版本须与 org.jetbrains.compose 插件版本保持一致
    // (Material3 / material-icons-extended 依赖已随 Jewel 迁移移除)
    implementation("org.jetbrains.compose.components:components-resources:1.11.1")

    // Jewel(IntelliJ 风格桌面主题;兼容矩阵:CMP 1.11.0 / JDK 25)
    // 版本号后缀 262.* 是 IntelliJ Platform 构建号,非 CMP 版本
    val jewelVersion = "0.39.1-262.9437.29"
    implementation("org.jetbrains.jewel:jewel-int-ui-standalone:$jewelVersion")
    implementation("org.jetbrains.jewel:jewel-int-ui-decorated-window:$jewelVersion")
    // Jewel standalone 运行所需的平台图标资源(下拉箭头、勾选框等),缺了会渲染成品红色方块
    implementation("com.jetbrains.intellij.platform:icons:262.9437.142")

    // 协程(UI 侧异步/轮询)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")

    // 日志实现(logback.xml 在本模块 resources;slf4j-api 由 common 传递)
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
}

// Jewel 的 DecoratedWindow 及字体加载依赖 JetBrains Runtime;
// 开发运行和打包统一使用 JBR(下载:https://github.com/JetBrains/JetBrainsRuntime/releases)
// 自动探测 ~/.jdks 下的 jbrsdk-25*(任意平台后缀),取版本号最大者
val jbrHome = File(System.getProperty("user.home"), ".jdks")
    .listFiles { f -> f.isDirectory && f.name.startsWith("jbrsdk-25") }
    ?.maxByOrNull { it.name }
    // macOS 的 JDK 是 .jdk 包结构,真正的 home 在 Contents/Home 下
    ?.let { dir -> File(dir, "Contents/Home").takeIf { it.isDirectory } ?: dir }

// 只在真正要运行/打包桌面应用时才提示 JBR 缺失,server 构建或普通编译不刷屏
val desktopRunOrPackage = gradle.startParameter.taskNames.any {
    it.startsWith(":desktop:") &&
        (it.contains("run", ignoreCase = true) || it.contains("package", ignoreCase = true))
}

compose {
    resources {
        // 固定生成的 Res 类包名,不随模块改名而变化
        packageOfResClass = "com.example.dq.generated.resources"
    }
}

compose.desktop {
    application {
        mainClass = "com.example.dq.MainKt"

        if (jbrHome != null) {
            javaHome = jbrHome.absolutePath
        } else if (desktopRunOrPackage) {
            logger.warn("JBR 未安装于 ~/.jdks(jbrsdk-25*),DecoratedWindow 将无法运行;请从 JetBrainsRuntime releases 下载对应平台的 JBR 25")
        }

        // 注意:JBR 的 Wayland 工具包(WLToolkit)与当前 Skiko 不兼容(Can't lock DrawingSurface,
        // 软件渲染同样失败),勿开启;Linux 下文本清晰度受 KWin 对 X11 应用的缩放策略影响

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "dq-tool"
            packageVersion = "1.0.0"
            // 安装版数据目录固定为 ~/.dq-tool/data(与原 jpackage 约定一致)
            // 通过 -Ddq.data.dir 注入;直接 gradlew run 时默认 ./data
            modules("java.sql")
            // 打包产物统一使用 ZGC(JDK 25 默认即为分代模式,无需其他 GC 参数)
            jvmArgs("-XX:+UseZGC")
            // JDK 25 对第三方库(skiko 加载 native、Jewel 反射 Unsafe)的告警:
            // 均为上游库行为且最新版仍如此,显式放行以保持控制台干净
            jvmArgs("--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

// compose 插件在 afterEvaluate 中才注册 run 任务(类型即 JavaExec),
// 故这里同样放在 afterEvaluate 里配置;开发运行(:desktop:run)同样使用 ZGC
afterEvaluate {
    tasks.named<JavaExec>("run") {
        jvmArgs("-XX:+UseZGC")
        // 同 nativeDistributions:压住 skiko native 加载与 Jewel Unsafe 反射的 JDK 25 告警
        jvmArgs("--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
