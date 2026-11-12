plugins {
    id("java")
    // Kotlin 2.4.21：平台 262 的字节码是 Java 25 目标（major 69），Kotlin 2.4 对它的
    // 元数据/字节码支持最稳；同时它也兼容 Gradle 9.6。
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "com.example"
version = "1.0.1"   // 1.0.1：修正 navigate/reference 的行列号为 0-based（原来 +1 导致落点下移）

// ---- 目标 IDE ---------------------------------------------------------------
// 默认直接使用本机已安装的 CLion（不下载整份发行版）。
// 覆盖方式：-PclionPath="C:/path/to/CLion" 或环境变量 CLION_PATH。
// 想改成「按版本号下载平台」（例如 2023.1+）：把下面 local(...) 换成
//     clion("2023.1.5")            // CLion 2023.1.5
val clionPath: String =
    (findProperty("clionPath") as String?)
        ?: System.getenv("CLION_PATH")
        ?: "C:/Users/Norym/AppData/Local/Programs/CLion"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // IntelliJ Platform 的 jar 会自动进入 compile classpath，
        // 所以 CopyPasteManager / NotificationGroupManager / VfsUtilCore 无需手写依赖。
        local(clionPath)
    }

    testImplementation("junit:junit:4.13.2")
}

kotlin {
    // 现代平台要求 Java 21+ 运行；这里用 21 产出字节码，CLion 自带 JBR 25 可以正常加载。
    jvmToolchain(21)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

intellijPlatform {
    // 用于验证插件兼容性的 IDE 列表；不需要就删掉，避免联网下载。
    pluginVerification {
        ides {
            recommended()
        }
    }

    pluginConfiguration {
        ideaVersion {
            // 编译目标是 CLion 2026.2（build 262）。要支持更老的 IDE，
            // 必须改成对应版本重新编译（平台 API 是向前兼容，不是向后兼容）。
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
}

tasks {
    // 插件没有可搜索的设置项，跳过这个耗时的任务。
    buildSearchableOptions {
        enabled = false
    }
}
