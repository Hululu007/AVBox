package com.github.tvbox.osc.server

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

/**
 * /dns-query 应答报文的字节级回归。
 *
 * 存在理由:IPv4 曾被写成 QTYPE=0x011C、答案一律标 AAAA 且声明 RDLENGTH=16(实际只写 4 字节),
 * 任何标准解析器都判格式错误,该端点对 IPv4 场景完全不可用。
 */
class RemoteServerDnsTest {

    // ID + flags + QD/AN/NS/AR 计数
    private fun header(flags: String, answerCount: String) =
        "0000" + flags + "0001" + answerCount + "0000" + "0000"

    // 07 "example" 03 "com" 00
    private val qname = "076578616d706c6503636f6d00"
    private val answerA = "c00c" + "0001" + "0001" + "0000003c" + "0004" + "01020304"
    private val answerAaaa = "c00c" + "001c" + "0001" + "0000003c" + "0010" + "20010db8000000000000000000000001"

    @Test
    fun ipv4AnswerIsWellFormed() {
        val expected = header("8180", "0001") + qname + "0001" + "0001" + answerA
        assertEquals(expected, build(listOf(InetAddress.getByName("1.2.3.4"))))
    }

    @Test
    fun ipv6OnlyAnswerUsesAaaaQuestion() {
        val expected = header("8180", "0001") + qname + "001c" + "0001" + answerAaaa
        assertEquals(expected, build(listOf(InetAddress.getByName("2001:db8::1"))))
    }

    @Test
    fun mixedAnswerWritesTypeAndRdlengthPerRecord() {
        val expected = header("8180", "0002") + qname + "0001" + "0001" + answerA + answerAaaa
        assertEquals(expected, build(listOf(InetAddress.getByName("1.2.3.4"), InetAddress.getByName("2001:db8::1"))))
    }

    @Test
    fun emptyAnswerKeepsQuestionAndReturnsServfail() {
        val expected = header("8182", "0000") + qname + "0001" + "0001"
        assertEquals(expected, build(emptyList()))
    }

    private fun build(addresses: List<InetAddress>) = RemoteServer.buildDnsResponse("example.com", addresses).hex()

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
