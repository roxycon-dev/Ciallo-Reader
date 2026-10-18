package com.example.transfer

import java.io.IOException
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** Bind the real TCP socket before layering TLS; retain it so cancellation also stops connect/handshake. */
internal object TransferConnection {
    data class NearbyAccepted(val socket:SSLSocket,val remote:NearbyOffer)
    fun connectNearby(context:()->SSLContext,route:TransferRoute,local:NearbyOffer,expectedId:String?,
        claim:()->Boolean,trackProbe:(Socket)->Unit={},track:(Socket)->Unit={}):NearbyAccepted? {
        val raw=Socket()
        try {
            trackProbe(raw)
            route.localAddress?.let {raw.bind(InetSocketAddress(it,0))}
            raw.connect(InetSocketAddress(route.host,route.port),2_000)
            raw.soTimeout=1_500
            NearbyHandshake.write(DataOutputStream(raw.outputStream),local)
            val remote=NearbyHandshake.read(DataInputStream(raw.inputStream))
            require(remote.id!=local.id){"附近连接指向了本机"}
            if(expectedId!=null && remote.id!=expectedId)throw SecurityException("附近网络中的设备与所选设备不一致")
            if(local.sending && remote.sending && !remote.busy)throw IllegalStateException("双方都选择了发送，请让一台设备选择接收")
            if(!NearbyHandshake.shouldStart(local,remote) || !claim()){raw.close();return null}
            track(raw)
            return NearbyAccepted(secure(context(),raw,true,8_000,track),remote)
        } catch(e:Exception){runCatching {raw.close()};throw e}
    }
    fun acceptNearby(context:()->SSLContext,raw:Socket,offer:(NearbyOffer)->NearbyOffer,
        claim:()->Boolean,track:(Socket)->Unit={}):NearbyAccepted? {
        try {
            raw.soTimeout=1_500
            val remote=NearbyHandshake.read(DataInputStream(raw.inputStream))
            var local=offer(remote)
            var start=NearbyHandshake.shouldStart(local,remote)
            if(start && !claim()){local=local.copy(busy=true);start=false}
            NearbyHandshake.write(DataOutputStream(raw.outputStream),local)
            if(!start){raw.close();return null}
            track(raw)
            return NearbyAccepted(secure(context(),raw,false,8_000,track),remote)
        } catch(e:Exception){runCatching {raw.close()};throw e}
    }
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
            if(tls.isClosed)throw java.net.SocketException("连接已取消")
            tls.useClientMode=client
            if(!client)tls.needClientAuth=true
            TransferTls.configure(tls)
            tls.soTimeout=timeout
            tls.startHandshake()
            if(tls.isClosed)throw java.net.SocketException("连接已取消")
            return tls
        } catch(e:Exception){runCatching {tls.close()};throw e}
    }
}
