package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.appbar.AppBarLayout

/**
 * An AppBarLayout that keeps its toolbar below the status bar and beside cutouts / side
 * navigation bars on edge-to-edge windows. Owning the insets here (instead of a listener on
 * the Activity content) means a bar inflated by a later fragment pads itself on attach.
 */
class InsetAppBarLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : AppBarLayout(context, attrs) {
    init {
        padForSystemBars(statusBarTop = true, bottom = false)
    }
}
