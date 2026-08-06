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
    // Compose Desktop
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.components.resources)

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

    // 本地 H2 存储 + 连接池
    implementation(libs.h2)
    implementation("com.zaxxer:HikariCP:6.3.0")

    // JSON(null_rules / col_stats / AI 接口)
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.20.0")

    // Excel 导出
    implementation(libs.poi.ooxml)

    // 日志
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    // 7 个目标数据库 JDBC 驱动(版本与 server 模块统一在 gradle/libs.versions.toml 管理)
    runtimeOnly(libs.jdbc.mysql)
    runtimeOnly(libs.jdbc.postgresql)
    runtimeOnly(libs.jdbc.mssql)
    runtimeOnly(libs.jdbc.oracle)
    runtimeOnly(libs.jdbc.dameng)
    runtimeOnly(libs.jdbc.kingbase)
    runtimeOnly(libs.jdbc.oceanbase)

    // 测试
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.mssqlserver)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Jewel 的 DecoratedWindow 及字体加载依赖 JetBrains Runtime;
// 开发运行和打包统一使用 JBR(下载:https://github.com/JetBrains/JetBrainsRuntime/releases)
// 自动探测 ~/.jdks 下的 jbrsdk-25*(任意平台后缀),取版本号最大者
val jbrHome = File(System.getProperty("user.home"), ".jdks")
    .listFiles { f -> f.isDirectory && f.name.startsWith("jbrsdk-25") }
    ?.maxByOrNull { it.name }

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
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
