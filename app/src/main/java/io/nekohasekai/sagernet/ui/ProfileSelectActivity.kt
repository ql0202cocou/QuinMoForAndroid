package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity

class ProfileSelectActivity : ThemedActivity(R.layout.layout_empty),
    ConfigurationFragment.SelectCallback {

    companion object {
        const val EXTRA_SELECTED = "selected"
        const val EXTRA_PROFILE_ID = "id"
        const val EXTRA_NO_CHAIN = "noChain"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selected = intent.getParcelableExtra<ProxyEntity>(EXTRA_SELECTED)
        val noChain = intent.getBooleanExtra(EXTRA_NO_CHAIN, false)

        supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_holder,
                ConfigurationFragment(true, selected, R.string.select_profile, noChain)
            )
            .commitAllowingStateLoss()
    }

    override fun returnProfile(profileId: Long) {
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_PROFILE_ID, profileId)
        })
        finish()
    }

}