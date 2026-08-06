import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    id("org.springframework.boot")
}

group = "com.example"
version = "0.1.3"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // BOM 由 Spring Boot 插件坐标直接导入(不使用 io.spring.dependency-management 插件)
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testImplementation(platform(SpringBootPlugin.BOM_COORDINATES))

    implementation("org.springframework.boot:spring-boot-starter-web") {
        // 本地单机使用,内嵌容器用更轻量的 Jetty;Undertow 不支持 Servlet 6.1,Spring Boot 3.4 已移除
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
    }
    implementation("org.springframework.boot:spring-boot-starter-jetty")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Excel 导出
    implementation(libs.poi.ooxml)

    // 本地配置/结果存储
    runtimeOnly(libs.h2)

    // 七个目标数据库的 JDBC 驱动
    runtimeOnly(libs.jdbc.mysql)
    runtimeOnly(libs.jdbc.postgresql)
    runtimeOnly(libs.jdbc.dameng)
    runtimeOnly(libs.jdbc.kingbase)
    runtimeOnly(libs.jdbc.oceanbase)
    runtimeOnly(libs.jdbc.mssql)
    runtimeOnly(libs.jdbc.oracle)
    runtimeOnly(libs.jdbc.oracle.nls)

    // 测试
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // 测试直接编译期引用 org.h2.jdbcx.JdbcDataSource(runtimeOnly 不进测试编译类路径)
    testImplementation(libs.h2)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.mssqlserver)
}

tasks.bootJar {
    // 产物:server/build/libs/dq-tool-<version>.jar(打包脚本按此命名引用)
    archiveBaseName.set("dq-tool")
}

tasks.processResources {
    // 打包时把前端构建产物 web/dist 拷进 jar(需先在 web/ 执行 npm run build);dist 缺失时静默跳过
    from("../web/dist") {
        into("static")
    }
}

tasks.bootRun {
    // 工作目录固定为仓库根,保持 ./data 数据目录口径与 java -jar 方式一致
    workingDir = rootDir
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
