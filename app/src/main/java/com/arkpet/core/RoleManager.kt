package com.arkpet.core

data class SkinInfo(val id: String, val name: String)

data class RoleInfo(
    val id: String,
    val name: String,
    val skins: List<SkinInfo>
) {
    fun skinOf(id: String) = skins.find { it.id == id }
    fun defaultSkin() = skins.firstOrNull() ?: SkinInfo("base", "初雪")
}

/**
 * 角色-皮肤注册表。
 * 新增角色：往 roles 列表 append RoleInfo，并把 asset/pet/{skin_id}_{anim}.webp 放入 assets 目录即可，无需改代码。
 * 默认动画集：asset 文件前缀约定为 "{skin_id}_"（如 cloud_trail_Default.webp）。
 */
object RoleRegistry {
    val roles: List<RoleInfo> = listOf(
        RoleInfo(
            id = "chuxue",
            name = "初雪",
            skins = listOf(
                SkinInfo("base", "初雪"),
                SkinInfo("snow", "雪境"),
                SkinInfo("cloud_trail", "云迹")
            )
        )
    )

    fun byId(id: String) = roles.find { it.id == id }
    fun allSkins() = roles.flatMap { r -> r.skins }
    fun roleOfSkin(skinId: String): RoleInfo? = roles.find { r -> r.skinOf(skinId) != null }
    fun resolve(skinId: String): SkinInfo = allSkins().find { it.id == skinId } ?: allSkins().first()
}
