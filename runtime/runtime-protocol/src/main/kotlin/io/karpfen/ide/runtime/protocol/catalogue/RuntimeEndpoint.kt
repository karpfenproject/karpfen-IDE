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
 * Where a karpfen runtime listens. Its HTTP routes lie below [httpBase] and its WebSocket at [webSocketUrl], on the
 * same host and port.
 *
 * @property secure true for `https` and `wss`, false for `http` and `ws`.
 * @property host the host name or IP address. An IPv6 address is given without its brackets.
 * @property port the TCP port, from 1 to 65535.
 * @throws IllegalArgumentException if [host] is blank or [port] is out of range.
 */
public data class RuntimeEndpoint(public val secure: Boolean, public val host: String, public val port: Int) {

    init {
        require(host.isNotBlank()) { "a runtime endpoint needs a host" }
        require(port in PORTS) { "the port of a runtime endpoint must be in $PORTS, not $port" }
    }

    /** The host and port as a URL writes them. An IPv6 address goes in brackets, and any other host stays as it is. */
    private val authority: String
        get() = if (':' in host) "[$host]:$port" else "$host:$port"

    /** The base of every HTTP route, such as `http://127.0.0.1:8080`, without a trailing slash. */
    public val httpBase: String
        get() = "${if (secure) HTTPS else HTTP}://$authority"

    /** The URL of the runtime's WebSocket, such as `ws://127.0.0.1:8080/ws`. A secure endpoint uses `wss`. */
    public val webSocketUrl: String
        get() = "${if (secure) WSS else WS}://$authority${WireNames.WEB_SOCKET_PATH}"

    public companion object {
        /** The scheme of a plain HTTP endpoint. */
        private const val HTTP = "http"

        /** The scheme of a secure HTTP endpoint. */
        private const val HTTPS = "https"

        /** The scheme of a plain WebSocket. */
        private const val WS = "ws"

        /** The scheme of a secure WebSocket. */
        private const val WSS = "wss"

        /** What follows the scheme in a URL. */
        private const val SCHEME_END = "://"

        /** The valid TCP ports. */
        private val PORTS = 1..65_535

        /** A host name or an IPv4 address: letters, digits, dots and hyphens. */
        private val HOST_NAME = Regex("[A-Za-z0-9.-]+")

        /** An IPv6 address inside its brackets: hexadecimal digits, colons and dots. */
        private val IPV6_ADDRESS = Regex("[0-9A-Fa-f:.]+")

        /**
         * Parses [url] into an endpoint. The URL has the scheme `http` or `https`, a host and an optional port.
         * Without a port, the default is 80 for `http` and 443 for `https`. The URL has no path other than `/`, no
         * query, no fragment and no user information. Examples are `http://127.0.0.1:8080` and
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
