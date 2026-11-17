pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    plugins {
        // AGP 8.13.2 / Gradle 8.13 / Kotlin 1.9.21 / KSP 1.9.21-1.0.16：
        // api102 迁移验证过的组合，支持 compileSdk 37 的平台头与 16KB page 对齐链路
        id("com.android.application") version "8.13.2"
        id("com.android.library") version "8.13.2"
        id("org.jetbrains.kotlin.android") version "1.9.21"
        id("com.google.devtools.ksp") version "1.9.21-1.0.16" apply false
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://api.xposed.info/") }
    }
}
// ASCII 项目名：与仓库名一致；GBK 控制台下中文项目名显示为乱码，
// 部分工具链日志路径拼接对非 ASCII 敏感（教训见 gradle.properties 顶部注释）
rootProject.name = "TiebaTS"
include(":app")
