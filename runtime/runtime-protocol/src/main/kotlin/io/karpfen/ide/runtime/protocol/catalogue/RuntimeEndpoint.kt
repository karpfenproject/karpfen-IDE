/**
 * Copyright 2026 Karl Kegel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.karpfen.ide.runtime.protocol.catalogue

/**
 * Where a karpfen runtime listens: its HTTP routes below [httpBase] and its WebSocket at [webSocketUrl], on the same
 * host and port.
 *
 * @property secure true for `https` and `wss`, false for `http` and `ws`.
 * @property host the host name or IP address; an IPv6 address without its brackets.
 * @property port the TCP port, from 1 to 65535.
 * @throws IllegalArgumentException if [host] is blank or [port] is out of range.
 */
public data class RuntimeEndpoint(public val secure: Boolean, public val host: String, public val port: Int) {

    init {
        require(host.isNotBlank()) { "a runtime endpoint needs a host" }
        require(port in PORTS) { "the port of a runtime endpoint must be in $PORTS, not $port" }
    }

    /** The host as a URL writes it: an IPv6 address in brackets, any other host as it is. */
    private val authority: String
        get() = if (':' in host) "[$host]:$port" else "$host:$port"

    /** The base of every HTTP route, such as `http://127.0.0.1:8080`, without a trailing slash. */
    public val httpBase: String
        get() = "${if (secure) HTTPS else HTTP}://$authority"

    /** The URL of the runtime's WebSocket, such as `ws://127.0.0.1:8080/ws` (`wss` for a secure endpoint). */
    public val webSocketUrl: String
        get() = "${if (secure) WSS else WS}://$authority${WireNames.WEB_SOCKET_PATH}"

    public companion object {
        private const val HTTP = "http"
        private const val HTTPS = "https"
        private const val WS = "ws"
        private const val WSS = "wss"
        private const val SCHEME_END = "://"
        private val PORTS = 1..65_535

        /** A host name or an IPv4 address: letters, digits, dots and hyphens. */
        private val HOST_NAME = Regex("[A-Za-z0-9.-]+")

        /** An IPv6 address inside its brackets: hexadecimal digits, colons and dots. */
        private val IPV6_ADDRESS = Regex("[0-9A-Fa-f:.]+")

        /**
         * The endpoint of [url]: `http` or `https`, a host, an optional port (80 or 443 when absent) and no path other
         * than `/`, no query, no fragment and no user information, such as `http://127.0.0.1:8080` or
         * `https://runtime.example.org/`.
         *
         * @throws IllegalArgumentException if [url] is not such a URL.
         */
        public fun parse(url: String): RuntimeEndpoint {
            val schemeEnd = url.indexOf(SCHEME_END)
            require(schemeEnd > 0) { "not a runtime URL (expected http://<host>:<port>): '$url'" }
            val secure = when (url.substring(0, schemeEnd).lowercase()) {
                HTTP -> false
                HTTPS -> true
                else -> throw IllegalArgumentException("a runtime URL starts with http:// or https://: '$url'")
            }
            val rest = url.substring(schemeEnd + SCHEME_END.length)
            val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) rest.length else it }
            val path = rest.substring(authorityEnd)
            require(path.isEmpty() || path == "/") { "a runtime URL has no path, query or fragment: '$url'" }
            val (host, port) = hostAndPort(rest.substring(0, authorityEnd), url)
            return RuntimeEndpoint(secure, host, port ?: if (secure) DEFAULT_HTTPS_PORT else DEFAULT_HTTP_PORT)
        }

        /** The default port of `http`. */
        private const val DEFAULT_HTTP_PORT = 80

        /** The default port of `https`. */
        private const val DEFAULT_HTTPS_PORT = 443

        /** The host and the port, if one is given, of the [authority] of [url]. */
        private fun hostAndPort(authority: String, url: String): Pair<String, Int?> {
            val bracketed = authority.startsWith("[")
            val hostEnd = when {
                bracketed -> authority.indexOf(']') + 1
                ':' in authority -> authority.indexOf(':')
                else -> authority.length
            }
            require(!bracketed || hostEnd > 0) { "an IPv6 host of a runtime URL needs its closing bracket: '$url'" }
            val host = if (bracketed) authority.substring(1, hostEnd - 1) else authority.substring(0, hostEnd)
            require((if (bracketed) IPV6_ADDRESS else HOST_NAME).matches(host)) { "not a host of a runtime URL: '$host' in '$url'" }
            val portText = authority.substring(hostEnd)
            if (portText.isEmpty()) return host to null
            val digits = portText.removePrefix(":")
            val port = digits.takeIf { portText.startsWith(":") && it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toIntOrNull()
            require(port != null && port in PORTS) { "not a port of a runtime URL: '$digits' in '$url'" }
            return host to port
        }
    }
}
