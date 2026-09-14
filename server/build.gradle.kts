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
    // 交付 jar 不再内嵌任何前端资源:静态由 Tauri frontendDist(web/dist)或 jpackage 的
    // -Ddq.web.static-dir=${APPDIR}/static 从磁盘提供,jar 此时是纯 API 服务。
    // processResources 仍会把 web/dist 拷入 build/resources(dev/测试 classpath 用),此排除只作用于 fat jar。
    exclude("static/**")
    manifest {
        attributes("Main-Class" to "com.example.dq.DqApplication")
    }
}

tasks.named<JavaExec>("run") {
    // 工作目录固定为仓库根,保持 ./data 数据目录口径与 java -jar 方式一致
    workingDir = rootDir
    // 统一使用 G1 + 384MB 堆上限(桌面单机小堆场景 ZGC 内部结构开销大于收益,与安装包参数一致)
    jvmArgs("-XX:+UseG1GC", "-Xmx384m", "-XX:MaxRAMPercentage=50")
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

// ---- 前端构建(dev/测试仍走 classpath,交付 jar 不含前端) ----
// dev 模式(:server:run,make dev / dev-headless)不构建前端——前端开发走 make dev-web(vite 5173 热更新),
// 或直接使用磁盘上已有的 web/dist;processResources 仅在有 dist 时拷入 static,缺失时跳过(API-only 调试)。
// 测试依赖 buildWeb 产出 web/dist,保证 WebServerSmokeTest 的 classpath 静态用例可跑。
// 交付 jar 不再内嵌前端:shadowJar 排除 static/**,前端由 jpackage static-dir / Tauri frontendDist 提供。
val buildWeb by tasks.registering(Exec::class) {
    group = "build"
    description = "构建前端产物 web/dist(增量;:server:test 的前置;打包脚本各自构建)"

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
            throw GradleException("web/node_modules 不存在,请先执行: cd web && pnpm install")
        }
    }
}

// 说明(2026-09):原 buildWebForRelease 任务已删除。交付 fat jar 不再内嵌前端(见 tasks.shadowJar 的 exclude),
// 是纯 API 服务;前端产物分别由 jpackage 脚本(xcopy web/dist 到 static/,jpackage 注入 -Ddq.web.static-dir)
// 与 Tauri 打包脚本(scripts/package-tauri-*)各自保证,Tauri 的 frontendDist 直接指向 web/dist,不再经 Gradle 中转。

// 更新日志硬校验(发版强制):CHANGELOG.md 必须存在当前版本对应的 `## <展示版>` 段落
// (展示版 = VERSION 去掉 0. 前缀,与页脚显示/页签「本次更新」口径一致;`## <原始VERSION>` 也接受)。
// 挂在 processResources 前置:dev 运行与 release 打包都必经,缺失立即构建失败并提示填写
val verifyChangelog by tasks.registering {
    group = "build"
    description = "校验 CHANGELOG.md 含当前版本段落(processResources 前置)"
    val changelogFile = rootProject.layout.projectDirectory.file("CHANGELOG.md")
    inputs.file(changelogFile)
    inputs.property("version", project.version.toString())
    doLast {
        val version = project.version.toString()
        val displayVersion = version.replaceFirst(Regex("^0\\."), "")
        val lines = changelogFile.asFile.takeIf { it.isFile }?.readLines() ?: emptyList()
        val found = lines.any { it.startsWith("## $displayVersion") || it.startsWith("## $version") }
        if (!found) {
            throw GradleException(
                "CHANGELOG.md 缺少当前版本($displayVersion)的更新段落,请以「## $displayVersion (YYYY-MM-DD)」标题补充本次更新内容;" +
                    "scripts/bump-version.sh 升版本号时会自动插入模板段落"
            )
        }
    }
}

tasks.processResources {
    dependsOn(verifyChangelog)
    // 更新日志随 jar 分发:运行时由 common 的 ChangelogService 从 classpath /CHANGELOG.md 读取解析
    from(rootProject.layout.projectDirectory.file("CHANGELOG.md"))
    // dev 模式与测试不再强依赖前端构建:dist 存在则拷入 static,缺失时 from 空目录静默跳过(API-only 调试)。
    // 注意不能用 if(distDir.isDirectory) 在配置期判断:buildWeb 同轮新建的 dist 会赶上 processResources
    // 已被 up-to-date 跳过,static 永远拷不进去(WebServerSmokeTest 404);必须无条件 from + 声明可选输入,
    // 让 dist 的出现/变化参与 up-to-date 判定,任务才会在 dist 就绪后重新执行
    val distDir = rootProject.layout.projectDirectory.dir("web/dist")
    from(distDir) {
        into("static")
    }
    inputs.dir(distDir).withPropertyName("webDist").optional(true)
    // 当 buildWeb 在任务图中(测试)时,确保先构建、后拷贝,拷入的始终是最新产物
    mustRunAfter(buildWeb)
    // 软件版本号构建期注入 app-version.txt:去 0. 前缀(如 0.1.7 -> 1.7),与打包脚本 PKG_VERSION 口径一致;版本号源头为根目录 VERSION 文件
    filesMatching("app-version.txt") {
        expand(mapOf("appVersion" to project.version.toString().replaceFirst(Regex("^0\\."), "")))
    }
}

tasks.test {
    // 测试需要静态资源验证 SPA 行为(WebServerSmokeTest),保持测试完整性
    dependsOn(buildWeb)
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
