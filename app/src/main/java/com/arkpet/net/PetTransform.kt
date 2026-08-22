package com.arkpet.net

import android.content.Context
import android.content.SharedPreferences

/**
 * 桌宠变换状态：位置、缩放、朝向、显隐
 * 本地持久化到 SharedPreferences，MCP 可读写
 */
data class PetTransform(
    val x: Float = 0f,
    val y: Float = 0f,
    val scale: Float = 1f,
    val flipX: Boolean = false,
    val visible: Boolean = true
) {
    companion object {
        private const val PREFS = "arkpet_transform"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val KEY_SCALE = "scale"
        private const val KEY_FLIP = "flipX"
        private const val KEY_VISIBLE = "visible"
        private const val MIN_SCALE = 0.4f
        private const val MAX_SCALE = 2.5f

        fun load(ctx: Context): PetTransform {
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return PetTransform(
                x = sp.getFloat(KEY_X, 0f),
                y = sp.getFloat(KEY_Y, 0f),
                scale = sp.getFloat(KEY_SCALE, 1f).coerceIn(MIN_SCALE, MAX_SCALE),
                flipX = sp.getBoolean(KEY_FLIP, false),
                visible = sp.getBoolean(KEY_VISIBLE, true)
            )
        }

        fun save(ctx: Context, t: PetTransform) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                putFloat(KEY_X, t.x)
                putFloat(KEY_Y, t.y)
                putFloat(KEY_SCALE, t.scale)
                putBoolean(KEY_FLIP, t.flipX)
                putBoolean(KEY_VISIBLE, t.visible)
                apply()
            }
        }

        fun clampScale(scale: Float) = scale.coerceIn(MIN_SCALE, MAX_SCALE)
    }
}
