package com.example.transfer

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** Bind the real TCP socket before layering TLS; retain it so cancellation also stops connect/handshake. */
internal object TransferConnection {
    fun connect(context:SSLContext,route:TransferRoute,bind:(Socket)->Unit={},
        track:(Socket)->Unit={},stage:(String)->Unit={},connectTimeout:Int=4_000,handshakeTimeout:Int=8_000):SSLSocket {
        val raw=Socket()
        try {
            track(raw)
            stage("正在连接设备")
            bind(raw)
            raw.connect(InetSocketAddress(route.host,route.port),connectTimeout)
            stage("正在建立加密连接")
            return secure(context,raw,true,handshakeTimeout,track)
        } catch(e:Exception) {
            runCatching {raw.close()}
            if(e is IOException)throw IOException(if(raw.isConnected)"设备已连接，但加密握手失败：${e.message}" else "无法连接设备：${e.message}",e)
            throw e
        }
    }

    fun accept(context:SSLContext,raw:Socket,track:(Socket)->Unit={},handshakeTimeout:Int=8_000):SSLSocket =
        try {secure(context,raw,false,handshakeTimeout,track)} catch(e:Exception){runCatching {raw.close()};throw e}

    private fun secure(context:SSLContext,raw:Socket,client:Boolean,timeout:Int,track:(Socket)->Unit):SSLSocket {
        val tls=context.socketFactory.createSocket(raw,raw.inetAddress.hostAddress,raw.port,true) as SSLSocket
        try {
            track(tls)
            tls.useClientMode=client
            if(!client)tls.needClientAuth=true
            TransferTls.configure(tls)
            tls.soTimeout=timeout
            tls.startHandshake()
            return tls
        } catch(e:Exception){runCatching {tls.close()};throw e}
    }
}
