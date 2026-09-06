package com.opencall.relay.dialer.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.opencall.relay.MainActivity
import com.opencall.relay.shell.AppTab

/**
 * PART 3.1/1: the manifest's `ACTION_DIAL`/`tel:` entry point — required to
 * stay a real Activity for default-dialer role eligibility — but no longer
 * the dialer's own screen. Tab 2 (Phone) is now a top-level pillar hosted
 * directly inside [MainActivity] (see [PhoneTabController], extracted from
 * what used to be this Activity's own content). This Activity's only job
 * now is: land here, forward straight into MainActivity's Phone tab with
 * whatever number the system/another app supplied, and get out of the way.
 */
class DialerHostActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forwardToPhoneTab(intent)
        finish()
    }

    private fun forwardToPhoneTab(intent: Intent) {
        val number = intent.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra(MainActivity.EXTRA_SELECT_TAB, AppTab.PHONE.name)
            if (number != null) putExtra(MainActivity.EXTRA_PREFILL_NUMBER, number)
        })
    }
}
