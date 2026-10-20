package com.example.source.js

import com.example.source.SourceException
import org.junit.Assert.*
import org.junit.Test

class JsAccessErrorTest {
    @Test fun legalRestrictionHasActionableMessageAndRetainsNetworkClassification() {
        val raw = "Error: Invalid status code: 451\n at Network.get"
        val failure = jsSourceFailure(raw)
        assertTrue(failure is SourceException.NetworkError)
        assertTrue(failure.message.orEmpty().contains("HTTP 451"))
        assertTrue(failure.message.orEmpty().contains("其他可用书源"))
        assertFalse(failure.message.orEmpty().contains("Network.get"))
    }
}
