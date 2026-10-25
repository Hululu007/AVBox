package com.github.avbox.osc.dlna

import org.fourthline.cling.model.message.Connection
import org.fourthline.cling.model.message.StreamRequestMessage
import org.fourthline.cling.model.message.StreamResponseMessage
import org.fourthline.cling.model.message.UpnpHeaders
import org.fourthline.cling.model.message.UpnpMessage
import org.fourthline.cling.model.message.UpnpRequest
import org.fourthline.cling.protocol.ProtocolFactory
import org.fourthline.cling.transport.Router
import org.fourthline.cling.transport.spi.InitializationException
import org.fourthline.cling.transport.spi.StreamServer
import org.fourthline.cling.transport.spi.StreamServerConfiguration
import org.fourthline.cling.transport.spi.UpnpStream

import com.github.avbox.osc.util.AppLog

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.nio.charset.Charset
import java.util.ArrayList
import java.util.HashMap

open class SocketHttpStreamServer(private val configuration: Configuration) :
    StreamServer<SocketHttpStreamServer.Configuration> {

    private var serverSocket: ServerSocket? = null

    @Volatile
    private var stopped = false
    private var router: Router? = null

    override fun getConfiguration(): Configuration {
        return configuration
    }

    override fun init(bindAddress: InetAddress, router: Router) {
        this.router = router
        try {
            val socket = ServerSocket()
            serverSocket = socket
            socket.setReuseAddress(true)
            socket.bind(InetSocketAddress(bindAddress, configuration.getListenPort()), 50)
        } catch (e: IOException) {
            throw InitializationException("Could not bind HTTP server socket on " + bindAddress, e)
        }
    }

    override fun getPort(): Int {
        return serverSocket?.localPort ?: -1
    }

    override fun stop() {
        stopped = true
        try {
            serverSocket?.close()
        } catch (ignored: IOException) {
            AppLog.d("SocketHttpStreamServer", "close server socket failed")
        }
    }

    override fun run() {
        while (!stopped) {
            try {
                val socket = serverSocket?.accept() ?: break
                socket.soTimeout = 30000
                val r = router ?: break
                r.received(SocketUpnpStream(r.protocolFactory, socket))
            } catch (e: SocketException) {
                break
            } catch (ignored: IOException) {
                AppLog.d("SocketHttpStreamServer", "accept failed, keep serving")
            }
        }
    }

    private class SocketUpnpStream(protocolFactory: ProtocolFactory, private val socket: Socket) : UpnpStream(protocolFactory) {

        override fun run() {
            try {
                val inputStream = socket.getInputStream()
                val requestLine = readLine(inputStream)
                val parts = requestLine.split(" ", limit = 3)
                if (requestLine.isEmpty() || parts.size < 2) {
                    socket.close()
                    return
                }
                val headers = readHeaders(inputStream)
                val requestMessage = buildRequestMessage(parts[0], parts[1], headers)
                readBodyInto(inputStream, requestMessage, headers)
                val responseMessage = process(requestMessage)
                val outputStream = socket.getOutputStream()
                writeResponse(outputStream, responseMessage)
                outputStream.flush()
                responseSent(responseMessage)
            } catch (e: Exception) {
                responseException(e)
            } finally {
                try {
                    socket.close()
                } catch (ignored: IOException) {
                    AppLog.d("SocketHttpStreamServer", "close socket failed")
                }
            }
        }

        private fun readLine(inputStream: InputStream): String {
            val sb = StringBuilder()
            var previous = -1
            while (true) {
                val value = inputStream.read()
                if (value == -1) break
                if (previous == '\r'.code && value == '\n'.code) {
                    sb.deleteCharAt(sb.length - 1)
                    return sb.toString()
                }
                sb.append(value.toChar())
                previous = value
            }
            return sb.toString()
        }

        private fun readHeaders(inputStream: InputStream): Map<String, List<String>> {
            val headers = HashMap<String, MutableList<String>>()
            while (true) {
                val line = readLine(inputStream)
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon < 0) continue
                val key = line.substring(0, colon).trim { it <= ' ' }.lowercase()
                val value = line.substring(colon + 1).trim { it <= ' ' }
                var values = headers[key]
                if (values == null) {
                    values = ArrayList()
                    headers[key] = values
                }
                values.add(value)
            }
            return headers
        }

        private fun buildRequestMessage(method: String, rawUri: String, headers: Map<String, List<String>>): StreamRequestMessage {
            val message = StreamRequestMessage(UpnpRequest.Method.getByHttpName(method), URI.create(rawUri))
            message.setConnection(SocketConnection(socket))
            message.setHeaders(UpnpHeaders(headers))
            return message
        }

        private fun readBodyInto(inputStream: InputStream, message: StreamRequestMessage, headers: Map<String, List<String>>) {
            val lengthHeaders = headers["content-length"]
            if (lengthHeaders == null || lengthHeaders.isEmpty()) return
            val length = lengthHeaders[0].trim { it <= ' ' }.toInt()
            if (length <= 0) return
            val body = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = inputStream.read(body, offset, length - offset)
                if (read == -1) break
                offset += read
            }
            if (message.isContentTypeMissingOrText) message.setBodyCharacters(body)
            else message.setBody(UpnpMessage.BodyType.BYTES, body)
        }

        private fun writeResponse(outputStream: OutputStream, message: StreamResponseMessage?) {
            if (message == null) {
                writeStatusLine(outputStream, 404, "Not Found")
                writeHeader(outputStream, "Content-Length", "0")
                writeEndHeaders(outputStream)
                return
            }
            writeStatusLine(outputStream, message.operation.statusCode, message.operation.statusMessage)
            for (entry in message.headers.entries) {
                val key = entry.key
                val values = entry.value
                if (key == null || values == null) continue
                for (value in values) {
                    writeHeader(outputStream, key, value)
                }
            }
            val body = if (message.hasBody()) message.bodyBytes else null
            writeHeader(outputStream, "Content-Length", (body?.size ?: 0).toString())
            writeEndHeaders(outputStream)
            if (body != null && body.size > 0) outputStream.write(body)
        }

        private fun writeStatusLine(outputStream: OutputStream, code: Int, reason: String?) {
            outputStream.write(("HTTP/1.1 " + code + " " + (reason ?: "") + "\r\n").toByteArray(ISO_8859_1))
        }

        private fun writeHeader(outputStream: OutputStream, name: String, value: String) {
            outputStream.write((name + ": " + value + "\r\n").toByteArray(ISO_8859_1))
        }

        private fun writeEndHeaders(outputStream: OutputStream) {
            outputStream.write("\r\n".toByteArray(ISO_8859_1))
        }

        companion object {
            private val ISO_8859_1 = Charset.forName("ISO-8859-1")
        }
    }

    private class SocketConnection(private val socket: Socket) : Connection {
        override fun isOpen(): Boolean {
            return !socket.isClosed
        }

        override fun getRemoteAddress(): InetAddress? {
            return socket.inetAddress
        }

        override fun getLocalAddress(): InetAddress? {
            return socket.localAddress
        }
    }

    class Configuration(port: Int) : StreamServerConfiguration {
        private val listenPort = port

        override fun getListenPort(): Int {
            return listenPort
        }
    }
}
