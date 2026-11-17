package gm.tieba.tabswitch.hooker.auto

import gm.tieba.tabswitch.logBoth
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 签到 HTTP 层：改用平台自带 HttpURLConnection。
 *
 * <p>此前依赖 OkHttp 只为三个同步 JSON 接口（tbs/关注列表/签到），而 HttpURLConnection
 * 原生支持 HTTP/1.1 keep-alive（平台按 host 缓存 TCP 连接，读完整响应即自动归还连接池），
 * 删除该依赖可让 okhttp+okio 整链退出 classes.dex（dex 是 APK 体积大头）。
 * 语义与原 OkHttp 实现对齐：显式超时、固定 UA/Cookie 头、非 2xx 抛 IOException、
 * 响应体全量读取后由调用方解析 JSON。connection 头为平台受限头由系统自管
 * （HTTP/1.1 默认 keep-alive，与原显式 addHeader 效果一致）。
 *
 * <p>线程模型不变：所有调用方（AutoSign 签到线程 / FollowFilter 拉取线程）均在
 * 后台线程同步调用，HttpURLConnection 的阻塞式 API 与之吻合，无需异步封装。
 */
object AutoSignHelper {

    // FollowFilter 拉取线程与 AutoSign 签到线程会并发读写, volatile 保证可见性。
    // 可空而非 lateinit: 先于 setCookie 的调用(如 FollowFilter 抢先拉取)不应抛
    // UninitializedPropertyAccessException, 无 Cookie 的请求本就会失败, 与断网路径一致
    @Volatile
    private var sCookie: String? = null

    // 显式超时沿用原 OkHttp 配置: 平台默认(连接 0=无限/读 0=无限)对签到循环偏危险,
    // 偶发网络抖动会把当日 3 轮重试拖成上百次请求各挂 20s+
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 30_000

    fun setCookie(BDUSS: String) {
        sCookie = "BDUSS=$BDUSS"
    }

    fun get(url: String): JSONObject {
        val respContent = try {
            exchange(url, null)
        } catch (e: IOException) {
            logBoth("get请求错误 -- $e")
            null
        }
        return parseJsonOrNull(respContent, "get") ?: JSONObject()
    }

    fun post(url: String, body: String): JSONObject {
        val respContent = try {
            exchange(url, body)
        } catch (e: IOException) {
            logBoth("post请求错误 -- $e")
            null
        }
        return parseJsonOrNull(respContent, "post") ?: JSONObject()
    }

    // GET(body=null) 与 POST 的公共通道: 建连、请求头、状态码判定、响应体读取
    // 全部一致, 仅 POST 额外写请求体。统一入口让两个调用方的错误文案与
    // JSON 兜底行为保持同一份实现
    @Throws(IOException::class)
    private fun exchange(url: String, body: String?): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = if (body == null) "GET" else "POST"
            // 请求体字段顺序由调用方拼好(kw/tbs/sign), Content-Type 必须是
            // x-www-form-urlencoded: 签到接口的 sign 校验基于服务端按此解析的参数
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("charset", "UTF-8")
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/39.0.2171.71 Safari/537.36"
            )
            setRequestProperty("Cookie", sCookie ?: "")
        }
        try {
            if (body != null) {
                // POST 请求体必须在读响应码前写出: getOutputStream 隐式置 doOutput=true,
                // 漏写会让签到接口收到空体而 sign 校验必然失败
                conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            // 非 2xx 与原实现同语义: 抛 IOException 走调用方的"接口失败"路径, 不解析错误体
            val code = conn.responseCode
            if (code !in 200..299) {
                // 消费错误响应体属协议收尾(不读流直接抛会让服务端/代理看到连接
                // 在响应中途被弃); 随后 catch 路径会 disconnect, 错误连接不复用
                runCatching { conn.errorStream?.use { it.readBytes() } }
                throw IOException("Response code: $code")
            }
            return conn.inputStream.use { stream ->
                // 按字节读出后统一 UTF-8 解码, 不依赖平台按 Content-Type 猜字符集
                String(stream.readBytes(), StandardCharsets.UTF_8)
            }
            // 不调 disconnect(): 读完整响应后平台自动把连接归还 keep-alive 池,
            // 显式 disconnect 反而强制关 socket, 破坏复用
        } catch (e: IOException) {
            // 异常路径连接状态未知(可能读到半截响应), 显式断开防止坏连接回池
            conn.disconnect()
            throw e
        }
    }

    // JSON 解析兜底: 只捕获 IOException 时, 服务端 200 + HTML(如风控页/网关错误页)
    // 会令 JSONObject 抛 JSONException 直接上抛调用方; 统一降级为空对象,
    // 调用方按"接口失败"路径处理
    private fun parseJsonOrNull(content: String?, tag: String): JSONObject? {
        if (content == null) return null
        return try {
            JSONObject(content)
        } catch (e: Exception) {
            logBoth("${tag}响应非JSON(前100字符): ${content.take(100)} -- $e")
            null
        }
    }

    fun enCodeMd5(str: String): String {
        return try {
            // 生成一个MD5加密计算摘要
            val md = MessageDigest.getInstance("MD5")
            // 计算md5函数
            md.update(str.toByteArray(StandardCharsets.UTF_8))
            // digest() 返回 16 字节，规范 hex 恒为 32 位；%032x 保证高字节为 0 时保留前导零
            String.format("%032x", BigInteger(1, md.digest()))
        } catch (e: Exception) {
            logBoth("字符串进行MD5加密错误 -- $e")
            ""
        }
    }
}
