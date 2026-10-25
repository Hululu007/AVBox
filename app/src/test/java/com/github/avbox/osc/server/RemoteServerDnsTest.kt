package com.github.avbox.osc.server

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class RemoteServerDnsTest {

    private fun header(flags: String, answerCount: String) =
        "0000" + flags + "0001" + answerCount + "0000" + "0000"

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
