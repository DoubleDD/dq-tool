plugins {
    kotlin("jvm")
    application
    id("com.gradleup.shadow")
}

group = "com.example"
version = "0.1.0"

kotlin {
    jvmToolchain(25)
}

// JCEF natives 按构建机器的平台/架构选择,fat jar 只内嵌当前平台(单平台约 100MB);
// 跨平台分发需各自在本机打包(Windows 用 scripts\package-shell-win.bat,Linux 打包脚本暂未实现)
val osName = System.getProperty("os.name").lowercase()
val osArch = System.getProperty("os.arch").lowercase()
val isArm = osArch == "aarch64" || osArch.startsWith("arm")
val jcefPlatform = when {
    osName.contains("win") -> if (isArm) "windows-arm64" else "windows-amd64"
    osName.contains("mac") -> if (isArm) "macosx-arm64" else "macosx-amd64"
    else -> if (isArm) "linux-arm64" else "linux-amd64"
}

dependencies {
    // 复用 server 的 Web 壳(Javalin 路由/内核装配/静态资源),transitively 含 common 业务内核
    implementation(project(":server"))

    // JCEF:me.friwi 的 Maven 中央仓库坐标(2026-05 最新,CEF 146)
    // jcefmaven 是官方接入封装:负责 natives 解压、java.library.path 补丁、macOS framework/子进程路径,
    // transitively 带入 jcef-api(Org.cef.* API);版本与 natives 的 release tag 必须同属一个发布
    implementation(libs.jcefmaven)
    // 内嵌当前平台 natives(jar 根部的 tar.gz),运行时免下载;不声明则首启从 Maven 中央拉取
    runtimeOnly("me.friwi:jcef-natives-$jcefPlatform:${libs.versions.jcef.natives.get()}")
}

application {
    mainClass.set("com.example.dq.shell.MainKt")
}

tasks.jar {
    // 可执行产物由 shadowJar 承担;plain jar 仅留档,避免与 shadowJar 产物同名冲突
    archiveClassifier.set("plain")
}

tasks.shadowJar {
    // 产物:shell/build/libs/dq-tool-shell-<version>.jar(打包脚本按此命名引用)
    archiveBaseName.set("dq-tool-shell")
    archiveClassifier.set("")
    mergeServiceFiles()
    manifest {
        attributes("Main-Class" to "com.example.dq.shell.MainKt")
    }
}

// JCEF 在 JDK 16+ 访问 AWT 内部 API 必需的开放(macOS 三个包都要;非 macOS 加了也无害)
val jcefOpens = listOf(
    "--add-opens", "java.desktop/sun.awt=ALL-UNNAMED",
    "--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED",
    "--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED"
)

tasks.named<JavaExec>("run") {
    // 工作目录固定为仓库根,保持 ./data 数据目录口径与其他模块一致
    workingDir = rootDir
    // 统一使用 ZGC(JDK 25 默认即为分代模式,无需其他 GC 参数)
    jvmArgs("-XX:+UseZGC")
    jvmArgs(jcefOpens)
}
