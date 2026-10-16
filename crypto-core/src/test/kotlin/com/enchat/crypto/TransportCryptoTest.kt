package com.enchat.crypto

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TransportCryptoTest {

    private val serverIdentity = IdentityKeyPair.generate()

    private val serverStaticKeys
        get() = TransportCrypto.ServerStaticKeys(
            ed25519Public = serverIdentity.ed25519PublicKey,
            x25519Public = serverIdentity.x25519PublicKey
        )

    private fun handshake(): Pair<TransportSession, TransportSession> {
        val client = TransportCrypto.beginClientHandshake()
        val hello = client.helloBytes()
        val (serverHello, serverSession) = TransportCrypto.respondAsServer(hello, serverIdentity)
        val clientSession = client.complete(serverHello, serverStaticKeys)
        return clientSession to serverSession
    }

    @Test
    fun `handshake produces matching channel ids and working keys`() {
        val (client, server) = handshake()
        assertArrayEquals(client.channelId, server.channelId)
        val pt = "登录请求载荷".toByteArray()
        assertArrayEquals(pt, server.open(client.seal(pt)))
        assertArrayEquals("响应".toByteArray(), client.open(server.seal("响应".toByteArray())))
    }

    @Test
    fun `one hundred frames round trip in both directions`() {
        val (client, server) = handshake()
        repeat(100) { i ->
            val a = "c2s-$i".toByteArray()
            assertArrayEquals(a, server.open(client.seal(a)))
            val b = "s2c-$i".toByteArray()
            assertArrayEquals(b, client.open(server.seal(b)))
        }
        assertEquals(100L, client.nextSendSeq)
        assertEquals(100L, server.nextExpectedReceiveSeq)
        assertEquals(100L, client.nextExpectedReceiveSeq)
    }

    @Test
    fun `tampered frame is rejected`() {
        val (client, server) = handshake()
        val frame = client.seal("payload".toByteArray())
        val bad = frame.copyOf()
        bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 0x01).toByte()
        assertThrows(CryptoException::class.java) { server.open(bad) }
    }

    @Test
    fun `replayed frame is rejected`() {
        val (client, server) = handshake()
        val frame = client.seal("once".toByteArray())
        server.open(frame)
        assertThrows(CryptoException::class.java) { server.open(frame) }
    }

    @Test
    fun `out of order frame is rejected on ordered transport`() {
        val (client, server) = handshake()
        val f0 = client.seal("0".toByteArray())
        val f1 = client.seal("1".toByteArray())
        assertThrows(CryptoException::class.java) { server.open(f1) } // 先到 f1
        assertArrayEquals("0".toByteArray(), server.open(f0))
        assertArrayEquals("1".toByteArray(), server.open(f1))
    }

    @Test
    fun `frame from another session is rejected`() {
        val (client, _) = handshake()
        val (otherClient, otherServer) = handshake()
        val frame = client.seal("hello".toByteArray())
        assertThrows(CryptoException::class.java) { otherServer.open(frame) }
    }

    @Test
    fun `mitm server without legitimate key is rejected`() {
        val client = TransportCrypto.beginClientHandshake()
        val hello = client.helloBytes()
        val mitmIdentity = IdentityKeyPair.generate() // 中间人用自己的密钥签名
        val (mitmHello, _) = TransportCrypto.respondAsServer(hello, mitmIdentity)
        assertThrows(IllegalArgumentException::class.java) {
            client.complete(mitmHello, serverStaticKeys)
        }
    }

    @Test
    fun `malformed hello is rejected`() {
        val client = TransportCrypto.beginClientHandshake()
        assertEquals(TransportCrypto.CLIENT_HELLO_SIZE, client.helloBytes().size)
        assertThrows(IllegalArgumentException::class.java) {
            TransportCrypto.respondAsServer(ByteArray(10), serverIdentity)
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.complete(ByteArray(TransportCrypto.SERVER_HELLO_SIZE - 1), serverStaticKeys)
        }
    }

    @Test
    fun `frame version mismatch is rejected`() {
        val (client, server) = handshake()
        val frame = client.seal("x".toByteArray()).copyOf()
        frame[0] = 2
        assertThrows(CryptoException::class.java) { server.open(frame) }
    }

    @Test
    fun `two handshakes produce different keys`() {
        val (c1, s1) = handshake()
        val (c2, s2) = handshake()
        assertFalse(c1.channelId.contentEquals(c2.channelId))
        // 两条连接各自的第 0 帧交叉投递：密钥隔离 → 解不开；本连接 → 正常
        val f1 = c1.seal("x".toByteArray())
        val f2 = c2.seal("x".toByteArray())
        assertThrows(CryptoException::class.java) { s2.open(f1) }
        assertThrows(CryptoException::class.java) { s1.open(f2) }
        assertArrayEquals("x".toByteArray(), s1.open(f1))
        assertArrayEquals("x".toByteArray(), s2.open(f2))
    }
}
