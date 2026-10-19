package com.example.source.zlibrary

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.library.ZLibraryNodeConfig
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ZLibraryDomainResolverTest {
    private lateinit var endpointProvider: ZLibraryEndpointProvider
    private var previousSelection = ""
    private val probed = mutableListOf<String>()

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        previousSelection = ZLibraryNodeConfig.domain
        ZLibraryNodeConfig.domain = ""
        val credentials = ZLibraryCredentialStorage(context.getSharedPreferences("credential-test", Context.MODE_PRIVATE))
        endpointProvider = ZLibraryEndpointProvider(context, credentials)
        endpointProvider.invalidateCache()
        endpointProvider.setCustomEndpoint(null)
        // Exercise the production quick-probe and precedence logic with actual
        // structured Z-Library responses, without depending on example.com's live
        // HTML/network or treating any HTTP 200 as a healthy library endpoint.
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            probed += chain.request().url.host
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture")
                .body("<script src='/js/zlibrary.js'></script>".toResponseBody()).build()
        }.build()
        ZLibraryEndpointProvider::class.java.getDeclaredField("quickCheckClient").apply {
            isAccessible = true
            set(endpointProvider, client)
        }
    }

    @After fun restoreSelection() { ZLibraryNodeConfig.domain = previousSelection }

    @Test fun validCachedEndpointIsProbedAndReused() = runBlocking {
        endpointProvider.saveCache("cached-zlib.example")
        assertEquals("cached-zlib.example", endpointProvider.getEndpoint())
        assertEquals(listOf("cached-zlib.example"), probed)
    }

    @Test fun healthyCustomEndpointTakesPrecedenceOverCache() = runBlocking {
        endpointProvider.saveCache("cached-zlib.example")
        endpointProvider.setCustomEndpoint("custom-zlib.example")
        assertEquals("custom-zlib.example", endpointProvider.getEndpoint())
        assertEquals(listOf("custom-zlib.example"), probed)
    }

    @Test fun explicitSelectionTakesPrecedenceOverCustomAndCache() = runBlocking {
        endpointProvider.saveCache("cached-zlib.example")
        endpointProvider.setCustomEndpoint("custom-zlib.example")
        ZLibraryNodeConfig.domain = "selected-zlib.example"
        assertEquals("selected-zlib.example", endpointProvider.getEndpoint())
        assertEquals(listOf("selected-zlib.example"), probed)
    }
}
