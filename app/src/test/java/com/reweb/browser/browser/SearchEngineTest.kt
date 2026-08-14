package com.reweb.browser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SearchEngineTest {

    @Test
    fun `default engine is Google`() {
        assertEquals(SearchEngine.GOOGLE, SearchEngine.DEFAULT)
    }

    @Test
    fun `query is percent-encoded with literal spaces escaped`() {
        // A raw "+" would be read as a plus sign by some engines, so spaces must
        // become %20 rather than "+".
        assertEquals(
            "https://www.google.com/search?q=fish%20%26%20chips",
            SearchEngine.GOOGLE.buildSearchUrl("fish & chips")
        )
    }

    @Test
    fun `special characters survive encoding`() {
        val url = SearchEngine.DUCKDUCKGO.buildSearchUrl("c++ \"exact\" #tag")
        assertEquals("https://duckduckgo.com/?q=c%2B%2B%20%22exact%22%20%23tag", url)
    }

    @Test
    fun `byId resolves built-in engines and rejects unknown ones`() {
        assertEquals(SearchEngine.BING, SearchEngine.byId("bing"))
        assertNull(SearchEngine.byId("nope"))
        assertNull(SearchEngine.byId(null))
    }

    @Test
    fun `custom engine requires a placeholder and a web scheme`() {
        assertNotNull(SearchEngine.custom("https://search.example/?q=%s"))
        // No placeholder: the query would be silently dropped.
        assertNull(SearchEngine.custom("https://search.example/"))
        // Non-web scheme: would hand arbitrary input to another app.
        assertNull(SearchEngine.custom("intent://search?q=%s"))
        assertNull(SearchEngine.custom(""))
    }

    @Test
    fun `custom engine builds a usable URL`() {
        val engine = SearchEngine.custom("https://search.example/?q=%s&lang=en")
        assertEquals(
            "https://search.example/?q=a%20b&lang=en",
            engine?.buildSearchUrl("a b")
        )
    }
}
