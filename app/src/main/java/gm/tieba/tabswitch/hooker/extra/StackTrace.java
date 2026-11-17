package gm.tieba.tabswitch.hooker.extra;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import gm.tieba.tabswitch.bridge.XC_MethodHook;
import gm.tieba.tabswitch.bridge.XposedBridge;
import gm.tieba.tabswitch.bridge.XposedHelpers;
import gm.tieba.tabswitch.XposedContext;
import gm.tieba.tabswitch.hooker.IHooker;

public class StackTrace extends XposedContext implements IHooker {
    // hook 回调可能来自不同线程, 用同步包装避免并发 add 时的数据错乱
    public static List<String> sStes = Collections.synchronizedList(new ArrayList<>());

    @NonNull
    @Override
    public String key() {
        return "check_stack_trace";
    }

    @Override
    public void hook() throws Throwable {
        for (final Method method : XposedHelpers.findClass("com.baidu.tieba.LogoActivity", sClassLoader).getDeclaredMethods()) {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                    final List<String> sts = new ArrayList<>();
                    final StackTraceElement[] stes = Thread.currentThread().getStackTrace();
                    boolean isXposedStackTrace = false;
                    for (final StackTraceElement ste : stes) {
                        final String name = ste.getClassName();
                        if (name.contains("Activity")
                                || name.equals("android.app.Instrumentation")) break;
                        if (isXposedStackTrace) sts.add(name);
                        if (name.equals("java.lang.Thread")) isXposedStackTrace = true;
                    }

                    // contains+add 两步必须同锁原子执行, 否则并发回调会重复收录同一类名
                    // (读取侧 TraceChecker 已按 synchronized(sStes) 遍历, 此处对齐)
                    synchronized (sStes) {
                        for (final String st : sts) {
                            if (!sStes.contains(st)) sStes.add(st);
                        }
                    }
                }
            });
        }
    }
}
