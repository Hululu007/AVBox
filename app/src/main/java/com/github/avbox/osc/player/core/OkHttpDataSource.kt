package com.github.avbox.osc.player.core

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.Util
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.HttpUtil
import androidx.media3.datasource.TransferListener
import com.google.common.base.Predicate
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.Collections
import java.util.HashMap
import java.util.concurrent.CountDownLatch

class OkHttpDataSource private constructor(
    private val callFactory: Call.Factory,
    private val userAgent: String?,
    private val cacheControl: CacheControl?,
    private val defaultRequestProperties: HttpDataSource.RequestProperties?,
    private var contentTypePredicate: Predicate<String>?,
) : BaseDataSource(true), HttpDataSource {

    class Factory(private val callFactory: Call.Factory) : HttpDataSource.BaseFactory() {

        private var userAgent: String? = null
        private var transferListener: TransferListener? = null
        private var cacheControl: CacheControl? = null
        private var contentTypePredicate: Predicate<String>? = null

        fun setUserAgent(userAgent: String?): Factory = apply { this.userAgent = userAgent }

        fun setCacheControl(cacheControl: CacheControl?): Factory = apply { this.cacheControl = cacheControl }

        fun setContentTypePredicate(contentTypePredicate: Predicate<String>?): Factory =
            apply { this.contentTypePredicate = contentTypePredicate }

        fun setTransferListener(transferListener: TransferListener?): Factory =
            apply { this.transferListener = transferListener }

        override fun createDataSourceInternal(
            defaultRequestProperties: HttpDataSource.RequestProperties,
        ): HttpDataSource {
            val dataSource = OkHttpDataSource(callFactory, userAgent, cacheControl, defaultRequestProperties, contentTypePredicate)
            transferListener?.let { dataSource.addTransferListener(it) }
            return dataSource
        }
    }

    private val requestProperties = HttpDataSource.RequestProperties()

    private var dataSpec: DataSpec? = null
    private var response: Response? = null
    private var responseByteStream: InputStream? = null
    private var opened = false
    private var bytesToRead = 0L
    private var bytesRead = 0L

    fun setContentTypePredicate(contentTypePredicate: Predicate<String>?) {
        this.contentTypePredicate = contentTypePredicate
    }

    override fun getUri(): Uri? = response?.request?.url?.let { Uri.parse(it.toString()) }

    override fun getResponseCode(): Int = response?.code ?: -1

    override fun getResponseHeaders(): Map<String, List<String>> =
        response?.headers?.toMultimap() ?: Collections.emptyMap()

    override fun setRequestProperty(name: String, value: String) {
        requestProperties.set(name, value)
    }

    override fun clearRequestProperty(name: String) {
        requestProperties.remove(name)
    }

    override fun clearAllRequestProperties() {
        requestProperties.clear()
    }

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        bytesRead = 0
        bytesToRead = 0
        transferInitializing(dataSpec)

        val request = makeRequest(dataSpec)
        val response: Response
        val responseBody: okhttp3.ResponseBody
        val call = callFactory.newCall(request)
        try {
            response = executeCall(call)
            this.response = response
            responseBody = response.body
            responseByteStream = responseBody.byteStream()
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e,
                dataSpec,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        }

        val responseCode = response.code
        if (!response.isSuccessful) {
            if (responseCode == 416) {
                val documentSize = HttpUtil.getDocumentSize(response.headers["Content-Range"])
                if (dataSpec.position == documentSize) {
                    opened = true
                    transferStarted(dataSpec)
                    return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else 0
                }
            }

            val errorResponseBody: ByteArray = try {
                Util.toByteArray(requireNotNull(responseByteStream))
            } catch (e: IOException) {
                Util.EMPTY_BYTE_ARRAY
            }
            val headers = response.headers.toMultimap()
            closeConnectionQuietly()
            val cause: IOException? =
                if (responseCode == 416) DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) else null
            throw HttpDataSource.InvalidResponseCodeException(
                responseCode,
                response.message,
                cause,
                headers,
                dataSpec,
                errorResponseBody,
            )
        }

        val mediaType: MediaType? = responseBody.contentType()
        val contentType = mediaType?.toString() ?: ""
        val predicate = contentTypePredicate
        if (predicate != null && !predicate.apply(contentType)) {
            closeConnectionQuietly()
            throw HttpDataSource.InvalidContentTypeException(contentType, dataSpec)
        }

        val bytesToSkip = if (responseCode == 200 && dataSpec.position != 0L) dataSpec.position else 0L
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            bytesToRead = dataSpec.length
        } else {
            val contentLength = responseBody.contentLength()
            bytesToRead = if (contentLength != -1L) contentLength - bytesToSkip else C.LENGTH_UNSET.toLong()
        }

        opened = true
        transferStarted(dataSpec)

        try {
            skipFully(bytesToSkip, dataSpec)
        } catch (e: HttpDataSource.HttpDataSourceException) {
            closeConnectionQuietly()
            throw e
        }
        return bytesToRead
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return try {
            val read = readInternal(buffer, offset, length)
            SlowOpenSignal.note(read)
            read
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e,
                requireNotNull(dataSpec),
                HttpDataSource.HttpDataSourceException.TYPE_READ,
            )
        }
    }

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
            closeConnectionQuietly()
        }
    }

    private fun makeRequest(dataSpec: DataSpec): Request {
        val position = dataSpec.position
        val length = dataSpec.length

        val url = dataSpec.uri.toString().toHttpUrlOrNull()
            ?: throw HttpDataSource.HttpDataSourceException(
                "Malformed URL",
                dataSpec,
                PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )

        val builder = Request.Builder().url(url)
        if (cacheControl != null) {
            builder.cacheControl(cacheControl)
        }

        val headers = HashMap<String, String>()
        defaultRequestProperties?.snapshot?.let { headers.putAll(it) }
        headers.putAll(requestProperties.snapshot)
        headers.putAll(dataSpec.httpRequestHeaders)
        for ((key, value) in headers) {
            builder.header(key, value)
        }

        HttpUtil.buildRangeRequestHeader(position, length)?.let { builder.addHeader("Range", it) }
        if (userAgent != null) {
            builder.addHeader("User-Agent", userAgent)
        }
        if (!dataSpec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP)) {
            builder.addHeader("Accept-Encoding", "identity")
        }

        val httpBody: ByteArray? = dataSpec.httpBody
        val requestBody = when {
            httpBody != null -> httpBody.toRequestBody(null)
            dataSpec.httpMethod == DataSpec.HTTP_METHOD_POST -> Util.EMPTY_BYTE_ARRAY.toRequestBody(null)
            else -> null
        }
        builder.method(dataSpec.httpMethodString, requestBody)
        return builder.build()
    }

    private fun executeCall(call: Call): Response {
        val responseHolder = arrayOfNulls<Response>(1)
        val errorHolder = arrayOfNulls<IOException>(1)
        val latch = CountDownLatch(1)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                errorHolder[0] = e
                latch.countDown()
            }

            override fun onResponse(call: Call, response: Response) {
                responseHolder[0] = response
                latch.countDown()
            }
        })

        var interrupted = false
        while (true) {
            try {
                latch.await()
                break
            } catch (e: InterruptedException) {
                interrupted = true
                call.cancel()
            }
        }
        if (interrupted) {
            throw InterruptedIOException()
        }
        errorHolder[0]?.let { throw it }
        return requireNotNull(responseHolder[0])
    }

    private fun skipFully(bytesToSkip: Long, dataSpec: DataSpec) {
        if (bytesToSkip == 0L) {
            return
        }
        var remaining = bytesToSkip
        val skipBuffer = ByteArray(4096)
        try {
            while (remaining > 0) {
                val readLength = minOf(remaining, skipBuffer.size.toLong()).toInt()
                val read = requireNotNull(responseByteStream).read(skipBuffer, 0, readLength)
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedIOException()
                }
                if (read == -1) {
                    throw HttpDataSource.HttpDataSourceException(
                        dataSpec,
                        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                        HttpDataSource.HttpDataSourceException.TYPE_OPEN,
                    )
                }
                remaining -= read
                bytesTransferred(read)
            }
        } catch (e: IOException) {
            if (e is HttpDataSource.HttpDataSourceException) {
                throw e
            }
            throw HttpDataSource.HttpDataSourceException(
                dataSpec,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        }
    }

    private fun readInternal(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (readLength == 0) {
            return 0
        }
        var length = readLength
        if (bytesToRead != C.LENGTH_UNSET.toLong()) {
            val bytesRemaining = bytesToRead - bytesRead
            if (bytesRemaining == 0L) {
                return C.RESULT_END_OF_INPUT
            }
            length = minOf(length.toLong(), bytesRemaining).toInt()
        }

        val read = requireNotNull(responseByteStream).read(buffer, offset, length)
        if (read == -1) {
            return C.RESULT_END_OF_INPUT
        }

        bytesRead += read
        bytesTransferred(read)
        return read
    }

    private fun closeConnectionQuietly() {
        response?.let { it.body.close() }
        response = null
        responseByteStream = null
    }
}
