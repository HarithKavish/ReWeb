package com.reweb.browser.browser

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import com.reweb.browser.engine.BrowserEngine

/**
 * Owns the tab list and decides which tabs are allowed to hold a live engine.
 *
 * ## The memory strategy
 *
 * A WebView is expensive — tens of megabytes of renderer, and on legacy devices
 * every one of them is in this process. Keeping one per tab is how a browser
 * becomes unusable on a 1 GB phone, so ReWeb caps the number of live engines by
 * device class and suspends the rest.
 *
 * Suspending a tab means: save its navigation history, destroy its engine,
 * keep its URL/title/favicon. Reactivating restores the saved history, so the
 * back/forward stack survives; if the platform failed to produce a state bundle,
 * the tab falls back to reloading its last URL. Either way the tab never
 * silently disappears.
 *
 * The active tab is never suspended.
 */
class TabManager(
    context: Context,
    /**
     * Builds an engine for [Tab], already wired to its client and configuration.
     * Returning null means the engine could not be created — a real outcome when
     * the system WebView package is missing or mid-update.
     */
    private val engineFactory: (Tab) -> BrowserEngine?
) {

    interface Listener {
        fun onTabListChanged() {}
        fun onActiveTabChanged(tab: Tab?) {}
        /** An engine was created or destroyed for [tab]; the UI must re-attach views. */
        fun onTabEngineChanged(tab: Tab) {}
    }

    private val appContext = context.applicationContext
    private val tabsInternal = mutableListOf<Tab>()
    private var nextId = 1L
    private var activeTabId: Long? = null
    private val listeners = mutableListOf<Listener>()

    /** How many tabs may hold a live engine at once, chosen from device memory. */
    val maxLiveEngines: Int = computeMaxLiveEngines(appContext)

    val tabs: List<Tab> get() = tabsInternal
    val count: Int get() = tabsInternal.size
    val activeTab: Tab? get() = tabsInternal.firstOrNull { it.id == activeTabId }

    fun addListener(listener: Listener) {
        if (listener !in listeners) listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * Creates a tab. [activate] false is used when restoring a session, so that
     * restoring twenty tabs does not build twenty engines.
     */
    fun createTab(url: String? = null, isPrivate: Boolean = false, activate: Boolean = true): Tab {
        val tab = Tab(id = nextId++, isPrivate = isPrivate, initialUrl = url)
        tabsInternal.add(tab)
        notify { it.onTabListChanged() }
        if (activate) setActiveTab(tab.id)
        return tab
    }

    /**
     * Registers a tab whose engine already exists — used for popups, where the
     * platform hands us a WebView it created and expects us to keep it.
     */
    fun adoptTab(engine: BrowserEngine, isPrivate: Boolean, activate: Boolean): Tab {
        val tab = Tab(id = nextId++, isPrivate = isPrivate, initialUrl = null)
        tab.engine = engine
        tab.isShowingHome = false
        tabsInternal.add(tab)
        enforceEngineBudget(protecting = tab)
        notify { it.onTabListChanged() }
        if (activate) setActiveTab(tab.id)
        return tab
    }

    fun setActiveTab(id: Long): Tab? {
        val tab = tabsInternal.firstOrNull { it.id == id } ?: return null
        if (activeTabId == id && tab.engine != null) return tab

        activeTabId?.let { previousId ->
            tabsInternal.firstOrNull { it.id == previousId }?.engine?.setActive(false)
        }

        activeTabId = id
        tab.lastActiveAt = System.currentTimeMillis()
        ensureEngine(tab)
        tab.engine?.setActive(true)
        notify { it.onActiveTabChanged(tab) }
        return tab
    }

    /**
     * Guarantees [tab] has a live engine, restoring its saved navigation state or
     * reloading its last URL. Returns null only if the engine could not be built.
     */
    fun ensureEngine(tab: Tab): BrowserEngine? {
        tab.engine?.let { return it }

        val engine = try {
            engineFactory(tab)
        } catch (_: Exception) {
            // WebView can genuinely fail to initialise: a missing or mid-update
            // system WebView package throws here. The caller reports it rather
            // than the app dying.
            null
        } ?: return null
        tab.engine = engine
        enforceEngineBudget(protecting = tab)

        val restored = tab.savedState?.let { engine.restoreState(it) } ?: false
        if (!restored) {
            // Either the tab was never live, or the platform declined to produce a
            // state bundle. Falling back to the URL loses forward history but keeps
            // the tab usable, which is the important part.
            tab.url?.takeIf { it.isNotBlank() }?.let { engine.loadUrl(it) }
        }
        tab.savedState = null
        notify { it.onTabEngineChanged(tab) }
        return engine
    }

    /**
     * Destroys [tab]'s engine after saving its state. The tab itself remains in
     * the list. No-op for the active tab.
     */
    fun suspendTab(tab: Tab): Boolean {
        if (tab.id == activeTabId) return false
        val engine = tab.engine ?: return false

        tab.url = engine.currentUrl ?: tab.url
        tab.title = engine.title ?: tab.title
        tab.canGoBack = engine.canGoBack()
        tab.canGoForward = engine.canGoForward()

        val state = android.os.Bundle()
        tab.savedState = if (engine.saveState(state)) state else null

        engine.destroy()
        tab.engine = null
        notify { it.onTabEngineChanged(tab) }
        return true
    }

    fun closeTab(id: Long): Boolean {
        val index = tabsInternal.indexOfFirst { it.id == id }
        if (index < 0) return false
        val tab = tabsInternal.removeAt(index)
        tab.engine?.destroy()
        tab.engine = null
        tab.savedState = null

        if (activeTabId == id) {
            // Prefer the tab to the left, matching what every browser does.
            val next = tabsInternal.getOrNull(index - 1) ?: tabsInternal.getOrNull(index)
            activeTabId = next?.id
            if (next != null) {
                ensureEngine(next)
                next.engine?.setActive(true)
            }
            notify { it.onActiveTabChanged(next) }
        }
        notify { it.onTabListChanged() }
        return true
    }

    fun closeAllPrivateTabs(): Int {
        val privateIds = tabsInternal.filter { it.isPrivate }.map { it.id }
        privateIds.forEach { closeTab(it) }
        return privateIds.size
    }

    fun closeAll() {
        tabsInternal.toList().forEach { tab ->
            tab.engine?.destroy()
            tab.engine = null
            tab.savedState = null
        }
        tabsInternal.clear()
        activeTabId = null
        notify { it.onTabListChanged() }
        notify { it.onActiveTabChanged(null) }
    }

    fun hasPrivateTabs(): Boolean = tabsInternal.any { it.isPrivate }

    /**
     * Responds to system memory pressure.
     *
     * Below TRIM_MEMORY_BACKGROUND the app is still visible, so only inactive
     * engines are released. At or above it the process is a kill candidate and the
     * active engine's caches are released too.
     */
    fun onTrimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                suspendAllInactive()
                activeTab?.engine?.trimMemory()
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                suspendAllInactive()
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                // Shed only the least recently used engine; the user is still
                // interacting and may switch tabs at any moment.
                oldestLiveInactiveTab()?.let { suspendTab(it) }
            }
        }
    }

    fun suspendAllInactive(): Int =
        tabsInternal.filter { it.id != activeTabId && it.engine != null }
            .count { suspendTab(it) }

    /** URLs of all non-private tabs, for session restore on next launch. */
    fun sessionUrls(): List<String> = tabsInternal
        .filterNot { it.isPrivate }
        .mapNotNull { tab -> (tab.engine?.currentUrl ?: tab.url)?.takeIf { HistoryUrls.isRestorable(it) } }

    fun onActivityPause() {
        tabsInternal.forEach { it.engine?.onActivityPause() }
    }

    fun onActivityResume() {
        activeTab?.engine?.onActivityResume()
    }

    /** Keeps live engines within [maxLiveEngines], never touching [protecting]. */
    private fun enforceEngineBudget(protecting: Tab) {
        while (liveEngineCount() > maxLiveEngines) {
            val victim = tabsInternal
                .filter { it.id != protecting.id && it.id != activeTabId && it.engine != null }
                .minByOrNull { it.lastActiveAt }
                ?: break
            suspendTab(victim)
        }
    }

    private fun liveEngineCount(): Int = tabsInternal.count { it.engine != null }

    private fun oldestLiveInactiveTab(): Tab? = tabsInternal
        .filter { it.id != activeTabId && it.engine != null }
        .minByOrNull { it.lastActiveAt }

    private inline fun notify(action: (Listener) -> Unit) {
        // Copy first: a listener may add or remove tabs while responding.
        listeners.toList().forEach(action)
    }

    companion object {
        /**
         * Derives the live-engine budget from the heap the system is willing to
         * give this process. These numbers are conservative by design: an
         * over-eager cache is the difference between a slow browser and one the
         * system kills mid-page.
         */
        fun computeMaxLiveEngines(context: Context): Int {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return 2
            if (activityManager.isLowRamDevice) return 1
            return when (activityManager.memoryClass) {
                in 0..47 -> 1
                in 48..95 -> 2
                in 96..191 -> 3
                else -> 4
            }
        }
    }
}

/** Shared predicate for URLs worth persisting across launches. */
internal object HistoryUrls {
    fun isRestorable(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }
}
