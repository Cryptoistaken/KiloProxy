package net.typeblog.socks.util

import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared SOCKS5 proxy connectivity test.
 *
 * Performs the full raw-socket SOCKS5 handshake (method negotiation,
 * optional username/password auth, CONNECT to a test target) so both the
 * proxy card and the add/edit sheet report consistent results.
 */
object SocksTester {

    /**
     * Result of a raw SOCKS5 handshake probe.
     */
    enum class ProxyProbe {
        OK,
        AUTH_FAILED,
        NOT_SOCKS5,
        CONNECT_FAILED,
        UNREACHABLE
    }

    /**
     * Performs the full raw-socket SOCKS5 handshake (method negotiation,
     * optional username/password auth, CONNECT to a test target) and returns
     * a [ProxyProbe] classifying the outcome. Safe to call from any thread.
     */
    fun probeProxy(server: String?, port: Int, username: String?, password: String?): ProxyProbe {
        if (server.isNullOrEmpty()) return ProxyProbe.UNREACHABLE
        val user = username.orEmpty()
        val pass = password.orEmpty()
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(server, port), 5000)
            socket.soTimeout = 5000
            val ins = socket.getInputStream()
            val outs = socket.getOutputStream()

            // SOCKS5 method negotiation
            outs.write(byteArrayOf(0x05, 0x01, 0x02.toByte()))
            outs.flush()
            val authResp = ByteArray(2)
            if (ins.read(authResp) < 2) return ProxyProbe.UNREACHABLE
            if (authResp[0] != 0x05.toByte()) return ProxyProbe.NOT_SOCKS5
            if (authResp[1] == 0xff.toByte()) return ProxyProbe.NOT_SOCKS5

            // Username/password auth (RFC 1929)
            if (authResp[1] == 0x02.toByte()) {
                val uBytes = user.toByteArray()
                val pBytes = pass.toByteArray()
                val authReq = ByteArray(3 + uBytes.size + pBytes.size)
                authReq[0] = 0x01
                authReq[1] = uBytes.size.toByte()
                System.arraycopy(uBytes, 0, authReq, 2, uBytes.size)
                authReq[2 + uBytes.size] = pBytes.size.toByte()
                System.arraycopy(pBytes, 0, authReq, 3 + uBytes.size, pBytes.size)
                outs.write(authReq)
                outs.flush()
                val authResp2 = ByteArray(2)
                if (ins.read(authResp2) < 2) return ProxyProbe.UNREACHABLE
                if (authResp2[1] != 0x00.toByte()) return ProxyProbe.AUTH_FAILED
            }

            // CONNECT to a test target
            val testHost = "google.com"
            val testPort = 80
            val testHostBytes = testHost.toByteArray()
            val connectReq = ByteArray(7 + testHostBytes.size)
            connectReq[0] = 0x05
            connectReq[1] = 0x01
            connectReq[2] = 0x00
            connectReq[3] = 0x03
            connectReq[4] = testHostBytes.size.toByte()
            System.arraycopy(testHostBytes, 0, connectReq, 5, testHostBytes.size)
            connectReq[5 + testHostBytes.size] = (testPort shr 8).toByte()
            connectReq[6 + testHostBytes.size] = testPort.toByte()
            outs.write(connectReq)
            outs.flush()
            val connectResp = ByteArray(4)
            if (ins.read(connectResp) < 4) return ProxyProbe.UNREACHABLE
            if (connectResp[1] != 0x00.toByte()) return ProxyProbe.CONNECT_FAILED

            // Consume the rest of the SOCKS response (BND.ADDR + BND.PORT)
            val addrType = connectResp[3]
            val remaining = when (addrType.toInt()) {
                0x01 -> 4 + 2
                0x04 -> 16 + 2
                0x03 -> {
                    val lenByte = ByteArray(1)
                    if (ins.read(lenByte) < 1) return ProxyProbe.UNREACHABLE
                    1 + lenByte[0].toInt() + 2
                }
                else -> return ProxyProbe.NOT_SOCKS5
            }
            var skipped = 0
            while (skipped < remaining) {
                val n = ins.read(ByteArray(remaining - skipped))
                if (n < 0) return ProxyProbe.UNREACHABLE
                skipped += n
            }
            ProxyProbe.OK
        } catch (e: Exception) {
            ProxyProbe.UNREACHABLE
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Returns a short "✓ …" / "✗ …" status string for display.
     * Safe to call from any thread; runs on the IO dispatcher.
     */
    suspend fun testProxy(
        server: String,
        port: Int,
        username: String,
        password: String
    ): String = withContext(Dispatchers.IO) {
        when (probeProxy(server, port, username, password)) {
            ProxyProbe.OK -> "✓ Proxy works"
            ProxyProbe.AUTH_FAILED -> "✗ Auth failed"
            ProxyProbe.NOT_SOCKS5 -> "✗ Not a SOCKS5 proxy"
            ProxyProbe.CONNECT_FAILED -> "✗ Connection failed"
            ProxyProbe.UNREACHABLE -> "✗ Proxy unreachable"
        }
    }
}
