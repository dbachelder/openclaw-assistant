package com.openclaw.assistant.util

import java.net.URI

object NetworkUtils {
    /**
     * Checks if the given URL is secure.
     * A URL is considered secure if it uses HTTPS or if it points to a local network address.
     */
    fun isUrlSecure(url: String): Boolean {
        if (url.isBlank()) return false

        try {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase() ?: return false

            if (scheme == "https") return true
            if (scheme == "http") {
                val host = uri.host ?: return false
                return isLocalOrPrivate(host)
            }
        } catch (e: Exception) {
            return false
        }

        return false
    }

    private fun isLocalOrPrivate(host: String): Boolean {
        // Localhost
        if (host == "localhost") return true

        // Check for IPv4 private ranges
        // 127.x.x.x (Loopback)
        if (host.startsWith("127.")) return true

        // 10.x.x.x (Private Class A)
        if (host.startsWith("10.")) return true

        // 192.168.x.x (Private Class C)
        if (host.startsWith("192.168.")) return true

        // 172.16.x.x - 172.31.x.x (Private Class B)
        if (host.startsWith("172.")) {
            val parts = host.split(".")
            if (parts.size == 4) {
                val second = parts[1].toIntOrNull()
                if (second != null && second in 16..31) return true
            }
        }

        return false
    }
}
