package com.ideawav.app

import android.content.Context
import android.util.AttributeSet
import android.widget.Spinner

/**
 * 与普通 Spinner 的区别：当用户在下拉列表中选中与当前相同的项时，也会触发 onItemSelected。
 * 用于"自定义音乐"选项可被重复点击进入设置页。
 */
class AlwaysSelectSpinner @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Spinner(context, attrs) {

    override fun setSelection(position: Int) {
        val wasSame = position == selectedItemPosition
        super.setSelection(position)
        if (wasSame) {
            onItemSelectedListener?.onItemSelected(this, selectedView, position, selectedItemId)
        }
    }
}
