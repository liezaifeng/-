package com.ideawav.app

import com.ideawav.app.R

/**
 * 音频资源配置中心。
 * 新增音效包时只需在此文件添加条目，无需改动其他代码。
 */
object AudioConfig {

    /** 引擎音效包：每个包只有一个引擎音频文件 */
    data class EnginePack(val displayName: String, val rawResId: Int)

    /** 效果音效包：包含单次 up/down 和循环 up/down 共四个音频文件 */
    data class EffectPack(
        val displayName: String,
        val upRawResId: Int,          // 单次 up
        val downRawResId: Int,        // 单次 down
        val loopUpRawResId: Int,      // 循环 up（保持音效，如 mxt_loop）
        val loopDownRawResId: Int     // 循环 down（怠速音效，如 mxt_pool）
    )

    // ===== 引擎音效列表 =====
    val enginePacks: List<EnginePack> = listOf(
        EnginePack("出来兜风", R.raw.mxt_eng),
        EnginePack("星之卡比", R.raw.sjt_eng),
       EnginePack("可爱猫猫", R.raw.git_eng),
       EnginePack("斗地主", R.raw.hs_eng),
       EnginePack("QQ堂", R.raw.hor_eng),
       EnginePack("英雄联盟", R.raw.elc_eng),
        // 以后添加：EnginePack("Turbo", R.raw.turbo_eng),
    )

    // ===== 效果音效列表 =====
    val effectPacks: List<EffectPack> = listOf(
        EffectPack(
            displayName = "冰箱推过来",
            upRawResId = R.raw.mxt_up,
            downRawResId = R.raw.mxt_down,
            loopUpRawResId = R.raw.mxt_loop,   // 循环 up
            loopDownRawResId = R.raw.mxt_pool  // 循环 down
        ),
        EffectPack(
            displayName = "期待碎了",
            upRawResId = R.raw.sjt_up,
            downRawResId = R.raw.sjt_down,
            loopUpRawResId = R.raw.sjt_loop,   // 循环 up
            loopDownRawResId = R.raw.sjt_pool  // 循环 down
        ),
        EffectPack(
            displayName = "弦又接好了",
            upRawResId = R.raw.git_up,
            downRawResId = R.raw.git_down,
            loopUpRawResId = R.raw.git_loop,   // 循环 up
            loopDownRawResId = R.raw.git_pool  // 循环 down
        ),
        EffectPack(
            displayName = "马蹄声",
            upRawResId = R.raw.hs_up,
            downRawResId = R.raw.hs_down,
            loopUpRawResId = R.raw.hs_loop,   // 循环 up
            loopDownRawResId = R.raw.hs_pool  // 循环 down
        ),
        EffectPack(
            displayName = "四十",
            upRawResId = R.raw.hor_up,
            downRawResId = R.raw.hor_down,
            loopUpRawResId = R.raw.hor_loop,   // 循环 up
            loopDownRawResId = R.raw.hor_pool  // 循环 down
        ),
        EffectPack(
            displayName = "皮卡丘",
            upRawResId = R.raw.elc_up,
            downRawResId = R.raw.elc_down,
            loopUpRawResId = R.raw.elc_loop,   // 循环 up
            loopDownRawResId = R.raw.elc_pool  // 循环 down
        )
        // 以后添加：EffectPack("Turbo", R.raw.turbo_up, R.raw.turbo_down, R.raw.turbo_loop, R.raw.turbo_pool),
    )

    /** 获取引擎包显示名称列表（用于 Spinner） */
    val enginePackNames: List<String> get() = enginePacks.map { it.displayName }

    /** 获取效果包显示名称列表（用于 Spinner） */
    val effectPackNames: List<String> get() = effectPacks.map { it.displayName }
}