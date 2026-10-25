package com.github.avbox.osc.dlna

import android.os.Build

import org.fourthline.cling.android.AndroidUpnpServiceConfiguration
import org.fourthline.cling.binding.xml.ServiceDescriptorBinder
import org.fourthline.cling.binding.xml.UDA10ServiceDescriptorBinderImpl
import org.fourthline.cling.model.ServerClientTokens
import org.fourthline.cling.transport.spi.NetworkAddressFactory
import org.fourthline.cling.transport.spi.StreamClient
import org.fourthline.cling.transport.spi.StreamServer

open class DLNAServiceConfiguration : AndroidUpnpServiceConfiguration() {
    override fun createStreamClient(): StreamClient<*> {
        return OkHttpStreamClient(object : OkHttpStreamClient.Configuration(getSyncProtocolExecutorService()) {
            override fun getUserAgentValue(majorVersion: Int, minorVersion: Int): String {
                val tokens = ServerClientTokens(majorVersion, minorVersion)
                tokens.setOsName("Android")
                tokens.setOsVersion(Build.VERSION.RELEASE)
                return tokens.toString()
            }
        })
    }

    override fun createStreamServer(networkAddressFactory: NetworkAddressFactory): StreamServer<*> {
        return SocketHttpStreamServer(SocketHttpStreamServer.Configuration(networkAddressFactory.streamListenPort))
    }

    override fun createServiceDescriptorBinderUDA10(): ServiceDescriptorBinder {
        return UDA10ServiceDescriptorBinderImpl()
    }
}
