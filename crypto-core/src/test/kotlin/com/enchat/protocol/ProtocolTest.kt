package com.enchat.protocol

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProtocolTest {

    @Test
    fun `connection state banner texts match product requirement`() {
        assertEquals("连接中…", ConnectionState.CONNECTING.displayText)
        assertEquals("连接中…", ConnectionState.RECONNECTING.displayText)
        assertEquals("正在等待网络…", ConnectionState.WAITING_FOR_NETWORK.displayText)
        assertEquals("", ConnectionState.CONNECTED.displayText)
        assertFalse(ConnectionState.CONNECTED.showBanner())
        assertTrue(ConnectionState.CONNECTING.showBanner())
        assertTrue(ConnectionState.WAITING_FOR_NETWORK.showBanner())
    }

    @Test
    fun `username rules`() {
        assertTrue(Protocol.isValidUsername("alice"))
        assertTrue(Protocol.isValidUsername("alice_123"))
        assertFalse(Protocol.isValidUsername("ab"))        // 太短
        assertFalse(Protocol.isValidUsername("1alice"))    // 数字开头
        assertFalse(Protocol.isValidUsername("alice-1"))   // 非法字符
        assertFalse(Protocol.isValidUsername("中文名"))      // 非 ASCII
        assertFalse(Protocol.isValidUsername("a".repeat(33)))
        assertTrue(Protocol.isValidUsername("a".repeat(32)))
    }

    @Test
    fun `uid formatting pads to nine digits`() {
        assertEquals("000123456", Protocol.formatUid(123456L))
        assertEquals("000000001", Protocol.formatUid(1L))
        assertEquals("123456789", Protocol.formatUid(123456789L))
    }

    @Test
    fun `frame type codes round trip`() {
        FrameType.entries.forEach {
            assertEquals(it, FrameType.fromCode(it.code))
        }
        assertNull(FrameType.fromCode(9999))
        assertEquals(10, FrameType.SEND_MESSAGE.code)
        assertEquals(43, FrameType.E2EE_INIT.code)
    }

    @Test
    fun `message kind codes round trip`() {
        MessageKind.entries.forEach {
            assertEquals(it, MessageKind.fromCode(it.code))
        }
        assertNull(MessageKind.fromCode(77))
    }

    @Test
    fun `encryption modes have expected semantics`() {
        assertEquals(1, ChatEncryptionMode.TRANSPORT.code)
        assertEquals(2, ChatEncryptionMode.E2EE.code)
        assertEquals("标准加密", ChatEncryptionMode.TRANSPORT.displayName)
        assertEquals("端到端加密", ChatEncryptionMode.E2EE.displayName)
    }

    @Test
    fun `error codes carry messages`() {
        assertTrue(ErrorCode.TRANSPORT_DECRYPT_FAILED.message.contains("连接将被重置"))
        assertTrue(ErrorCode.entries.all { it.message.isNotBlank() })
        val codes = ErrorCode.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size) // 无重复码
    }

    @Test
    fun `limits are sane`() {
        assertTrue(Protocol.MAX_FRAME_BYTES >= Protocol.MAX_TEXT_BYTES)
        assertTrue(Protocol.MAX_AVATAR_BYTES > 0)
        assertTrue(Protocol.MAX_GROUP_MEMBERS > 1)
        assertTrue(Protocol.NICKNAME_MAX >= Protocol.NICKNAME_MIN)
    }
}
