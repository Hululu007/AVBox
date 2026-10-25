package com.github.avbox.osc.dlna

import org.fourthline.cling.model.message.StreamRequestMessage
import org.fourthline.cling.model.message.StreamResponseMessage
import org.fourthline.cling.model.message.UpnpHeaders
import org.fourthline.cling.model.message.UpnpResponse
import org.fourthline.cling.transport.spi.AbstractStreamClient
import org.fourthline.cling.transport.spi.AbstractStreamClientConfiguration

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

open class OkHttpStreamClient(private val configuration: Configuration) :
    AbstractStreamClient<OkHttpStreamClient.Configuration, Call>() {

    private val httpClient: OkHttpClient

    init {
        val timeout = configuration.timeoutSeconds + 5
        httpClient = OkHttpClient.Builder()
            .connectTimeout(timeout.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeout.toLong(), TimeUnit.SECONDS)
            .build()
    }

    override fun getConfiguration(): Configuration {
        return configuration
    }

    override fun createRequest(requestMessage: StreamRequestMessage): Call {
        val method = requestMessage.operation.httpMethodName
        val builder = Request.Builder()
            .url(requestMessage.operation.uri.toString())
            .method(method, buildRequestBody(requestMessage, method))
        for (entry in requestMessage.headers.entries) {
            val key = entry.key
            val values = entry.value
            if (key == null || values == null) continue
            for (value in values) {
                if (value != null) builder.addHeader(key, value)
            }
        }
        if (requestMessage.headers["user-agent"] == null) {
            builder.header("User-Agent", configuration.getUserAgentValue(requestMessage.udaMajorVersion, requestMessage.udaMinorVersion))
        }
        return httpClient.newCall(builder.build())
    }

    private fun buildRequestBody(requestMessage: StreamRequestMessage, method: String): RequestBody? {
        if (requestMessage.hasBody()) {
            val bytes = requestMessage.bodyBytes
            if (bytes != null && bytes.size > 0) {
                val contentTypes = requestMessage.headers["content-type"]
                val mediaType = if (contentTypes != null && contentTypes.isNotEmpty()) contentTypes[0].toMediaTypeOrNull() else null
                return bytes.toRequestBody(mediaType)
            }
        }
        return if (requiresBody(method)) ByteArray(0).toRequestBody(null) else null
    }

    private fun requiresBody(method: String): Boolean {
        return "POST" == method || "NOTIFY" == method || "PUT" == method
    }

    override fun createCallable(requestMessage: StreamRequestMessage, call: Call): Callable<StreamResponseMessage> {
        return Callable {
            val response = call.execute()
            try {
                val responseMessage = StreamResponseMessage(UpnpResponse(response.code, response.message))
                val upnpHeaders = UpnpHeaders()
                for (name in response.headers.names()) {
                    for (value in response.headers(name)) {
                        upnpHeaders.add(name, value)
                    }
                }
                responseMessage.setHeaders(upnpHeaders)
                val body = response.body
                val bytes = if (body != null) body.bytes() else ByteArray(0)
                if (bytes.size > 0) responseMessage.setBodyCharacters(bytes)
                responseMessage
            } finally {
                response.close()
            }
        }
    }

    override fun abort(call: Call) {
        call.cancel()
    }

    override fun logExecutionException(t: Throwable): Boolean {
        return false
    }

    override fun stop() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    open class Configuration(executorService: ExecutorService) : AbstractStreamClientConfiguration(executorService)
}
