package com.example.source.js

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MyComicBrowserReaderTest {
    @Test fun routeOnlyAllowsSameOriginPublicHtmlReads() {
        assertTrue(MyComicBrowserReader.accepts("mycomic", "GET", "https://mycomic.com/cn/comics?page=2", false))
        for (url in listOf("http://mycomic.com/cn", "https://mycomic.com.evil.invalid/cn", "https://evil.invalid/", "https://user:password@mycomic.com/", "https://mycomic.com:8443/")) {
            assertFalse(url, MyComicBrowserReader.accepts("mycomic", "GET", url, false))
        }
        assertFalse(MyComicBrowserReader.accepts("mycomic", "POST", "https://mycomic.com/cn", false))
        assertFalse(MyComicBrowserReader.accepts("mycomic", "GET", "https://mycomic.com/cn", true))
        assertFalse(MyComicBrowserReader.accepts("another-source", "GET", "https://mycomic.com/cn", false))
    }

    @Test fun challengeAndHttpErrorsNeverBecomeSuccessfulCatalogueResponses() {
        val url = "https://mycomic.com/cn/comics"
        val challenge = JSONObject(MyComicBrowserReader.response(url, "Just a moment...", "<html>__cf_chl_</html>", 200))
        assertEquals(403, challenge.getInt("status"))
        assertEquals("challenge", challenge.getJSONObject("headers").getString("cf-mitigated"))
        assertEquals(503, JSONObject(MyComicBrowserReader.response(url, "Unavailable", "<html>MYCOMIC error</html>", 503)).getInt("status"))
        assertEquals(502, JSONObject(MyComicBrowserReader.response(url, "", "", 200)).getInt("status"))
        assertEquals(502, JSONObject(MyComicBrowserReader.response(url, "Advertisement", "<html>unrelated</html>", 200)).getInt("status"))
    }

    @Test fun publicHtmlRoundTripsQuotedTitlesAndChapterAttributes() {
        val html = "<html><title>MYCOMIC \"作品\"</title><img class=\"page\" data-src=\"https://biccam.com/chapters/1.jpg\"></html>"
        val response = JSONObject(MyComicBrowserReader.response("https://mycomic.com/cn/comics/1/1", "MYCOMIC", html, 200))
        assertEquals(200, response.getInt("status"))
        assertEquals(html, response.getString("body"))
        assertFalse(response.getJSONObject("headers").has("cf-mitigated"))
    }
}
