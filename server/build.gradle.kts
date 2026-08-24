plugins {
    java
    application
    id("com.gradleup.shadow")
}

group = "com.example"
version = rootProject.file("VERSION").readText().trim()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // 共享内核:业务逻辑(dialect/repository/scan/service/license/config)全部在此
    implementation(project(":common"))

    // Web 层:Javalin(内嵌 Jetty 12 ee10);JSON 用 Jackson 3(io.javalin.json.JavalinJackson3)
    implementation(libs.javalin)
    implementation(libs.jackson3.databind)
    // Kotlin data class(内核模型)的序列化/反序列化
    implementation(libs.jackson3.module.kotlin)

    // 日志:logback 按天滚动(配置见 resources/logback.xml)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    // 配置加载(yaml);连接池由内核 ServiceEnv 管理
    implementation(libs.snakeyaml)

    // 入参校验(jakarta validation);tomcat-embed-el 为校验消息插值的 EL 实现
    implementation(libs.hibernate.validator)
    runtimeOnly(libs.tomcat.embed.el)

    // 测试(junit-jupiter + assertj)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    // 测试直接编译期引用 org.h2.jdbcx.JdbcDataSource(runtimeOnly 不进测试编译类路径)
    testImplementation(libs.h2)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.example.dq.DqApplication")
}

tasks.jar {
    // 可执行产物由 shadowJar 承担;plain jar 仅留档,避免与 shadowJar 产物同名冲突
    archiveClassifier.set("plain")
}

tasks.shadowJar {
    // 产物:server/build/libs/dq-tool-<version>.jar(打包脚本按此命名引用)
    archiveBaseName.set("dq-tool")
    archiveClassifier.set("")
    mergeServiceFiles()
    manifest {
        attributes("Main-Class" to "com.example.dq.DqApplication")
    }
}

tasks.named<JavaExec>("run") {
    // 工作目录固定为仓库根,保持 ./data 数据目录口径与 java -jar 方式一致
    workingDir = rootDir
    // 统一使用 ZGC(JDK 25 默认即为分代模式,无需其他 GC 参数)
    jvmArgs("-XX:+UseZGC")
    // 原生启动画面(开发模式无 jar,走文件系统相对路径;生产由打包脚本注入 -splash:${APPDIR}/splash.png)
    // 仅桌面开发模式启用:原生 splash 由 launcher 在 main() 之前显示,main 里再设 headless=true 也收不回去,
    // headless 调试(make dev-headless)带 -splash 会常驻一张启动图并把进程变成 GUI 应用(Dock 图标/抢焦点)。
    // 桌面判定与 make dev 注入方式一致:JAVA_TOOL_OPTIONS 含 -Djava.awt.headless=false
    val desktopDev = providers.environmentVariable("JAVA_TOOL_OPTIONS")
        .map { it.contains("java.awt.headless=false") }
        .getOrElse(false)
    if (desktopDev) {
        jvmArgs("-splash:server/src/main/resources/splash.png")
    }
}

// ---- 前端构建:dev 模式与 release 打包拆开 ----
// dev 模式(:server:run,make dev / dev-headless)不构建前端——前端开发走 make dev-web(vite 5173 热更新),
// 或直接使用磁盘上已有的 web/dist;processResources 仅在有 dist 时拷入 static,缺失时跳过(API-only 调试)。
// release 打包正确性由 buildWebForRelease 保障(见下)::server:shadowJar 前强制前端产物最新且存在,
// 不再存在"旧版/缺失"的静默坏包(此前 buildWeb 挂在 processResources 上导致 dev 运行也被迫构建前端)。
val buildWeb by tasks.registering(Exec::class) {
    group = "build"
    description = "构建前端产物 web/dist(增量;release 打包与测试的前置)"

    // 统一在 web/ 目录执行;npm 在 Windows 上是 npm.cmd,直接写 npm 会找不到
    workingDir = rootProject.layout.projectDirectory.dir("web").asFile
    val npmCmd = if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm"
    commandLine(npmCmd, "run", "build")

    // 增量输入:前端源码与构建配置
    inputs.dir(rootProject.layout.projectDirectory.dir("web/src"))
    inputs.file(rootProject.layout.projectDirectory.file("web/index.html"))
    inputs.file(rootProject.layout.projectDirectory.file("web/vite.config.ts"))
    inputs.file(rootProject.layout.projectDirectory.file("web/package.json"))
    // 输出:web/dist(缺失即视为未构建,自动触发 npm run build)
    outputs.dir(rootProject.layout.projectDirectory.dir("web/dist"))

    doFirst {
        if (!rootProject.layout.projectDirectory.dir("web/node_modules").asFile.exists()) {
            throw GradleException("web/node_modules 不存在,请先执行: cd web && npm install(或 npm ci)")
        }
    }
}

// release 打包专用保障(新增):打 fat jar 前强制前端产物最新且存在。
// dev 模式(:server:run)不经过本任务,因此不会触发前端构建。
val buildWebForRelease by tasks.registering {
    group = "build"
    description = "release 打包保障:构建前端产物并校验 web/dist 存在(shadowJar 的前置,dev 模式不触发)"
    dependsOn(buildWeb)
    doLast {
        val dist = rootProject.layout.projectDirectory.dir("web/dist").asFile
        if (!dist.isDirectory || !dist.resolve("index.html").isFile) {
            throw GradleException(
                "web/dist 缺失或为空,release 打包需要前端产物。请先执行: cd web && npm install && npm run build " +
                    "(或让 buildWeb 自动构建;若 web/node_modules 不存在会先报 npm 依赖错误)"
            )
        }
    }
}

tasks.processResources {
    // dev 模式与测试不再强依赖前端构建:dist 存在则拷入 static,缺失则跳过(API-only 调试)
    val distDir = rootProject.layout.projectDirectory.dir("web/dist").asFile
    if (distDir.isDirectory) {
        from(distDir) {
            into("static")
        }
    }
    // 当 buildWeb 在任务图中(release 打包 / 测试)时,确保先构建、后拷贝,拷入的始终是最新产物
    mustRunAfter(buildWeb)
    // 软件版本号构建期注入 app-version.txt:去 0. 前缀(如 0.1.7 -> 1.7),与打包脚本 PKG_VERSION 口径一致;版本号源头为根目录 VERSION 文件
    filesMatching("app-version.txt") {
        expand(mapOf("appVersion" to project.version.toString().replaceFirst(Regex("^0\\."), "")))
    }
}

tasks.shadowJar {
    // release 打包正确性:打 fat jar 前强制前端产物最新且存在(经 buildWebForRelease)
    dependsOn(buildWebForRelease)
}

tasks.test {
    // 测试需要静态资源验证 SPA 行为(WebServerSmokeTest),保持测试完整性
    dependsOn(buildWeb)
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
