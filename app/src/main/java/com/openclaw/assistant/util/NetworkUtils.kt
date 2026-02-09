package com.openclaw.assistant.util

import java.net.URI

object NetworkUtils {
    /**
     * Checks if the URL is secure (HTTPS) or a local/private address.
     * Enforces HTTPS for public networks.
     */
    fun isUrlSecure(url: String): Boolean {
        return try {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase() ?: return false

            if (scheme == "https") {
                return true
            }

            if (scheme == "http") {
                val host = uri.host ?: return false

                // Localhost
                if (host.equals("localhost", ignoreCase = true)) return true

                // Check if it's an IP address
                if (host.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"))) {
                    val parts = host.split(".")
                    if (parts.size != 4) return false

                    val b1 = parts[0].toIntOrNull() ?: return false
                    val b2 = parts[1].toIntOrNull() ?: return false
                    // b3 and b4 don't need checking for range here as we just check prefix

                    // 127.x.x.x (Loopback)
                    if (b1 == 127) return true

                    // 10.x.x.x (Private Class A)
                    if (b1 == 10) return true

                    // 192.168.x.x (Private Class C)
                    if (b1 == 192 && b2 == 168) return true

                    // 172.16.x.x - 172.31.x.x (Private Class B)
                    if (b1 == 172 && b2 in 16..31) return true
                }
            }

            false
        } catch (e: Exception) {
            false
        }
    }
}
