package com.reweb.browser

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reweb.browser.browser.BrowserActivity
import com.reweb.browser.diagnostics.WebViewInfo
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device checks that cannot be made on the JVM: that the activity inflates
 * against a real system WebView, and that the WebView the device actually ships
 * can be interrogated. Run with `./gradlew connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class BrowserActivityTest {

    @Test
    fun browserScreenShowsItsChrome() {
        ActivityScenario.launch(BrowserActivity::class.java).use {
            onView(withId(R.id.urlBar)).check(matches(isDisplayed()))
            onView(withId(R.id.backButton)).check(matches(isDisplayed()))
            onView(withId(R.id.tabsButton)).check(matches(isDisplayed()))
            onView(withId(R.id.menuButton)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun homeScreenIsShownOnFirstLaunch() {
        ActivityScenario.launch(BrowserActivity::class.java).use {
            onView(withId(R.id.homeTitle)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun webViewIsPresentAndReportsAUserAgent() {
        // If this fails the device has no usable WebView, which ReWeb reports as a
        // platform limitation rather than an app failure.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = WebViewInfo.read(context)
        assertNotNull(info)
        assertTrue("Expected a non-empty user agent", info.userAgent.isNotBlank())
        assertTrue(
            "Expected the UA to identify a Chromium build",
            info.chromiumMajorVersion != null
        )
    }
}
