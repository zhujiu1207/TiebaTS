package gm.tieba.tabswitch.hooker.extra;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.Process;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import dalvik.system.PathClassLoader;
import gm.tieba.tabswitch.bridge.XposedBridge;
import gm.tieba.tabswitch.BuildConfig;
import gm.tieba.tabswitch.XposedContext;
import gm.tieba.tabswitch.dao.Preferences;
import gm.tieba.tabswitch.hooker.TSPreferenceHelper;
import gm.tieba.tabswitch.util.FileUtils;
import gm.tieba.tabswitch.widget.TbToast;

public class TraceChecker extends XposedContext {
    public static int sChildCount;
    private final TSPreferenceHelper.PreferenceLayout mPreferenceLayout;
    private final String JAVA = "java";
    private final String C = "c";
    private final String FAKE = "fake";
    private int mTraceCount;
    // 检测重入防护: 双击会启动两个后台线程并发自增 mTraceCount、交错展示结果
    private final java.util.concurrent.atomic.AtomicBoolean mChecking = new java.util.concurrent.atomic.AtomicBoolean(false);

    public TraceChecker(final TSPreferenceHelper.PreferenceLayout preferenceLayout) {
        mPreferenceLayout = preferenceLayout;
    }

    public void checkAll() {
        if (!mChecking.compareAndSet(false, true)) {
            TbToast.showTbToast("检测进行中, 请稍候", TbToast.LENGTH_SHORT);
            return;
        }
        // 清理旧结果必须在主线程(当前在按钮点击回调中)
        mTraceCount = 0;
        while (mPreferenceLayout.getChildAt(sChildCount) != null) {
            mPreferenceLayout.removeViewAt(sChildCount);
        }
        // 检测全程(NativeCheck/包管理器全量查询/proc 文件逐行读取)移入后台线程:
        // 主线程执行既卡 UI 也有 ANR 风险; 结果经 ResultBuilder.show() 回主线程展示。
        // 此前版本曾把 pm 查询异步化再用 CountDownLatch 在主线程 await——但 countDown
        // 在 post 到主线程的 runnable 里, 主线程阻塞等待导致必然死等超时(结构性死锁)
        new Thread(() -> {
            try {
                getContext().getExternalFilesDir(null); // 部分场景可返回 null, 先触发初始化
                final java.io.File externalFilesDir = getContext().getExternalFilesDir(null);
                if (externalFilesDir == null) {
                    TbToast.showTbToast("未检测出痕迹", TbToast.LENGTH_SHORT);
                    return;
                }
                externalFilesDir.mkdirs();
                if (Preferences.getBoolean("check_xposed")) {
                    classloader();
                }
                if (Preferences.getBoolean("check_module")) {
                    files();
                    maps();
                    mounts();
                    pm();
                    preferences();
                }
                if (Preferences.getBoolean("check_stack_trace")) stackTrace();
                TbToast.showTbToast(mTraceCount > 0 ? String.format(Locale.getDefault(), "%s\n检测出%d处痕迹",
                        randomToast(), mTraceCount) : "未检测出痕迹", TbToast.LENGTH_SHORT);
            } catch (final Throwable t) {
                logBoth(t);
            } finally {
                mChecking.set(false);
            }
        }, "TiebaTSCheck").start();
    }

    private void classloader() {
        final ResultBuilder result = new ResultBuilder("类加载器");
        try {
            final String clazz = "de.robv.android.xposed.XposedBridge";
            PathClassLoader.getSystemClassLoader().loadClass(clazz);
            result.addTrace(JAVA, clazz);
        } catch (final ClassNotFoundException ignored) {
        }

        // NativeCheck 静态块 load("check") 失败时类初始化抛 ExceptionInInitializerError,
        // 不防护会沿按钮点击监听在宿主主线程崩掉宿主, 故每个 native 调用单独兜底
        if (Boolean.TRUE.equals(nativeCall(() -> NativeCheck.findXposed()))) result.addTrace(C, "de/robv/android/xposed/XposedBridge");
        if (Boolean.TRUE.equals(nativeCall(() -> NativeCheck.isFindClassInline()))) result.addTrace(FAKE, "FindClass is inline hooked");
        result.show();
    }

    private void files() {
        final ResultBuilder result = new ResultBuilder("文件");
        for (final String symbol : new String[]{"access", "faccessat"}) {
            if (Boolean.TRUE.equals(nativeCall(() -> NativeCheck.inline(symbol)))) result.addTrace(FAKE, symbol + " is inline hooked");
        }

        // getExternalFilesDir 是磁盘 stat, 三元里连调两次纯属重复; 局部缓存一次
        final java.io.File externalFilesDir = getContext().getExternalFilesDir(null);
        final String filesDirParent = getContext().getFilesDir().getParent();
        final String[] paths = new String[]{filesDirParent
                .replace(getContext().getPackageName(), BuildConfig.APPLICATION_ID),
                externalFilesDir != null
                        ? externalFilesDir.getParent()
                        .replace(getContext().getPackageName(), BuildConfig.APPLICATION_ID)
                        : "",
                getContext().getDatabasePath("Deobfs.db").getPath(),
                filesDirParent + File.separator + "shared_prefs"
                        + File.separator + "TS_preferences.xml",
                filesDirParent + File.separator + "shared_prefs"
                        + File.separator + "TS_config.xml",
                filesDirParent + File.separator + "shared_prefs"
                        + File.separator + "TS_notes.xml"};
        for (final String path : paths) {
            if (Integer.valueOf(0).equals(nativeCall(() -> NativeCheck.access(path)))) result.addTrace(C, path);
        }
        result.show();
    }

    private void maps() {
        final ResultBuilder result = new ResultBuilder("内存映射");
        for (final String symbol : new String[]{"open", "open64", "openat", "openat64", "__openat",
                "fopen", "fdopen"}) {
            if (Boolean.TRUE.equals(nativeCall(() -> NativeCheck.inline(symbol)))) result.addTrace(FAKE, symbol + " is inline hooked");
        }

        final String path = String.format(Locale.getDefault(), "/proc/%d/maps", Process.myPid());
        final String trace = nativeCall(() -> NativeCheck.fopen(path));
        if (!TextUtils.isEmpty(trace)) {
            // removeSuffix 而非盲截最后一字符: 正常行以 \n 结尾应剥掉行尾换行,
            // 但 _fopen 失败分支返回的 "cannot open ..." 无 \n, substring 会误剥 's'
            result.addTrace(C, removeTrailingNewline(trace));
        }
        result.show();
    }

    private static String removeTrailingNewline(String s) {
        return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
    }

    private void mounts() {
        final ResultBuilder result = new ResultBuilder("挂载");
        try (final BufferedReader br = new BufferedReader(new FileReader(String.format(Locale.getDefault(),
                "/proc/%d/mountinfo", Process.myPid())))) {
            final List<String> paths = new ArrayList<>();
            String lastPath = getContext().getExternalFilesDir(null) != null
                    ? getContext().getExternalFilesDir(null).getPath() : "";
            final String storagePath = Environment.getExternalStorageDirectory().getPath();
            while (!lastPath.isEmpty()) {
                final String parent = FileUtils.getParent(lastPath);
                if (parent.equals(lastPath)) break;
                lastPath = parent;
                // 停在存储根: /storage/emulated(/0) 及以上是干净设备也有的常规
                // FUSE/sdcardfs/tmpfs 挂载点, 列入匹配必然误报, 只保留应用专属深层路径
                if (lastPath.equals(storagePath) || lastPath.equals("/storage/emulated")) break;
                if (!lastPath.isEmpty() && !lastPath.endsWith("/data")) paths.add(lastPath);
            }

            for (String line = br.readLine(); line != null; line = br.readLine()) {
                for (final String path : paths) {
                    if (line.contains(String.format(" %s ", path))) {
                        result.addTrace(FAKE, line);
                    }
                }
            }
        } catch (final IOException e) {
            logBoth(e);
            result.addTrace(FAKE, e.getMessage());
        }

        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                for (final File f : getContext().getExternalFilesDir(null).getParentFile()
                        .getParentFile().listFiles()) {
                    // 分区存储下应用对自己包目录恒可见, 不构成痕迹; 只报多出来的条目
                    if (f.getName().equals(getContext().getPackageName())) continue;
                    result.addTrace(FAKE, f.getPath());
                }
            } catch (final NullPointerException ignored) {
            }
        }
        result.show();
    }

    @SuppressLint({"DiscouragedPrivateApi", "PrivateApi", "QueryPermissionsNeeded"})
    private void pm() {
        final ResultBuilder result = new ResultBuilder("包管理器");
        final List<String> modules = new ArrayList<>();
        final PackageManager pm = getContext().getPackageManager();
        // 运行于 checkAll 的后台线程, 全量包查询无 ANR 顾虑; 展示由 show() 回主线程
        try {
            final IBinder service = (IBinder) Class.forName("android.os.ServiceManager")
                    .getDeclaredMethod("getService", String.class).invoke(null, "package");
            final Object iPackageManager = Class.forName("android.content.pm.IPackageManager$Stub")
                    .getDeclaredMethod("asInterface", IBinder.class).invoke(null, service);
            final Field field = pm.getClass().getDeclaredField("mPM");
            field.setAccessible(true);
            final Class<?> mPMClass = field.get(pm).getClass();
            if (!mPMClass.equals(iPackageManager.getClass())) {
                result.addTrace(FAKE, mPMClass.getName());
            }
        } catch (final Throwable e) {
            // 反射失败是检测代码自身的问题, 不是检测出的痕迹: 记日志但不当 FAKE 上报
            logBoth(e);
        }

        try {
            for (final PackageInfo pkg : pm.getInstalledPackages(PackageManager.GET_META_DATA)) {
                final ApplicationInfo app = pkg.applicationInfo;
                if (app.metaData != null && app.metaData.containsKey("xposedmodule")) {
                    modules.add(pm.getApplicationLabel(pkg.applicationInfo).toString());
                }
            }
        } catch (final Throwable e) {
            // 同上: 接口变动/权限缺失不构成 FAKE 证据
            logBoth(e);
        }

        final Intent intentToResolve = new Intent(Intent.ACTION_MAIN);
        intentToResolve.addCategory("de.robv.android.xposed.category.MODULE_SETTINGS");
        final List<ResolveInfo> ris = pm.queryIntentActivities(intentToResolve, 0);
        for (final ResolveInfo ri : ris) {
            final String name = ri.loadLabel(pm).toString();
            if (!modules.contains(name)) modules.add(name);
        }

        if (modules.size() > 0) {
            result.addTrace(JAVA, modules.toString());
        }
        result.show();
    }

    private void preferences() {
        final ResultBuilder result = new ResultBuilder("偏好");
        for (final String sp : new String[]{"TS_preferences", "TS_config", "TS_notes"}) {
            if (getContext().getSharedPreferences(sp, Context.MODE_PRIVATE)
                    .getAll().keySet().size() != 0) result.addTrace(JAVA, sp);
        }
        result.show();
    }

    private void stackTrace() {
        final ResultBuilder result = new ResultBuilder("堆栈");
        // synchronizedList 的遍历必须持锁, 否则遍历期间并发 add 仍会错乱
        synchronized (StackTrace.sStes) {
            for (final String st : StackTrace.sStes) {
                result.addTrace(JAVA, st);
            }
        }
        result.show();
    }

    // NativeCheck 类初始化失败(ExceptionInInitializerError/UnsatisfiedLinkError)时的
    // 统一兜底: 失败按"无痕迹"处理并记日志, 绝不让 Error 沿按钮点击监听击穿宿主主线程
    private <T> T nativeCall(final java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (final Throwable t) {
            XposedBridge.log(t);
            return null;
        }
    }

    // 同步写入 LSPosed 日志与模块日志文件(与 Kotlin 侧 logBoth 一致)
    private void logBoth(final Throwable t) {
        XposedBridge.log(t);
        gm.tieba.tabswitch.util.Logger.INSTANCE.log(t);
    }

    private void logBoth(final String message) {
        XposedBridge.log(message);
        gm.tieba.tabswitch.util.Logger.INSTANCE.log(message);
    }

    private String randomToast() {
        switch (new Random().nextInt(9)) {
            case 0:
                return "没收尾巴球";
            case 1:
                return "尾巴捏捏";
            case 2:
                return "没收尾巴";
            case 3:
                return "点燃尾巴";
            case 4:
                return "捏尾巴";
            case 5:
                return "若要人不知，除非己莫为";
            case 6:
                return "哼！你满身都是破绽";
            case 7:
                return "checkmate";
            case 8:
                return "Xposed 无处可逃";
            default:
                return "";
        }
    }

    private class ResultBuilder {
        private static final String INDENT = "　 ";
        StringBuilder mResult;

        private ResultBuilder(final String text) {
            mResult = new StringBuilder("检测" + text + " -> ");
        }

        private void addTrace(final String tag, final String msg) {
            if (msg == null) return;
            mResult.append("\n").append(INDENT).append(tag).append(": ").append(msg);
            mTraceCount++;
        }

        private void show() {
            final String result = mResult.toString();
            logBoth(result);
            // checkAll 已移入后台线程: addView 必须回主线程, 否则后台改视图树会崩
            new android.os.Handler(android.os.Looper.getMainLooper()).post(
                    () -> mPreferenceLayout.addView(TSPreferenceHelper.createTextView(result)));
        }
    }
}
