import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// 固定版本号: 不再从 git 历史推导
val commitCount = 40092
val commitCountSinceLatestTag = "0"
// Android 17 为 API 37.0（Google 已改为小数版本命名, 平台包名 android-37.0,
// 不存在 android-37 —— AGP 8.x 必须用 spec 式 DSL 指定 minor 版本）。
// compileSdk 37.0 才能引用 API 37 的行为差异常量; targetSdk 保持 34 不动,
// 避免宿主行为因 targetSdk 提升而变化(本模块 hook 的是贴吧, 不是自身)
val targetSdkVersion = 34

android {
    // Android 17 平台包名为 android-37.0(小数命名, 不存在 android-37),
    // AGP 8.13.2 必须用字符串全名指定(其 javadoc 里的 compileSdk{} spec 块是
    // AGP 9 的 API, 8.13.2 的 gradle-api jar 中并不存在)
    compileSdkVersion("android-37.0")
    buildToolsVersion = "36.1.0"
    // NDK 26 的 libc++ 头在 API 37 平台头下会与新版 __ANDROID_API__ 分支冲突;
    // 27 已验证可用于 API 36/37 平台头, 且默认开启 16KB page 对齐支持
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "gm.tieba.tabswitch"
        minSdk = 28
        targetSdk = targetSdkVersion
        versionCode = commitCount
        versionName = "5.2.6"
        if (versionName!!.contains("alpha") || versionName!!.contains("beta")) {
            versionNameSuffix = ".$commitCountSinceLatestTag"
        }
        buildConfigField("String", "TARGET_VERSION", "\"22.14.1.0\"")
        buildConfigField("String", "MIN_VERSION", "\"12.53.1.0\"")

        externalNativeBuild {
            cmake {
                abiFilters("arm64-v8a")
                arguments("-DANDROID_STL=none")
            }
        }
    }
    applicationVariants.all {
        // 注意用 forEach 而非 all{}: all 是"谓词全部满足"的判定, 谓词返回 false
        // 会在第一个元素后短路——当前单 output 侥幸无害, 引入 ABI 拆分/多 output
        // 后第二个起不再重命名
        outputs
            .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
            .forEach { output ->
                output.outputFileName = "TS_${defaultConfig.versionName}${defaultConfig.versionNameSuffix ?: ""}_${name}.apk"
            }
    }
    signingConfigs {
        // 密钥材料一律不进源码树。
        //
        // 优先级: 项目根目录 keystore.properties > 环境变量 > 无(退化为未签名,
        // 发版仍可按 签名命令.txt 用 apksigner 手动签名)。
        // 早期版本把口令直接写进构建脚本, 一旦源码外传即泄露签名私钥口令 ——
        // keystore.properties 已在 .zcodeignore 中排除, 不会进入审查上下文。
        //
        // keystore.properties 格式:
        //   storeFile=D:/path/to/release.keystore
        //   storePassword=...
        //   keyAlias=...
        //   keyPassword=...
        create("release") {
            val propsFile = rootProject.file("keystore.properties")
            val props = Properties()
            if (propsFile.exists()) {
                propsFile.inputStream().use { props.load(it) }
            }
            val storePath = props.getProperty("storeFile") ?: System.getenv("TS_KEYSTORE")
            val store = storePath?.let { File(it) }
            if (store != null && store.exists()) {
                storeFile = store
                storePassword = props.getProperty("storePassword") ?: System.getenv("TS_STORE_PASS")
                keyAlias = props.getProperty("keyAlias") ?: System.getenv("TS_KEY_ALIAS")
                keyPassword = props.getProperty("keyPassword") ?: System.getenv("TS_KEY_PASS")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            // 资源收缩: res 只有 4 个小 XML, 单项收益小, 但零成本——new-shrinker
            // 的两个实验开关(gradle.properties)早已就位, 不开启它们纯属空转。
            // 依赖 minifyEnabled, 关闭 minify 时此项自动失效(AGP 会报错要求先开)
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 有可用密钥时签名, 否则保持未签名(不阻断构建)
            signingConfig = signingConfigs.findByName("release")
                ?.takeIf { it.storeFile?.exists() == true }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
        }
    }
    packaging {
        // 注意: 不能整体排除 /META-INF/**。libxposed API 102 的模块元数据
        // (java_init.list / native_init.list / module.prop / scope.list) 就放在
        // META-INF/xposed/ 下, 整体排除会让新框架认不出模块(装上后完全不生效,
        // 且没有任何报错)。这里只排除确定无关的子目录。
        //
        // 同样不能排除 META-INF/MANIFEST.MF: v1(JAR) 签名依赖它, 缺失会让
        // apksigner verify 直接报 "Missing META-INF/MANIFEST.MF"。
        resources.excludes.addAll(
            listOf(
                "/META-INF/*.version",
                "/META-INF/*.kotlin_module",
                "/META-INF/androidx/**",
                "/META-INF/com/**",
                "/META-INF/versions/**",
                "/META-INF/services/**",
                "/kotlin/**"
            )
        )
        jniLibs.excludes.addAll(listOf("**/liblog.so", "/lib/x86/**", "/lib/x86_64/**", "/lib/armeabi-v7a/**"))
    }
    buildFeatures {
        prefab = true
        buildConfig = true
    }
    lint {
        checkDependencies = true
    }
    namespace = "gm.tieba.tabswitch"
}

dependencies {
    // libxposed API 102: 唯一的模块 API(legacy de.robv 82 已移除, LSPatch 等
    // legacy-only 框架不再支持)。compileOnly——运行时由框架注入
    compileOnly("io.github.libxposed:api:102.0.0")
    api("androidx.annotation:annotation:1.7.1")

    val roomVersion = "2.6.1"
    // 不引 room-ktx: DAO 全部为阻塞式 List 查询, 无 suspend/Flow 用法, ktx 只是
    // 往 dex 里带进整条 kotlinx-coroutines 依赖链
    implementation("androidx.room:room-runtime:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // DexKit 2.0.6 起才支持 16KB 页内核: 2.0.1 的 libdexkit.so 实测 PT_LOAD 仅
    // 4KB 对齐, 16KB 页设备上动态链接器直接拒载, 反混淆整体失效。
    // 2.3.0(2026-09-22): POM/metadata 仍声明 stdlib 1.5.0(Kotlin 1.9.21 可消费),
    // 传递依赖与 2.2.0 完全一致(flatbuffers 23.5.26/cxx 1.2.0); aar 内 arm64-v8a
    // 与 x86_64 的 libdexkit.so 全部 PT_LOAD 实测 16384 对齐。升级收益: 零拷贝
    // 加载、字符串查询优化(本项目 12/21 matcher 为字符串特征)、usingNumbers
    // 浮点修复(ResMatcher 路径)、线程池销毁 SIGABRT 修复。无破坏性 API 变更,
    // 全项目 ClassMatcher 均走 create() DSL(2.3.0 修复的 ClassMatcher(Class<?>) 构造器未用)
    // 不引 okhttp: 签到层仅 3 个同步 JSON 接口, HttpURLConnection(平台自带,
    // keep-alive 连接复用)足够, 省去 okhttp+okio 整链 dex 体积
    implementation("org.luckypray:dexkit:2.3.0")
    implementation("dev.rikka.ndk.thirdparty:cxx:1.2.0")

    // JVM 单测: XposedContext.safeConstantFor / DeobfuscationHelper 版本区间等
    // 纯逻辑的回归防线
    testImplementation("junit:junit:4.13.2")
    // bridge 层类型签名引用 libxposed API(主代码 compileOnly),
    // 单测加载这些类时需要 api 在测试类路径上可解析
    testImplementation("io.github.libxposed:api:102.0.0")
}

// 本机 adb 路径固定; 换机器时回退到 PATH 中的 adb(找不到则该任务失败并提示)
val adbExecutable: String =
    if (File("D:\\Android\\Sdk\\platform-tools\\adb.exe").exists()) {
        "D:\\Android\\Sdk\\platform-tools\\adb.exe"
    } else {
        "adb"
    }

tasks.register("restartTieba") {
    doLast {
        exec {
            commandLine(adbExecutable, "shell", "am", "force-stop", "com.baidu.tieba")
        }
        // monkey 启动方式与《签名命令.txt》一致: "$(pm resolve-activity ...)"
        // 输出为多行 key=value, am start 无法直接消费
        exec {
            commandLine(adbExecutable, "shell", "monkey", "-p", "com.baidu.tieba", "1")
        }
    }
}

afterEvaluate {
    tasks.named("installDebug").configure {
        finalizedBy(tasks.named("restartTieba"))
    }
}
