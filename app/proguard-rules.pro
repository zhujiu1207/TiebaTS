-allowaccessmodification
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# libxposed API 102 入口: 类名由 META-INF/xposed/java_init.list 以字符串形式声明,
# 框架按全限定名反射实例化。被 R8 改名后框架找不到入口 —— 表现为"模块装上但
# 完全不生效", 且没有任何报错, 是最难排查的一类故障。
-keep class gm.tieba.tabswitch.ModuleEntry { *; }
-keep class gm.tieba.tabswitch.XposedInit { *; }

# 兼容层: 框架通过 XposedModule 的公开方法回调, 这些方法名不能变
-keep class gm.tieba.tabswitch.bridge.** { *; }

# 原生崩溃捕获的 JNI 入口: 类名与方法名都被 cpp/hide/hide.cpp 以名字修饰导出的
# 符号(Java_gm_tieba_tabswitch_util_NativeCrash_*)按字符串引用, 改名即静默失效
-keep class gm.tieba.tabswitch.util.NativeCrash { *; }

# NativeCheck 的类名被 cpp/check/main.c 的 RegisterNatives 按全限定名硬编码引用,
# 一旦被 R8 改名, System.loadLibrary 时 JNI_OnLoad 的 FindClass 失败 → 加载崩溃
-keep class gm.tieba.tabswitch.hooker.extra.NativeCheck { *; }

# JNI 注册的通用规则: JNI 按方法名+签名匹配, 而 R8 默认会把 native 方法改名
# (如 nativeInstall -> b), 改名后按名解析失败且没有任何异常——表现为"崩溃捕获
# 静默不可用", 恰恰是最需要它生效的场景。
# keepclasseswithmembernames + native <methods> 是 JNI 的标准保留规则。
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# 反射访问的宿主类字段/方法名由反混淆规则库以字符串给出, 不能被裁剪或改名;
# 这里只保留注解与签名信息, 不整体 keep 宿主类(宿主类不在本 APK 内)
-keepnames class * {
    @androidx.annotation.Keep <fields>;
    @androidx.annotation.Keep <methods>;
}

# (Room keep 规则已删: 规则库改为 framework SQLiteOpenHelper 手写实现,
#  无反射加载的生成类, 无需保留)

# DexKit: libdexkit.so 仅以 Java_org_luckypray_dexkit_DexKitBridge_native* 与
# Java_..._NativeReflect_* 符号为 JNI 入口(so 内字节串实测无指向其他包装类的
# 类名/描述符引用), native 方法名保留已被上方通用 JNI 规则与 AAR 自带 consumer
# 规则覆盖。整包 keep 会让 R8 把 445 个包装类全部保留(dex 实测 290KB, 占 42%),
# 未用到的 Java API 交由 R8 按可达性裁剪
-dontwarn org.luckypray.dexkit.**

-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
	public static void check*(...);
	public static void throw*(...);
}
