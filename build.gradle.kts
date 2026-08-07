// 根工程不含代码,仅统一声明各子模块使用的插件版本
plugins {
    kotlin("jvm") version "2.4.0" apply false
    id("org.jetbrains.compose") version "1.11.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
    // fat jar(shadow)插件,server 模块使用
    id("com.gradleup.shadow") version "9.6.1" apply false
}
