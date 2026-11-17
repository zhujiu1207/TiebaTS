package gm.tieba.tabswitch.hooker.add

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View.OnLongClickListener
import android.widget.ImageView
import android.widget.LinearLayout
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.dao.AcRules.findRule
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.hooker.Obfuscated
import gm.tieba.tabswitch.hooker.deobfuscation.Matcher
import gm.tieba.tabswitch.hooker.deobfuscation.ReturnTypeMatcher
import gm.tieba.tabswitch.util.copy
import gm.tieba.tabswitch.util.findFirstMethodByExactType
import gm.tieba.tabswitch.util.getExtension
import gm.tieba.tabswitch.util.toByteBuffer
import gm.tieba.tabswitch.widget.TbToast
import gm.tieba.tabswitch.widget.TbToast.Companion.showTbToast
import org.luckypray.dexkit.query.matchers.ClassMatcher
import java.io.File
import java.io.IOException
import java.lang.reflect.Field
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class SaveImages : XposedContext(), IHooker, Obfuscated {

    private var mDownloadImageViewField: Field? = null

    // @Volatile: 主线程长按读 toList() / 宿主回调线程写, 保证替换后引用对读者可见
    @Volatile
    private lateinit var mList: ArrayList<*>

    override fun key(): String {
        return "save_images"
    }

    override fun matchers(): List<Matcher> {
        return listOf(
            ReturnTypeMatcher(LinearLayout::class.java, "save_images").apply {
                classMatcher = ClassMatcher.create().usingStrings("分享弹窗触发分享：分享成功")
            }
        )
    }

    override fun hook() {
        findRule("save_images") { _, clazz, method ->
            hookAfterMethod(clazz, method, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType) { param ->
                // 宿主返回类型漂移时此处每次分享弹窗都会命中: 硬转换抛 CCE 且功能
                // 单点失效, as? 跳过与"找不到下载按钮"的降级语义一致
                val downloadIconView = param.result as? LinearLayout ?: return@hookAfterMethod
                downloadIconView.setOnLongClickListener(saveImageListener)
            }
        }

        hookBeforeMethod(
            findFirstMethodByExactType(
                "com.baidu.tbadk.coreExtra.view.ImagePagerAdapter",
                ArrayList::class.java
            )
        ) { param ->
            // 宿主传 null/非 List 时防御性跳过, 不刷 NPE 日志
            val list = param.args[0] as? ArrayList<*> ?: return@hookBeforeMethod
            // 列表元素理论上恒为 String, 防御性 as?: 类型漂移时不抛 CCE 刷日志
            // 先在局部完成过滤再一次性发布: 两步赋值会让并发长按读到半过滤列表
            val filtered = ArrayList(list)
            filtered.removeIf { (it as? String)?.startsWith("####mLiveRoomPageProvider") == true }
            mList = filtered
        }

        val imageViewerBottomLayoutClass = findClass("com.baidu.tbadk.coreExtra.view.ImageViewerBottomLayout")
        val declaredFields = mutableListOf(*imageViewerBottomLayoutClass.declaredFields)
        declaredFields.removeIf { it.type != ImageView::class.java }

        // 宿主版本混淆后可能不再声明 ImageView 字段, 此时放弃定位而不是 IndexOutOfBounds
        mDownloadImageViewField = declaredFields.lastOrNull()?.apply {
            // 宿主混淆字段几乎必为 private: 不 setAccessible 时 Field.get 抛
            // IllegalAccessException 被包装层吞掉, 长按保存监听器永远装不上
            isAccessible = true
        }
        mDownloadImageViewField?.let {
            hookAfterConstructor(
                "com.baidu.tbadk.coreExtra.view.ImageViewerBottomLayout",
                Context::class.java
            ) { param ->
                // as? 与上方"找不到下载按钮即放弃定位"口径一致: 构造后字段仍为 null
                // 时静默放弃而非抛 TypeCastException(虽被包装层吞掉, 但语义上这就是
                // "定位不到", 不该留异常日志)
                val imageView = it[param.thisObject] as? ImageView ?: return@hookAfterConstructor
                imageView.setOnLongClickListener(saveImageListener)
            }
        }
    }

    private val saveImageListener = OnLongClickListener {
        // mList 只在 ImagePagerAdapter hook 中赋值, 分享弹窗路径长按时可能尚未初始化
        if (!::mList.isInitialized) return@OnLongClickListener false
        val images = mList.toList()
        // 已初始化但为空列表: 长按会走完整下载流程并以"保存失败"收尾, 还白占
        // sDownloading 锁一轮, 提前给出明确提示
        if (images.isEmpty()) {
            showTbToast("暂无可保存的图片", TbToast.LENGTH_SHORT)
            return@OnLongClickListener true
        }
        // 防连点: 多个下载线程并发读写 mList 且重复写相册
        if (!sDownloading.compareAndSet(false, true)) {
            showTbToast("正在下载中，请稍候", TbToast.LENGTH_SHORT)
            return@OnLongClickListener true
        }
        showTbToast(
            "开始下载%d张图片".format(Locale.CHINA, images.size),
            TbToast.LENGTH_SHORT
        )

        val baseTime = System.currentTimeMillis()
        // 精确到毫秒: 仅到秒时, 上一批刚结束、同一墙钟秒内开始下一批会生成
        // 完全相同的文件名, pre-Q 按绝对路径插入会互相覆盖
        val formattedTime = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.CHINA).format(Date(baseTime))

        thread {
            var failed = 0
            try {
                images.forEachIndexed { index, url ->
                    // 逐张隔离: 单个坏 URL/失败只损失该张, 不中断整批
                    val ok = runCatching {
                        val formattedUrl = (url as? String)?.substringBeforeLast("*") ?: return@runCatching false
                        saveImage(
                            formattedUrl,
                            "${formattedTime}_${"%02d".format(Locale.CHINA, index)}",
                            getContext()
                        )
                        true
                    }.getOrDefault(false)
                    if (!ok) failed++
                }
                val saved = images.size - failed
                Handler(Looper.getMainLooper()).post {
                    if (saved > 0) {
                        showTbToast(
                            "已保存%d张图片至手机相册".format(Locale.CHINA, saved),
                            TbToast.LENGTH_SHORT
                        )
                    } else {
                        showTbToast("保存失败", TbToast.LENGTH_SHORT)
                    }
                }
            } catch (e: Throwable) {
                // 模块运行在宿主进程: 线程内未捕获异常会经 UncaughtExceptionHandler 崩掉宿主,
                // 此处兜底为"保存失败"提示; 用 Throwable 而非 Exception, OOM 等 Error 同样拦截
                Handler(Looper.getMainLooper()).post {
                    showTbToast("保存失败", TbToast.LENGTH_SHORT)
                }
            } finally {
                sDownloading.set(false)
            }
        }
        true
    }

    companion object {
        // 防重复下载标志
        private val sDownloading = java.util.concurrent.atomic.AtomicBoolean(false)

        // 单张图片的字节上限(含"查看原图"场景的大图), 防异常超大响应全量读入内存
        private const val MAX_IMAGE_BYTES = 32 * 1024 * 1024

        private fun saveImage(url: String, filename: String, context: Context) {
            // 显式超时: openStream() 无超时, 慢服务器会令下载线程无限挂起
            // (每次长按新起线程, 累积泄漏)
            val connection = URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            try {
                // 响应校验: 非 200 或明确非图片的 Content-Type(鉴权页/错误页 HTML)
                // 直接按失败处理, 不再存成假图片; Content-Type 缺失时放行(部分 CDN 不下发)
                val responseCode = connection.responseCode
                if (responseCode != java.net.HttpURLConnection.HTTP_OK) {
                    throw java.io.IOException("HTTP $responseCode for $url")
                }
                val contentType = connection.contentType
                if (!contentType.isNullOrEmpty() && !contentType.startsWith("image/")) {
                    throw java.io.IOException("Non-image content type: $contentType")
                }
                connection.inputStream.use { inputStream ->
                    val byteBuffer = toByteBuffer(inputStream, MAX_IMAGE_BYTES)
                    // 扩展名与 MIME 用同一推导值: Content-Type 可信时按 subtype,
                    // 否则魔法数兜底(见 FileUtils.getExtension)——两者不一致会让
                    // 相册按 MIME 解码失败
                    val ext = getExtension(byteBuffer, contentType)
                    val imageDetails = ContentValues().apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(
                                MediaStore.MediaColumns.RELATIVE_PATH,
                                "${Environment.DIRECTORY_PICTURES}${File.separator}tieba"
                            )
                        } else {
                            val path = File(
                                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                                "tieba"
                            ).apply { mkdirs() }
                            put(
                                MediaStore.MediaColumns.DATA,
                                "${path}${File.separator}$filename.$ext"
                            )
                        }
                        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/$ext")
                        val currentTime = System.currentTimeMillis()
                        put(MediaStore.MediaColumns.DATE_ADDED, currentTime / 1000)
                        put(MediaStore.MediaColumns.DATE_MODIFIED, currentTime / 1000)
                    }

                    val resolver = context.contentResolver
                    var imageUri: Uri? = null
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            imageDetails.put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                        imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageDetails)
                        val insertedUri = imageUri ?: throw IOException("Failed to insert image into MediaStore")
                        resolver.openFileDescriptor(insertedUri, "w")?.use { descriptor ->
                            copy(byteBuffer, descriptor.fileDescriptor)
                        } ?: throw IOException("Failed to open file descriptor")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            resolver.update(
                                insertedUri,
                                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                                null,
                                null
                            )
                        }
                    } catch (e: Throwable) {
                        imageUri?.let { runCatching { resolver.delete(it, null, null) } }
                        throw e
                    }
                }
            } finally {
                // 归还连接到池并释放资源, 避免连接池僵持
                connection.disconnect()
            }
        }
    }
}
