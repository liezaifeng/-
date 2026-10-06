package com.ideawav.app

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView

/**
 * 背景音乐下拉框适配器：
 * - 选中项（收起时）：第一项显示自定义音乐名（文件名 / "网络音乐"）
 * - 下拉列表（展开时）：第一项始终显示"自定义音乐"
 */
class EngineSpinnerAdapter(
    context: Context,
    items: List<String>
) : ArrayAdapter<String>(context, R.layout.spinner_item, items) {

    var customDisplayName: String = items.getOrElse(0) { "自定义音乐" }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent)
        if (position == 0) {
            (view as? TextView)?.text = customDisplayName
        }
        return view
    }
}
