package io.nekohasekai.sagernet.widget

import android.graphics.Rect
import android.os.Build
import android.view.View
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

object ListListener : OnApplyWindowInsetsListener {
    override fun onApplyWindowInsets(view: View, insets: WindowInsetsCompat) = insets.apply {
        view.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
    }
}

/**
 * Keep this view's content clear of the system bars, preserving the padding its layout
 * already declares (unlike [ListListener], which overwrites the bottom padding).
 *
 * Edge-to-edge is only forced from API 35, so nothing is installed below it and pre-35
 * layout stays exactly as it was.
 *
 * @param statusBarTop pad the top by the status bar inset too, for full-height views.
 * @param bottomAtLeast the declared bottom padding already reserves space (e.g. for a
 *   FAB): grow it to the navigation bar inset instead of adding the two together.
 */
fun View.padForSystemBars(statusBarTop: Boolean = false, bottomAtLeast: Boolean = false) {
    if (Build.VERSION.SDK_INT < 35) return
    val base = Rect(paddingLeft, paddingTop, paddingRight, paddingBottom)
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val navigationBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        v.updatePadding(
            top = if (statusBarTop) {
                base.top + insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            } else base.top,
            bottom = if (bottomAtLeast) maxOf(base.bottom, navigationBar) else base.bottom + navigationBar
        )
        insets
    }
}
