package com.arkpet.mcp.tools

import android.content.Context
import android.os.Environment
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 文件工具：列目录/删除/扫描/回传
 * pull() 真实现：读文件 → base64（小文件）或保存临时 HTTP 供下载（大文件）
 */
class FileTools(private val ctx: Context) {

    private val TEMP_HTTP_DIR = File(ctx.cacheDir, "file_pull").apply { mkdirs() }
    private val MAX_BASE64_SIZE = 512 * 1024 // 512KB 以上走临时 HTTP

    fun list(p: JSONObject): JSONObject {
        val path = p.optString("path", Environment.getExternalStorageDirectory().absolutePath)
        val dir = File(path)
        if (!dir.isDirectory) return err("not_a_dir: $path")
        val entries = dir.listFiles()?.sortedBy { it.name }?.map {
            JSONObject().put("name", it.name).put("is_dir", it.isDirectory).put("size", it.length())
        } ?: emptyList()
        return JSONObject().put("status", "ok")
            .put("data", JSONObject().put("path", path).put("entries", entries))
    }

    fun delete(p: JSONObject): JSONObject {
        val path = p.optString("path")
        if (path.isEmpty()) return err("need path")
        val f = File(path)
        if (!f.exists()) return err("not_found")
        val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
        return if (ok) JSONObject().put("status", "ok")
        else err("delete_failed (可能无权限，需要 root)")
    }

    fun scan(p: JSONObject): JSONObject {
        val keyword = p.optString("keyword", "")
        val root = Environment.getExternalStorageDirectory()
        val hits = mutableListOf<String>()
        scanDir(root, keyword, hits, 0, p.optInt("depth", 4), p.optInt("limit", 100))
        return JSONObject().put("status", "ok")
            .put("data", JSONObject().put("hits", hits))
    }

    /** 文件回传：小文件 base64，大文件返回临时 HTTP 下载链接 */
    fun pull(p: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val path = p.optString("path")
        if (path.isEmpty()) return err("need path")
        val f = File(path)
        if (!f.exists()) return err("not_found")
        if (f.isDirectory) return err("is_directory")

        val size = f.length()
        if (size <= MAX_BASE64_SIZE) {
            // 小文件：直接 base64
            val bytes = f.readBytes()
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            return JSONObject().put("status", "ok").put("data", JSONObject().apply {
                put("path", path)
                put("size", size)
                put("base64", b64)
                put("mime", guessMime(path))
            })
        } else {
            // 大文件：复制到临时目录，返回 HTTP 下载地址（需服务端配合 /file_pull/<token>）
            val token = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
            val dest = File(TEMP_HTTP_DIR, "$token_${f.name}")
            f.copyTo(dest)
            // 清理 1 小时前的临时文件
            cleanupOld()
            return JSONObject().put("status", "ok").put("data", JSONObject().apply {
                put("path", path)
                put("size", size)
                put("download_url", "/file_pull/$token/${f.name}")
                put("mime", guessMime(path))
                put("expires_in", 3600)
            })
        }
    }

    private fun scanDir(dir: File, kw: String, hits: MutableList<String>, depth: Int, maxDepth: Int, limit: Int) {
        if (depth > maxDepth || hits.size >= limit) return
        val children = dir.listFiles() ?: return
        for (c in children) {
            if (c.isDirectory) scanDir(c, kw, hits, depth + 1, maxDepth, limit)
            else if (kw.isEmpty() || c.name.contains(kw, ignoreCase = true)) {
                hits.add(c.absolutePath)
                if (hits.size >= limit) return
            }
        }
    }

    private fun guessMime(path: String): String = when {
        path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".webp") -> "image/webp"
        path.endsWith(".mp4") -> "video/mp4"
        path.endsWith(".txt") || path.endsWith(".log") -> "text/plain"
        path.endsWith(".json") -> "application/json"
        path.endsWith(".apk") -> "application/vnd.android.package-archive"
        else -> "application/octet-stream"
    }

    private fun cleanupOld() {
        val cutoff = System.currentTimeMillis() - 3600_000
        TEMP_HTTP_DIR.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    private fun err(msg: String) = JSONObject().put("status", "error").put("error", msg)
}
