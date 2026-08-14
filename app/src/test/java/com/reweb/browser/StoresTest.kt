package com.reweb.browser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.reweb.browser.bookmarks.BookmarkStore
import com.reweb.browser.browser.SearchEngine
import com.reweb.browser.browser.UserAgentMode
import com.reweb.browser.downloads.DownloadStatus
import com.reweb.browser.downloads.DownloadStore
import com.reweb.browser.history.HistoryStore
import com.reweb.browser.settings.Settings
import com.reweb.browser.settings.SiteSettingsStore
import com.reweb.browser.webapp.WebAppStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Persistence behaviour, exercised against the real SQLite and SharedPreferences
 * implementations Robolectric provides rather than against mocks — the bugs
 * worth catching here are in the SQL and the file handling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StoresTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric gives each test a fresh app data directory, but these stores
        // are cheap to reset explicitly and it makes ordering irrelevant.
        HistoryStore(context).clear()
        BookmarkStore(context).clear()
        DownloadStore(context).clear()
        WebAppStore(context).clear()
        SiteSettingsStore(context).clear()
    }

    // --- History ---

    @Test
    fun `history records visits newest first`() {
        val store = HistoryStore(context)
        store.recordVisit("https://a.example", "A")
        store.recordVisit("https://b.example", "B")

        val recent = store.recent()
        assertEquals(2, recent.size)
        assertEquals("https://b.example", recent[0].url)
        assertEquals("https://a.example", recent[1].url)
    }

    @Test
    fun `revisiting the newest entry updates it instead of duplicating`() {
        // Otherwise a reload or a single-page app floods the list.
        val store = HistoryStore(context)
        store.recordVisit("https://a.example", "A")
        store.recordVisit("https://a.example", "A again")

        assertEquals(1, store.count())
        assertEquals("A again", store.recent().first().title)
    }

    @Test
    fun `revisiting an older entry does create a new row`() {
        val store = HistoryStore(context)
        store.recordVisit("https://a.example", "A")
        store.recordVisit("https://b.example", "B")
        store.recordVisit("https://a.example", "A")
        assertEquals(3, store.count())
    }

    @Test
    fun `visits within the same millisecond keep a stable order`() {
        // Regression: "is this the most recent entry?" used to order by timestamp
        // alone. Rapid visits share a millisecond, the tie resolved arbitrarily,
        // and a visit could be collapsed into the wrong row and lost. Slow devices
        // hid this; a fast machine reproduces it immediately.
        val store = HistoryStore(context)
        repeat(30) { index ->
            store.recordVisit("https://a.example", "A$index")
            store.recordVisit("https://b.example", "B$index")
        }
        // Strict alternation means nothing may ever be collapsed.
        assertEquals(60, store.count())

        val recent = store.recent()
        assertEquals("https://b.example", recent[0].url)
        assertEquals("https://a.example", recent[1].url)
    }

    @Test
    fun `rapid identical visits still collapse into one row`() {
        // The other half of the same behaviour: reloading must not flood history,
        // even when every visit lands in the same millisecond.
        val store = HistoryStore(context)
        repeat(30) { store.recordVisit("https://a.example", "A") }
        assertEquals(1, store.count())
    }

    @Test
    fun `in-app and non-navigable URLs never enter history`() {
        val store = HistoryStore(context)
        store.recordVisit("about:blank", "blank")
        store.recordVisit("data:text/html,<h1>error page</h1>", "error")
        store.recordVisit("javascript:alert(1)", "js")
        store.recordVisit("blob:https://example.com/abc", "blob")
        store.recordVisit("", "")
        assertEquals(0, store.count())
    }

    @Test
    fun `shouldRecord is the single gate for what is storable`() {
        assertTrue(HistoryStore.shouldRecord("https://example.com"))
        assertTrue(HistoryStore.shouldRecord("http://example.com"))
        assertFalse(HistoryStore.shouldRecord("about:blank"))
        assertFalse(HistoryStore.shouldRecord("data:text/html,x"))
        assertFalse(HistoryStore.shouldRecord("JavaScript:alert(1)"))
        assertFalse(HistoryStore.shouldRecord(""))
    }

    @Test
    fun `history search matches title and url`() {
        val store = HistoryStore(context)
        store.recordVisit("https://example.com/kotlin", "Kotlin docs")
        store.recordVisit("https://other.example/page", "Something else")

        assertEquals(1, store.search("kotlin").size)
        assertEquals(1, store.search("Something").size)
        assertEquals(0, store.search("nothing here").size)
    }

    @Test
    fun `recentDistinctSites collapses repeat visits`() {
        val store = HistoryStore(context)
        store.recordVisit("https://a.example", "A")
        store.recordVisit("https://b.example", "B")
        store.recordVisit("https://a.example", "A")

        val distinct = store.recentDistinctSites()
        assertEquals(2, distinct.size)
        assertEquals("https://a.example", distinct[0].url)
    }

    @Test
    fun `updateTitle fills in a title reported after the visit`() {
        val store = HistoryStore(context)
        store.recordVisit("https://a.example", null)
        store.updateTitle("https://a.example", "Late title")
        assertEquals("Late title", store.recent().first().title)
    }

    @Test
    fun `clear empties history`() {
        val store = HistoryStore(context)
        store.recordVisit("https://a.example", "A")
        store.clear()
        assertEquals(0, store.count())
    }

    // --- Bookmarks ---

    @Test
    fun `bookmarks round-trip`() {
        val store = BookmarkStore(context)
        val id = store.add("https://example.com", "Example")
        assertTrue(id > 0)
        assertTrue(store.isBookmarked("https://example.com"))
        assertEquals("Example", store.findByUrl("https://example.com")?.title)
    }

    @Test
    fun `bookmarking the same URL twice updates rather than duplicates`() {
        val store = BookmarkStore(context)
        store.add("https://example.com", "First")
        store.add("https://example.com", "Second")
        assertEquals(1, store.all().size)
        assertEquals("Second", store.findByUrl("https://example.com")?.title)
    }

    @Test
    fun `only web pages can be bookmarked`() {
        val store = BookmarkStore(context)
        assertEquals(-1, store.add("about:blank", "Blank"))
        assertEquals(-1, store.add("data:text/html,x", "Data"))
        assertEquals(0, store.all().size)

        assertTrue(BookmarkStore.isBookmarkable("https://example.com"))
        assertTrue(BookmarkStore.isBookmarkable("http://example.com"))
        assertFalse(BookmarkStore.isBookmarkable("javascript:alert(1)"))
        assertFalse(BookmarkStore.isBookmarkable(""))
    }

    @Test
    fun `bookmarks can be edited and removed`() {
        val store = BookmarkStore(context)
        val id = store.add("https://example.com", "Example")
        assertTrue(store.update(id, "Renamed", "https://example.org"))
        assertEquals("Renamed", store.findByUrl("https://example.org")?.title)
        assertTrue(store.remove(id))
        assertFalse(store.isBookmarked("https://example.org"))
    }

    @Test
    fun `removeByUrl works for the toolbar star`() {
        val store = BookmarkStore(context)
        store.add("https://example.com", "Example")
        assertTrue(store.removeByUrl("https://example.com"))
        assertFalse(store.isBookmarked("https://example.com"))
    }

    // --- Web app profiles ---

    @Test
    fun `web app profiles persist across store instances`() {
        val installed = WebAppStore(context).install(
            name = "Example App",
            url = "https://example.com/app",
            icon = null,
            themeColor = null,
            userAgentMode = UserAgentMode.DESKTOP
        )
        assertNotNull(installed)

        // A fresh instance re-reads the JSON document from disk.
        val reloaded = WebAppStore(context).byId(installed!!.id)
        assertNotNull(reloaded)
        assertEquals("Example App", reloaded!!.name)
        assertEquals("https://example.com/app", reloaded.url)
        assertEquals(UserAgentMode.DESKTOP, reloaded.userAgentMode)
    }

    @Test
    fun `only web origins can be installed`() {
        val store = WebAppStore(context)
        assertNull(store.install("Bad", "about:blank", null, null, UserAgentMode.DEFAULT))
        assertNull(store.install("Bad", "javascript:alert(1)", null, null, UserAgentMode.DEFAULT))
        assertEquals(0, store.all().size)
    }

    @Test
    fun `reinstalling the same URL replaces the existing profile`() {
        val store = WebAppStore(context)
        store.install("First", "https://example.com", null, null, UserAgentMode.DEFAULT)
        store.install("Second", "https://example.com", null, null, UserAgentMode.MOBILE)

        assertEquals(1, store.all().size)
        assertEquals("Second", store.all().first().name)
    }

    @Test
    fun `web apps can be updated and removed`() {
        val store = WebAppStore(context)
        val profile = store.install("App", "https://example.com", null, null, UserAgentMode.DEFAULT)!!

        assertTrue(store.update(profile.copy(name = "Renamed")))
        assertEquals("Renamed", WebAppStore(context).byId(profile.id)?.name)

        assertTrue(store.remove(profile.id))
        assertNull(WebAppStore(context).byId(profile.id))
    }

    @Test
    fun `a blank name falls back to the host`() {
        val store = WebAppStore(context)
        val profile = store.install("   ", "https://www.example.com/app", null, null, UserAgentMode.DEFAULT)
        assertEquals("example.com", profile?.name)
    }

    // --- Per-site settings ---

    @Test
    fun `per-site user agent is keyed by host, ignoring www`() {
        val store = SiteSettingsStore(context)
        store.setUserAgentModeForUrl("https://www.example.com/page", UserAgentMode.DESKTOP)

        assertEquals(UserAgentMode.DESKTOP, store.userAgentModeForUrl("https://example.com/other"))
        assertEquals(UserAgentMode.DESKTOP, store.userAgentModeForHost("WWW.EXAMPLE.COM"))
        assertNull(store.userAgentModeForUrl("https://different.example/page"))
    }

    @Test
    fun `removing an override restores the global default`() {
        val store = SiteSettingsStore(context)
        store.setUserAgentMode("example.com", UserAgentMode.DESKTOP)
        assertEquals(1, store.all().size)

        store.remove("example.com")
        assertNull(store.userAgentModeForHost("example.com"))
        assertEquals(0, store.all().size)
    }

    @Test
    fun `host normalisation`() {
        assertEquals("example.com", SiteSettingsStore.normalizeHost(" WWW.Example.com "))
        assertEquals("sub.example.com", SiteSettingsStore.normalizeHost("sub.example.com"))
    }

    // --- Settings ---

    @Test
    fun `settings defaults are the documented ones`() {
        val settings = Settings(context)
        assertTrue(settings.usesNativeHomePage)
        assertEquals(SearchEngine.GOOGLE.id, settings.searchEngineId)
        assertTrue(settings.javaScriptEnabled)
        assertTrue(settings.loadImages)
        assertEquals(UserAgentMode.DEFAULT, settings.defaultUserAgentMode)
        assertEquals(100, settings.textZoomPercent)
        assertFalse(settings.clearOnExit)
        assertTrue(settings.oauthHandoffEnabled)
    }

    @Test
    fun `settings persist and clamp out-of-range values`() {
        val settings = Settings(context)
        settings.textZoomPercent = 1000
        assertEquals(Settings.MAX_TEXT_ZOOM, Settings(context).textZoomPercent)

        settings.textZoomPercent = 1
        assertEquals(Settings.MIN_TEXT_ZOOM, Settings(context).textZoomPercent)
    }

    @Test
    fun `an unusable custom search template falls back to the default engine`() {
        val settings = Settings(context)
        settings.searchEngineId = SearchEngine.CUSTOM_ID
        settings.customSearchTemplate = "not a template"
        assertEquals(SearchEngine.DEFAULT.id, settings.searchEngine().id)

        settings.customSearchTemplate = "https://search.example/?q=%s"
        assertEquals(SearchEngine.CUSTOM_ID, settings.searchEngine().id)
    }

    @Test
    fun `session URLs are capped so a runaway session cannot bloat preferences`() {
        val settings = Settings(context)
        settings.lastSessionUrls = (1..100).map { "https://example.com/$it" }
        assertEquals(Settings.MAX_RESTORED_TABS, Settings(context).lastSessionUrls.size)
    }

    // --- Downloads ---

    @Test
    fun `download records round-trip and update by system id`() {
        val store = DownloadStore(context)
        store.insert(
            systemId = 42L,
            fileName = "report.pdf",
            url = "https://example.com/report.pdf",
            mimeType = "application/pdf",
            totalBytes = 1024
        )
        assertEquals(1, store.all().size)
        assertEquals(DownloadStatus.RUNNING, store.all().first().status)

        store.updateStatus(42L, DownloadStatus.COMPLETE, "file:///storage/report.pdf", 2048)
        val updated = store.all().first()
        assertEquals(DownloadStatus.COMPLETE, updated.status)
        assertEquals(2048, updated.totalBytes)
        assertEquals("file:///storage/report.pdf", updated.localUri)
    }

    @Test
    fun `a locally written download can be inserted already complete`() {
        val store = DownloadStore(context)
        store.insert(
            systemId = -1,
            fileName = "export.csv",
            url = "data:",
            mimeType = "text/csv",
            totalBytes = 10,
            status = DownloadStatus.COMPLETE,
            localUri = "/storage/export.csv"
        )
        assertEquals(DownloadStatus.COMPLETE, store.all().first().status)
    }
}
