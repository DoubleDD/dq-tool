plugins {
    // 版本在根 build.gradle.kts 统一声明(kotlin 2.4.0)
    kotlin("jvm")
}

group = "com.example"
version = "0.1.3"

kotlin {
    jvmToolchain(25)
}

dependencies {
    // 本地 H2 存储 + 连接池(HikariDataSource 暴露在 ServiceEnv 公共 API 上,需 api 传递)
    implementation(libs.h2)
    api(libs.hikari)

    // JSON(null_rules / col_stats / AI 接口);内核统一用 Jackson 2,不依赖 server web 层的 Jackson 3
    implementation(libs.jackson2.module.kotlin)

    // 数据库迁移(Flyway;H2 支持内置于 flyway-core)
    implementation(libs.flyway.core)

    // Excel 导出
    implementation(libs.poi.ooxml)

    // SSH 隧道(数据源经跳板机连接目标库,本地端口转发)
    implementation(libs.jsch)

    // 入参校验注解:模型类携带 @field:NotNull 等注解,由 server web 层的 hibernate-validator 触发
    compileOnly(libs.jakarta.validation.api)

    // 日志门面(api 暴露:desktop UI 与 server web 层共享)
    api(libs.slf4j.api)

    // 7 个目标数据库 JDBC 驱动(版本与 server 统一在 gradle/libs.versions.toml 管理)
    runtimeOnly(libs.jdbc.mysql)
    runtimeOnly(libs.jdbc.postgresql)
    runtimeOnly(libs.jdbc.mssql)
    runtimeOnly(libs.jdbc.oracle)
    // orai18n:23c 起 ojdbc 移除了部分 NLS 字符集支持,Oracle 老库(ZHS16GBK 等)连接需要,否则报 ORA-17056
    runtimeOnly(libs.jdbc.oracle.nls)
    runtimeOnly(libs.jdbc.dameng)
    runtimeOnly(libs.jdbc.kingbase)
    runtimeOnly(libs.jdbc.oceanbase)

    // 测试
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.mssqlserver)
    // Jackson3KotlinSpikeTest:验证 server web 层(Jackson 3 + hibernate-validator)能直接消费内核 Kotlin 模型
    testImplementation(libs.jackson3.databind)
    testImplementation(libs.jackson3.module.kotlin)
    testImplementation("org.jetbrains.kotlin:kotlin-reflect")
    testImplementation(libs.hibernate.validator)
    testRuntimeOnly(libs.tomcat.embed.el)
    testRuntimeOnly(libs.logback.classic)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
