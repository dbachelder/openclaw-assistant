package com.openclaw.assistant.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkUtilsTest {

    @Test
    fun testIsUrlSecure_PublicHttps() {
        assertTrue(NetworkUtils.isUrlSecure("https://example.com"))
        assertTrue(NetworkUtils.isUrlSecure("https://sub.domain.com/path"))
    }

    @Test
    fun testIsUrlSecure_PublicHttp() {
        assertFalse(NetworkUtils.isUrlSecure("http://example.com"))
        assertFalse(NetworkUtils.isUrlSecure("http://1.1.1.1"))
    }

    @Test
    fun testIsUrlSecure_Localhost() {
        assertTrue(NetworkUtils.isUrlSecure("http://localhost"))
        assertTrue(NetworkUtils.isUrlSecure("http://localhost:8080"))
    }

    @Test
    fun testIsUrlSecure_Loopback() {
        assertTrue(NetworkUtils.isUrlSecure("http://127.0.0.1"))
        assertTrue(NetworkUtils.isUrlSecure("http://127.0.1.1"))
    }

    @Test
    fun testIsUrlSecure_PrivateClassA() {
        assertTrue(NetworkUtils.isUrlSecure("http://10.0.0.1"))
        assertTrue(NetworkUtils.isUrlSecure("http://10.255.255.255"))
    }

    @Test
    fun testIsUrlSecure_PrivateClassB() {
        assertTrue(NetworkUtils.isUrlSecure("http://172.16.0.1"))
        assertTrue(NetworkUtils.isUrlSecure("http://172.31.255.255"))
        // Outside range
        assertFalse(NetworkUtils.isUrlSecure("http://172.15.255.255"))
        assertFalse(NetworkUtils.isUrlSecure("http://172.32.0.1"))
    }

    @Test
    fun testIsUrlSecure_PrivateClassC() {
        assertTrue(NetworkUtils.isUrlSecure("http://192.168.0.1"))
        assertTrue(NetworkUtils.isUrlSecure("http://192.168.1.100"))
        // Outside range (though technically 192.169 is public)
        assertFalse(NetworkUtils.isUrlSecure("http://192.169.0.1"))
    }

    @Test
    fun testIsUrlSecure_Invalid() {
        assertFalse(NetworkUtils.isUrlSecure(""))
        assertFalse(NetworkUtils.isUrlSecure("not a url"))
        assertFalse(NetworkUtils.isUrlSecure("ftp://example.com"))
    }
}
