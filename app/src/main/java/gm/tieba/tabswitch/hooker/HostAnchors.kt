package gm.tieba.tabswitch.hooker

// 跨子系统共享的宿主锚点与私有 API 端点（单一事实源）。
// 各 hooker 不再各自声明同名字面量：类路径字符串写错编译器不报错、只会静默
// 失效 hook（AGENTS.md 强制项），集中一处便于版本适配时逐条核对。
//
// 放置约定：仅收录被 ≥2 个子系统引用、且随宿主版本稳定的锚点；单一子系统
// 专属的常量仍留在各自文件（如 PurgeAdKeys / LotterySupport），避免为引用
// 一个常量把无关子系统耦合到一个公共文件。

// 宿主主界面（冷启动锚点：自动签到/自动开签到页/反混淆触发/闪屏标记/侧边栏锁定
// 均挂其 onCreate/onResume）。类名未混淆、多版本稳定（12.x-22.x 均为 tblauncher 包）
internal const val MAIN_TAB_ACTIVITY = "com.baidu.tieba.tblauncher.MainTabActivity"

// 消息页容器视图（消息起始页改通知、消息页净化共用）。类名未混淆
internal const val MSG_CENTER_CONTAINER_VIEW =
    "com.baidu.tieba.immessagecenter.msgtab.ui.view.MsgCenterContainerView"

// 关注列表/签到状态查询端点（自动签到 API 直签与"只推荐已关注的吧"的过滤数据源）
internal const val LIKE_URL = "https://tieba.baidu.com/mo/q/newmoindex"
