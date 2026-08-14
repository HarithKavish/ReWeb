package com.reweb.browser.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.reweb.browser.browser.BrowserActivity

/**
 * Catches `reweb://auth` redirects at the end of an OAuth flow that was handed
 * off to an external browser, and returns control to the browser activity.
 *
 * The redirect URL carries the authorization code or token in its query or
 * fragment. It is passed straight through to [BrowserActivity] and is never
 * logged, stored in history, or written anywhere else.
 */
class AuthRedirectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val redirect = intent?.data
        val forward = Intent(this, BrowserActivity::class.java).apply {
            action = BrowserActivity.ACTION_AUTH_REDIRECT
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (redirect != null) putExtra(BrowserActivity.EXTRA_AUTH_REDIRECT, redirect.toString())
        }
        startActivity(forward)
        finish()
    }
}
