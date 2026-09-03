package io.nekohasekai.sagernet.ui

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.addCallback
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.utils.Theme

abstract class ThemedActivity : AppCompatActivity {
    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    var themeResId = 0
    var uiMode = 0
    open val isDialog = false

    // Navigation bar bottom inset, tracked on API 35+ to lift snackbars above the gesture pill
    private var navigationBarInset = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        if (!isDialog) {
            Theme.apply(this)
        } else {
            Theme.applyDialog(this)
        }
        Theme.applyNightTheme()

        super.onCreate(savedInstanceState)

        uiMode = resources.configuration.uiMode

        if (Build.VERSION.SDK_INT >= 35) {
            ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { _, insets ->
                val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
                navigationBarInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                findViewById<AppBarLayout>(R.id.appbar)?.apply {
                    updatePadding(top = top)
//                Logs.w("appbar $top")
                }
//            findViewById<NavigationView>(R.id.nav_view)?.apply {
//                updatePadding(top = top)
//            }
                insets
            }
        }
    }

    override fun setTheme(resId: Int) {
        super.setTheme(resId)

        themeResId = resId
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (newConfig.uiMode != uiMode) {
            uiMode = newConfig.uiMode
            ActivityCompat.recreate(this)
        }
    }

    fun snackbar(@StringRes resId: Int): Snackbar = snackbar("").setText(resId)
    fun snackbar(text: CharSequence): Snackbar = snackbarInternal(text).apply {
        view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).apply {
            maxLines = 10
        }
        // Edge-to-edge on API 35+: lift the snackbar above the gesture pill.
        // An anchored snackbar (MainActivity anchors to the FAB when shown)
        // already clears it, so do not add the margin on top of the anchor.
        if (navigationBarInset > 0 && anchorView == null) {
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                        bottomMargin += navigationBarInset
                    }
                    v.removeOnAttachStateChangeListener(this)
                }

                override fun onViewDetachedFromWindow(v: View) {}
            })
        }
    }

    internal open fun snackbarInternal(text: CharSequence): Snackbar = throw NotImplementedError()

    override fun onSupportNavigateUp(): Boolean {
        if (!super.onSupportNavigateUp()) finish()
        return true
    }

    /**
     * Guard the back gesture when there are unsaved edits. Predictive back routes through
     * [onBackPressedDispatcher] and skips `onBackPressed()` overrides, so register here instead.
     */
    protected fun guardUnsavedChanges(isDirty: () -> Boolean, dialog: () -> DialogFragment) {
        onBackPressedDispatcher.addCallback(this) {
            if (isDirty()) dialog().showAllowingStateLoss(supportFragmentManager) else finish()
        }
    }

}