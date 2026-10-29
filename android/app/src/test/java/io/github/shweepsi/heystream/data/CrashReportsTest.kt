package io.github.shweepsi.heystream.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CrashReportsTest {
    @Test
    fun urlsAndTheirCredentialsAreRemoved() {
        assertEquals(
            // Tout ce qui suit le schéma est retiré, parenthèse comprise.
            "Response code: 404 (<url>",
            CrashReports.scrub("Response code: 404 (http://panel.example:8080/live/alice/s3cret/123.ts)"),
        )
        assertEquals("load <url> failed", CrashReports.scrub("load https://cdn.example/a.m3u8?token=x failed"))
    }

    @Test
    fun hostsAndAddressesAreRemoved() {
        assertEquals(
            "Unable to resolve host <host>: No address associated with hostname",
            CrashReports.scrub("Unable to resolve host \"panel.example\": No address associated with hostname"),
        )
        assertEquals(
            "failed to connect to <host>/<ip> (port 80) after 10000ms",
            CrashReports.scrub("failed to connect to panel.example/203.0.113.7 (port 80) after 10000ms"),
        )
        assertEquals("Hostname <host> not verified", CrashReports.scrub("Hostname panel.example not verified"))
        assertEquals("connect <ip> refused", CrashReports.scrub("connect 198.51.100.2:443 refused"))
    }

    @Test
    fun ordinaryMessagesStay() {
        val messages = listOf(
            "java.lang.String cannot be cast to java.lang.Integer",
            "Attempt to invoke virtual method 'int java.lang.String.length()' on a null object reference",
            "index 3 out of bounds for length 2",
        )
        messages.forEach { assertEquals(it, CrashReports.scrub(it)) }
    }
}
