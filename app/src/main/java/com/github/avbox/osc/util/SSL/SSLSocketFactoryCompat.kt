package com.github.avbox.osc.util.SSL

import android.os.Build

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import java.util.HashSet
import java.util.LinkedList

import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class SSLSocketFactoryCompat(tm: X509TrustManager?) : SSLSocketFactory() {
    private val defaultFactory: SSLSocketFactory

    companion object {
        @JvmField
        var protocols: Array<String>? = null
        @JvmField
        var cipherSuites: Array<String>? = null

        init {
            try {
                val socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
                if (socket != null) {
                    val protocols = LinkedList<String>()
                    for (protocol in socket.supportedProtocols)
                        if (!protocol.uppercase().contains("SSL"))
                            protocols.add(protocol)
                    SSLSocketFactoryCompat.protocols = protocols.toTypedArray()
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                        val allowedCiphers: List<String> = listOf(
                            "TLS_RSA_WITH_AES_256_GCM_SHA384",
                            "TLS_RSA_WITH_AES_128_GCM_SHA256",
                            "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256",
                            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
                            "TLS_ECHDE_RSA_WITH_AES_128_GCM_SHA256",
                            "TLS_RSA_WITH_3DES_EDE_CBC_SHA",
                            "TLS_RSA_WITH_AES_128_CBC_SHA",
                            "TLS_RSA_WITH_AES_256_CBC_SHA",
                            "TLS_ECDHE_ECDSA_WITH_3DES_EDE_CBC_SHA",
                            "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
                            "TLS_ECDHE_RSA_WITH_3DES_EDE_CBC_SHA",
                            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA")
                        val availableCiphers: List<String> = listOf(*socket.supportedCipherSuites)
                        val preferredCiphers = HashSet(allowedCiphers)
                        preferredCiphers.retainAll(availableCiphers)
                        val enabledCiphers = preferredCiphers
                        enabledCiphers.addAll(HashSet(listOf(*socket.enabledCipherSuites)))
                        SSLSocketFactoryCompat.cipherSuites = enabledCiphers.toTypedArray()
                    }
                }
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
        }
    }

    init {
        defaultFactory = try {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, if (tm != null) arrayOf<TrustManager>(tm) else null, null)
            sslContext.socketFactory
        } catch (e: GeneralSecurityException) {
            throw AssertionError()
        }
    }

    private fun upgradeTLS(ssl: SSLSocket) {
        val tlsProtocols = protocols
        if (tlsProtocols != null) {
            ssl.enabledProtocols = tlsProtocols
        }
        val tlsCipherSuites = cipherSuites
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP && tlsCipherSuites != null) {
            ssl.enabledCipherSuites = tlsCipherSuites
        }
    }

    override fun getDefaultCipherSuites(): Array<String>? {
        return cipherSuites
    }

    override fun getSupportedCipherSuites(): Array<String>? {
        return cipherSuites
    }

    @Throws(IOException::class)
    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
        val ssl = defaultFactory.createSocket(s, host, port, autoClose)
        if (ssl is SSLSocket)
            upgradeTLS(ssl)
        return ssl
    }

    @Throws(IOException::class, UnknownHostException::class)
    override fun createSocket(host: String, port: Int): Socket {
        val ssl = defaultFactory.createSocket(host, port)
        if (ssl is SSLSocket)
            upgradeTLS(ssl)
        return ssl
    }

    @Throws(IOException::class, UnknownHostException::class)
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
        val ssl = defaultFactory.createSocket(host, port, localHost, localPort)
        if (ssl is SSLSocket)
            upgradeTLS(ssl)
        return ssl
    }

    @Throws(IOException::class)
    override fun createSocket(host: InetAddress, port: Int): Socket {
        val ssl = defaultFactory.createSocket(host, port)
        if (ssl is SSLSocket)
            upgradeTLS(ssl)
        return ssl
    }

    @Throws(IOException::class)
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
        val ssl = defaultFactory.createSocket(address, port, localAddress, localPort)
        if (ssl is SSLSocket)
            upgradeTLS(ssl)
        return ssl
    }
}
