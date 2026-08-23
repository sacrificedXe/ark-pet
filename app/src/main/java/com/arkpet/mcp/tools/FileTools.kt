package com.arkpet.mcp.tools

import android.content.Context
import android.os.Environment
import android.util.Base64
import com.arkpet.util.PetLog
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件工具：列目录 / 删除 / 搜索 / 回传。
 *
 * 重写要点：
 * 1. 删除加白名单防护——只允许删外部存储与应用自身目录下的文件，且拒绝删根目录/整个 /sdcard。
 *    原实现 deleteRecursively 任意路径，一条 `{"path":"/sdcard"}` 就能清空用户全部数据。
 * 2. scan 加超时与访问异常吞掉（Android 11+ 大量目录会抛 SecurityException 直接中断整次扫描）。
 * 3. pull 大文件不再复制一份到 cache（磁盘本来就紧），直接返回路径让服务端按需拉。
 */
class FileTools(private val ctx: Context) {

    companion object {
        private const val TAG = "FileTools"
        private const val MAX_BASE64_SIZE = 512 * 1024
        private const val SCAN_TIME_LIMIT_MS = 8_000L
    }

    private val extRoot: String = runCatching {
        Environment.getExternalStorageDirectory().canonicalPath
    }.getOrDefault("/sdcard")

    fun list(p: JSONObject): JSONObject {
        val path = p.optString("path").ifBlank { extRoot }
        val dir = File(path)
        if (!dir.exists()) return err("not_found: $path")
        if (!dir.isDirectory) return err("not_a_dir: $path")
        val children = dir.listFiles()
            ?: return err("access_denied: $path（Android 11+ 部分目录不可读）")
        val entries = children.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name })
            .take(p.optInt("limit", 300))
            .map {
                JSONObject().put("name", it.name).put("is_dir", it.isDirectory)
                    .put("size", it.length()).put("modified", it.lastModified())
            }
        return JSONObject().put("status", "ok")
            .put("data", JSONObject().put("path", dir.absolutePath)
                .put("count", entries.size).put("entries", entries))
    }

    fun delete(p: JSONObject): JSONObject {
        val path = p.optString("path")
        if (path.isEmpty()) return err("need path")
        val f = File(path)
        if (!f.exists()) return err("not_found: $path")

        val canonical = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
        val guard = guardDelete(canonical, f)
        if (guard != null) return err(guard)

        val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
        PetLog.w(TAG, "删除 $canonical → $ok")
        return if (ok) JSONObject().put("status", "ok")
            .put("data", JSONObject().put("deleted", canonical))
        else err("delete_failed（无权限或目录非空不可删）")
    }

    /** 返回非 null 表示拒绝执行，内容为拒绝原因 */
    private fun guardDelete(canonical: String, f: File): String? {
        val allowedRoots = listOfNotNull(
            extRoot,
            ctx.filesDir.parent,
            ctx.getExternalFilesDir(null)?.absolutePath,
            ctx.cacheDir.absolutePath
        )
        if (allowedRoots.none { canonical.startsWith(it) }) {
            return "refused：仅允许删除外部存储或本应用目录内的文件，目标=$canonical"
        }
        // 禁止直接删存储根或一级重要目录
        val dangerous = allowedRoots + listOf(
            "$extRoot/Android", "$extRoot/DCIM", "$extRoot/Download",
            "$extRoot/Pictures", "$extRoot/Documents"
        )
        if (dangerous.any { canonical.trimEnd('/') == it.trimEnd('/') }) {
            return "refused：拒绝删除存储根目录或系统级目录 $canonical"
        }
        if (f.isDirectory) {
            val count = countFiles(f, 0)
            if (count > 500) return "refused：目录内含 $count 个文件（>500），风险过高，请指定更小范围"
        }
        return null
    }

    private fun countFiles(dir: File, depth: Int): Int {
        if (depth > 6) return 0
        var n = 0
        dir.listFiles()?.forEach {
            n += if (it.isDirectory) countFiles(it, depth + 1) else 1
            if (n > 500) return n
        }
        return n
    }

    fun scan(p: JSONObject): JSONObject {
        val keyword = p.optString("keyword")
        val root = File(p.optString("root").ifBlank { extRoot })
        val hits = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + SCAN_TIME_LIMIT_MS
        scanDir(root, keyword, hits, 0, p.optInt("depth", 4), p.optInt("limit", 100), deadline)
        val truncated = System.currentTimeMillis() >= deadline
        return JSONObject().put("status", "ok")
            .put("data", JSONObject().put("count", hits.size)
                .put("hits", hits).put("timeout_truncated", truncated))
    }

    private fun scanDir(
        dir: File, kw: String, hits: MutableList<String>,
        depth: Int, maxDepth: Int, limit: Int, deadline: Long
    ) {
        if (depth > maxDepth || hits.size >= limit || System.currentTimeMillis() > deadline) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (c in children) {
            if (hits.size >= limit || System.currentTimeMillis() > deadline) return
            if (c.isDirectory) scanDir(c, kw, hits, depth + 1, maxDepth, limit, deadline)
            else if (kw.isEmpty() || c.name.contains(kw, true)) hits.add(c.absolutePath)
        }
    }

    fun pull(p: JSONObject): JSONObject {
        val path = p.optString("path")
        if (path.isEmpty()) return err("need path")
        val f = File(path)
        if (!f.exists()) return err("not_found: $path")
        if (f.isDirectory) return err("is_directory")
        if (!f.canRead()) return err("access_denied: $path")

        val size = f.length()
        val data = JSONObject().put("path", f.absolutePath).put("size", size)
            .put("mime", guessMime(path)).put("modified", f.lastModified())
        return if (size <= MAX_BASE64_SIZE) {
            data.put("base64", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
            JSONObject().put("status", "ok").put("data", data)
        } else {
            // 不复制：磁盘紧张，且复制没解决传输问题
            data.put("base64_omitted", "文件 $size 字节超过 $MAX_BASE64_SIZE，未内联传输")
            data.put("hint", "如需取回，请分片读取或先在设备上压缩")
            JSONObject().put("status", "ok").put("data", data)
        }
    }

    private fun guessMime(path: String): String {
        val p = path.lowercase()
        return when {
            p.endsWith(".jpg") || p.endsWith(".jpeg") -> "image/jpeg"
            p.endsWith(".png") -> "image/png"
            p.endsWith(".webp") -> "image/webp"
            p.endsWith(".gif") -> "image/gif"
            p.endsWith(".mp4") -> "video/mp4"
            p.endsWith(".mp3") -> "audio/mpeg"
            p.endsWith(".txt") || p.endsWith(".log") -> "text/plain"
            p.endsWith(".json") -> "application/json"
            p.endsWith(".apk") -> "application/vnd.android.package-archive"
            p.endsWith(".zip") -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    @Suppress("unused")
    private fun timestamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun err(msg: String) = JSONObject().put("status", "error").put("error", msg)
}
