package gm.tieba.tabswitch.bridge;

/**
 * 旧版 {@code XC_LoadPackage.LoadPackageParam} 的等价数据载体。
 *
 * <p>API 102 的 {@code PackageReadyParam} 把「包名 / 进程名 / ClassLoader /
 * 是否首个包」拆成了多个 getter，而本模块的历史代码统一按字段访问。这里做一层
 * 纯数据映射，让业务代码（{@code XposedInit#handleLoadPackage}）保持原样，
 * 不需要为了换框架而改写 700 行初始化逻辑。
 */
public final class LoadPackageParam {

    /** 包名，如 {@code com.baidu.tieba}。 */
    public final String packageName;

    /** 进程名，如 {@code com.baidu.tieba} 或 {@code com.baidu.tieba:push}。 */
    public final String processName;

    /** 宿主 ClassLoader。 */
    public final ClassLoader classLoader;

    /**
     * 是否为该进程的首个包。旧版 {@code isFirstApplication} 的等价物。
     *
     * <p>旧版语义是「这是本进程第一个被加载的 Application」，用于过滤同一进程内
     * 的重复回调；API 102 的 {@code isFirstPackage} 语义一致。
     */
    public final boolean isFirstApplication;

    public LoadPackageParam(String packageName, String processName,
                            ClassLoader classLoader, boolean isFirstApplication) {
        this.packageName = packageName;
        this.processName = processName;
        this.classLoader = classLoader;
        this.isFirstApplication = isFirstApplication;
    }

    @Override
    public String toString() {
        return "LoadPackageParam{" + packageName + '/' + processName
                + ", first=" + isFirstApplication + '}';
    }
}
