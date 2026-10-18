package com.example.transfer

import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class NearbyGroup(val owner:Boolean,val host:String,val localAddress:String?,val members:Set<String>)

/** Framework adapter. Success from connect means only that negotiation was requested. */
internal interface NearbyPlatform {
    fun group(callback:(NearbyGroup?)->Unit)
    fun pauseDiscovery(callback:()->Unit)
    fun connect(address:String,callback:(String?)->Unit)
    fun removeGroup(callback:(String?)->Unit)
    fun cancelPending()
}

/** Polls authoritative connection state as well as reacting to broadcasts, including already formed groups. */
internal class NearbyConnector(private val scope:CoroutineScope,private val platform:NearbyPlatform,
    private val onGroup:(NearbyGroup)->Unit,private val onStage:(String)->Unit,private val onError:(String)->Unit,
    private val pollMillis:Long=750,private val negotiationMillis:Long=35_000) {
    private var job:Job?=null
    private var connecting=false
    private var epoch=0
    private val wake=kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    fun observe() {
        if(job?.isActive==true)return
        job=scope.launch {try {watch()}catch(e:CancellationException){throw e}catch(e:Exception){onError(e.message?:"附近连接状态读取失败")}}
    }
    fun refresh() {wake.trySend(Unit)}
    fun connect(address:String) {
        job?.cancel()
        if(connecting)platform.cancelPending()
        val token=++epoch
        connecting=true
        job=scope.launch {
            try {
                onStage("正在建立附近网络")
                withTimeout(negotiationMillis) {
                    val existing=readGroup()
                    if(existing!=null && existing.members.any {it.equals(address,true)}) {
                        onGroup(existing)
                    } else {
                        if(existing!=null){action {platform.removeGroup(it)};waitForRemoval()}
                        suspendCancellableCoroutine<Unit> {c ->platform.pauseDiscovery {if(c.isActive)c.resume(Unit)}}
                        action {platform.connect(address,it)}
                        while(isActive) {
                            val group=readGroup()
                            if(group!=null){onGroup(group);break}
                            pause()
                        }
                    }
                }
                if(epoch==token)connecting=false
                watch()
            } catch(e:TimeoutCancellationException) {
                platform.cancelPending();onError("附近网络未建立，系统连接请求已超时。可重新连接")
            } catch(e:CancellationException){throw e}
            catch(e:Exception){platform.cancelPending();onError(e.message?:"附近连接未能建立")}
            finally {if(epoch==token)connecting=false}
        }
    }
    fun stop(disconnect:Boolean=false) {
        job?.cancel();job=null
        epoch++
        if(disconnect)platform.cancelPending()
        connecting=false
        if(disconnect)platform.removeGroup {}
    }
    fun cancelConnection() {
        stop()
        platform.cancelPending()
        val token=epoch
        job=scope.launch {
            try {
                if(readGroup()!=null){action {platform.removeGroup(it)};waitForRemoval()}
                if(token==epoch)watch()
            } catch(e:CancellationException){throw e}
            catch(e:Exception){onError(e.message?:"附近网络清理失败")}
        }
    }
    private suspend fun waitForRemoval() {
        check(withTimeoutOrNull(5_000){while(readGroup()!=null)pause();true}==true){"旧的附近网络尚未断开，请稍后重新连接"}
    }
    private suspend fun readGroup():NearbyGroup?=withTimeoutOrNull(1_500) {
        suspendCancellableCoroutine {c ->platform.group {if(c.isActive)c.resume(it)}}
    }
    private suspend fun action(call:((String?)->Unit)->Unit) = suspendCancellableCoroutine<Unit> {c ->
        call {error ->if(c.isActive){if(error==null)c.resume(Unit)else c.resumeWithException(IllegalStateException(error))}}
    }
    private suspend fun watch() {
        while(currentCoroutineContext().isActive) {
            readGroup()?.let(onGroup)
            pause()
        }
    }
    private suspend fun pause(){withTimeoutOrNull(pollMillis){wake.receive()}}
}
