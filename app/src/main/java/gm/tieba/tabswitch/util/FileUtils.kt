@file:JvmName("FileUtils")

package gm.tieba.tabswitch.util

import gm.tieba.tabswitch.XposedContext
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

fun copy(bb: ByteBuffer, output: Any?) {
    val outputStream: OutputStream = when (output) {
        is OutputStream -> output
        is File -> FileOutputStream(output)
        is FileDescriptor -> FileOutputStream(output)
        is String -> FileOutputStream(output)
        else -> throw IllegalArgumentException("unknown output type")
    }
    outputStream.use {
        // 只写 position..limit 之间的有效数据, 兼容 slice()/position 非零的 buffer
        val bytes = ByteArray(bb.remaining())
        bb.get(bytes)
        it.write(bytes)
    }
}

// 带上限的读入: 异常超大响应(URL 被劫持/错误页)全量读入会造成内存尖峰乃至 OOM,
// 超限抛 IOException 由调用方按下载失败处理
fun toByteBuffer(inputStream: InputStream, maxBytes: Int): ByteBuffer {
    val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(64 * 1024)
    var total = 0
    while (true) {
        val read = inputStream.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw IOException("Response exceeds size limit: $maxBytes bytes")
        out.write(buffer, 0, read)
    }
    return ByteBuffer.wrap(out.toByteArray())
}

fun getExtension(bb: ByteBuffer): String {
    // 相对读取, 不依赖 bb.array(): direct/只读缓冲的 array() 会抛异常。
    // WebP 头 12 字节("RIFF"+长度+"WEBP"), 不足 12 字节时按实际可读字节取头部
    val len = minOf(12, bb.remaining())
    val chunk = ByteArray(len).also {
        bb.get(it)
        bb.rewind()
    }.toString(Charsets.ISO_8859_1)
    return when {
        chunk.startsWith("RIFF") && chunk.contains("WEBP") -> "webp"
        chunk.startsWith("BM") -> "bmp"
        chunk.contains("GIF") -> "gif"
        chunk.contains("PNG") -> "png"
        else -> "jpeg"
    }
}

// 服务端 Content-Type 可信时按其 subtype 推导扩展名的白名单
private val KNOWN_IMAGE_SUBTYPES = setOf(
    "jpeg", "jpg", "png", "gif", "webp", "bmp", "avif", "heic", "heif"
)

fun getExtension(bb: ByteBuffer, contentType: String?): String {
    // Content-Type 明确为 image/* 时优先按 subtype 推导扩展名: 魔法数探测只识别
    // webp/bmp/gif/png, AVIF/HEIC 等一律兜底为 jpeg——存出的文件是 image/jpeg
    // MIME + .jpg 后缀, 按 MIME 解码的相册/图片应用会打不开。Content-Type 缺失
    // 或不可识别时退回魔法数探测(部分 CDN 不下发)
    val subtype = contentType
        ?.removePrefix("image/")
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
    if (subtype != null && subtype.isNotEmpty() && subtype != "image/*" &&
        subtype in KNOWN_IMAGE_SUBTYPES
    ) {
        return subtype
    }
    return getExtension(bb)
}

fun getParent(path: String): String {
    // 路径不含分隔符(或已在根)时返回空串, 避免 substring(0, -1) 越界
    val index = path.lastIndexOf(File.separatorChar)
    if (index <= 0) return ""
    return path.substring(0, index)
}

fun getAssetFileContent(filename: String?): String? {
    return try {
        filename?.let { name ->
            XposedContext.sAssetManager.open(name).use { inputStream ->
                inputStream.bufferedReader().use { reader ->
                    reader.readText()
                }
            }
        }
    } catch (ignored: IOException) {
        null
    }
}