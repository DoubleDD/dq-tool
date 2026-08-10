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
}

tasks.processResources {
    // 打包时把前端构建产物 web/dist 拷进 jar(需先在 web/ 执行 npm run build);dist 缺失时静默跳过
    from("../web/dist") {
        into("static")
    }
    // 软件版本号构建期注入 app-version.txt:去 0. 前缀(如 0.1.7 -> 1.7),与打包脚本 PKG_VERSION 口径一致;版本号源头为根目录 VERSION 文件
    filesMatching("app-version.txt") {
        expand(mapOf("appVersion" to project.version.toString().replaceFirst(Regex("^0\\."), "")))
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
