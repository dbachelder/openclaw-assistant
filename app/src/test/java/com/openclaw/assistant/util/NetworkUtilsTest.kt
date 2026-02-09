package com.openclaw.assistant.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkUtilsTest {

    @Test
    fun isUrlSecure_https_returnsTrue() {
        assertTrue(NetworkUtils.isUrlSecure("https://example.com"))
        assertTrue(NetworkUtils.isUrlSecure("https://1.1.1.1"))
    }

    @Test
    fun isUrlSecure_httpPublic_returnsFalse() {
        assertFalse(NetworkUtils.isUrlSecure("http://example.com"))
        assertFalse(NetworkUtils.isUrlSecure("http://8.8.8.8"))
    }

    @Test
    fun isUrlSecure_localhost_returnsTrue() {
        assertTrue(NetworkUtils.isUrlSecure("http://localhost"))
        assertTrue(NetworkUtils.isUrlSecure("http://localhost:8080"))
    }

    @Test
    fun isUrlSecure_loopbackIp_returnsTrue() {
        assertTrue(NetworkUtils.isUrlSecure("http://127.0.0.1"))
        assertTrue(NetworkUtils.isUrlSecure("http://127.0.0.1:3000"))
    }

    @Test
    fun isUrlSecure_emulatorIp_returnsTrue() {
        assertTrue(NetworkUtils.isUrlSecure("http://10.0.2.2"))
        assertTrue(NetworkUtils.isUrlSecure("http://10.0.2.2:5000"))
    }

    @Test
    fun isUrlSecure_privateIpClassA_returnsTrue() {
        assertTrue(NetworkUtils.isUrlSecure("http://10.0.0.1"))
        assertTrue(NetworkUtils.isUrlSecure("http://10.255.255.255"))
    }

    @Test
    fun isUrlSecure_privateIpClassB_returnsTrue() {
        assertTrue(NetworkUtils.isUrlSecure("http://172.16.0.1"))
        assertTrue(NetworkUtils.isUrlSecure("http://172.31.255.255"))
    }

    @Test
    fun isUrlSecure_privateIpClassB_outOfRange_returnsFalse() {
        assertFalse(NetworkUtils.isUrlSecure("http://172.15.0.1")) // Public
        assertFalse(NetworkUtils.isUrlSecure("http://172.32.0.1")) // Public
    }

    @Test
    fun isUrlSecure_privateIpClassC_returnsTrue() {
        assertTrue(NetworkUtils.isUrlSecure("http://192.168.0.1"))
        assertTrue(NetworkUtils.isUrlSecure("http://192.168.1.1"))
        assertTrue(NetworkUtils.isUrlSecure("http://192.168.255.255"))
    }

    @Test
    fun isUrlSecure_invalidUrl_returnsFalse() {
        assertFalse(NetworkUtils.isUrlSecure("not a url"))
        assertFalse(NetworkUtils.isUrlSecure("ftp://example.com"))
        assertFalse(NetworkUtils.isUrlSecure(""))
    }
}
