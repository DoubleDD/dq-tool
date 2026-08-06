rootProject.name = "dq-tool"

include("server", "desktop")

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        // IntelliJ Platform 图标等资源构件(Jewel standalone 依赖)
        maven("https://www.jetbrains.com/intellij-repository/snapshots")
    }
}
