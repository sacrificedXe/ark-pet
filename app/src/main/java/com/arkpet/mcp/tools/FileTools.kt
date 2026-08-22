package com.arkpet.mcp.tools

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File

/**
 * 文件工具：列出目录 / 删除文件 / 扫描文件
 * supervise 模式由服务器端二次确认后才真正调用 delete
 * 安全修复：路径遍历防护、扫描深度/数量硬限制
 */
class FileTools(private val ctx: Context) {

    private val allowedRoot: File = Environment.getExternalStorageDirectory().canonicalFile

    private fun resolveSafe(path: String): File? {
        val f = File(path).canonicalFile
        return if (f.path.startsWith(allowedRoot.path)) f else null
    }

    fun list(p: JSONObject): JSONObject {
        val path = p.optString("path", allowedRoot.absolutePath)
        val dir = resolveSafe(path) ?: return err("path_not_allowed: $path")
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
        val f = resolveSafe(path) ?: return err("path_not_allowed: $path")
        if (!f.exists()) return err("not_found")
        val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
        return if (ok) JSONObject().put("status", "ok")
        else err("delete_failed (可能无权限，需要 root)")
    }

    fun scan(p: JSONObject): JSONObject {
        val keyword = p.optString("keyword", "")
        if (keyword.isEmpty()) return err("keyword_required")
        val hits = mutableListOf<String>()
        // 硬限制：最大深度 3，最大结果 50，防止全盘扫描
        scanDir(allowedRoot, keyword, hits, 0, 3, 50)
        return JSONObject().put("status", "ok")
            .put("data", JSONObject().put("hits", hits))
    }

    fun pull(p: JSONObject): JSONObject {
        // P3：文件回传（App->服务器->初雪QQ），此处占位
        return JSONObject().put("status", "pending").put("note", "P3 实现文件回传")
    }

    private fun scanDir(dir: File, kw: String, hits: MutableList<String>, depth: Int, maxDepth: Int, limit: Int) {
        if (depth > maxDepth || hits.size >= limit) return
        val children = dir.listFiles() ?: return
        for (c in children) {
            if (c.isDirectory) scanDir(c, kw, hits, depth + 1, maxDepth, limit)
            else if (c.name.contains(kw, ignoreCase = true)) {
                hits.add(c.absolutePath)
                if (hits.size >= limit) return
            }
        }
    }

    private fun err(msg: String) = JSONObject().put("status", "error").put("error", msg)
}
