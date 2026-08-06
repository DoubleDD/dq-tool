// 根工程不含代码,仅统一声明各子模块使用的插件版本
plugins {
    kotlin("jvm") version "2.4.0" apply false
    id("org.jetbrains.compose") version "1.11.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
    // Spring Boot 4.1 官方要求 Gradle 8.14+ 或 9.x(与根 wrapper 9.2.1 兼容)
    id("org.springframework.boot") version "4.1.0" apply false
}
