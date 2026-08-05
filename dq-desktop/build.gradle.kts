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

    // 协程(UI 侧异步/轮询)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")

    // 本地 H2 存储 + 连接池
    implementation("com.h2database:h2:2.3.232")
    implementation("com.zaxxer:HikariCP:6.3.0")

    // JSON(null_rules / col_stats / AI 接口)
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.20.0")

    // Excel 导出
    implementation("org.apache.poi:poi-ooxml:5.3.0")

    // 日志
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    // 7 个目标数据库 JDBC 驱动(版本与 Maven 工程一致)
    runtimeOnly("com.mysql:mysql-connector-j:9.3.0")
    runtimeOnly("org.postgresql:postgresql:42.7.5")
    runtimeOnly("com.microsoft.sqlserver:mssql-jdbc:12.10.2.jre11")
    runtimeOnly("com.oracle.database.jdbc:ojdbc11:23.26.3.0.0")
    runtimeOnly("com.dameng:DmJdbcDriver18:8.1.3.140")
    runtimeOnly("cn.com.kingbase:kingbase8:9.0.1")
    runtimeOnly("com.oceanbase:oceanbase-client:2.4.18")

    // 测试
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
    testImplementation("org.testcontainers:mysql:1.21.4")
    testImplementation("org.testcontainers:postgresql:1.21.4")
    testImplementation("org.testcontainers:mssqlserver:1.21.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

compose.desktop {
    application {
        mainClass = "com.example.dq.MainKt"

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
