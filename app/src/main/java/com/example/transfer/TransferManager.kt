package com.example.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import com.example.data.BackupManager
import com.example.data.PreferencesManager
import com.example.data.PrivacyManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.UUID
import javax.net.ssl.SSLSocket

/** One application-scoped task owner. UI, service and discovery observe the same state. */
class TransferManager private constructor(private val context:Context) {
    companion object {
        @Volatile private var instance:TransferManager?=null
        fun get(context:Context):TransferManager=instance?:synchronized(this) { instance?:TransferManager(context.applicationContext).also { instance=it } }
        fun sharedUri(intent:Intent):Uri? {
            val uri=when(intent.action) {
                Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) as? Uri)
                Intent.ACTION_VIEW -> intent.data
                else -> null
            }
            return uri?.takeIf { it.scheme in setOf("content","file") && intent.data?.scheme!="ciallo" }
        }
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val backup=BackupManager(context,PreferencesManager(context))
    private val prefs=context.getSharedPreferences("device_transfer",0)
    private val id=UUID.randomUUID().toString()
    val deviceName:String=(Build.MODEL?:"Android").take(64)
    private val directory=File(context.filesDir,"transfer_sessions").apply { mkdirs() }
    private val mutableState=MutableStateFlow(TransferSessionState()); val state=mutableState.asStateFlow()
    private val mutableOpen=MutableStateFlow(false); val openPage=mutableOpen.asStateFlow()
    private val mutableEnabled=MutableStateFlow(prefs.getBoolean("enabled",false)); val enabled=mutableEnabled.asStateFlow()
    private var foreground=false
    private var server:java.net.ServerSocket?=null
    private var serverJob:Job?=null
    private var nearbyServer:java.net.ServerSocket?=null
    private var nearbyServerJob:Job?=null
    @Volatile private var nearbyProbeSocket:java.net.Socket?=null
    @Volatile private var pendingNearby=false
    private var nearbyGroupSeen=false
    @Volatile private var nearTask=false
    private var task:Job?=null
    private var connectionTimeout:Job?=null
    @Volatile private var socket:SSLSocket?=null
    @Volatile private var connectingSocket:java.net.Socket?=null
    private var directJob:Job?=null
    @Volatile private var claimed=false
    private var outgoing:JSONObject?=null
    private var localDecision:CompletableDeferred<Boolean>?=null
    private var importDecision:CompletableDeferred<ImportMode>?=null
    private var receiveMode=ImportMode.MERGE
    private val discovery=TransferDiscovery(context,id,deviceName,::nearbyGroup,::nearbyStage,::nearbyFailure)
    private fun sendIntent():Boolean=outgoing!=null && state.value.phase in setOf(
        TransferPhase.CONNECTING,TransferPhase.AWAITING_CONFIRMATION,TransferPhase.TRANSFERRING,TransferPhase.VERIFYING)
    private fun nearbyOffer(requester:String?=null):NearbyOffer {
        val sending=sendIntent()
        val wrongPeer=sending && requester!=null && outgoing!!.optString("peerFingerprint").isEmpty() && outgoing!!.optString("peerId")!=requester
        val busy=claimed || state.value.phase in setOf(TransferPhase.PREPARING,TransferPhase.IMPORTING,TransferPhase.VERIFYING,TransferPhase.RECEIVED) ||
            outgoing!=null && !sending || wrongPeer
        return NearbyOffer(id,deviceName,sending,busy)
    }
    private fun nearbyStage(message:String) {if(pendingNearby && !claimed)change(TransferPhase.CONNECTING,message)}
    private fun nearbyFailure(message:String) {
        if(!pendingNearby || claimed)return
        pendingNearby=false;connectionTimeout?.cancel()
        change(TransferPhase.WAITING_TO_RESUME,message);stopServiceIfIdle()
    }
    private fun nearbyTimeout(millis:Long,message:String) {
        connectionTimeout?.cancel()
        connectionTimeout=scope.launch {delay(millis);if(pendingNearby && !claimed && state.value.phase==TransferPhase.CONNECTING) {
            withContext(Dispatchers.Main){discovery.cancelConnection()}
            nearbyFailure(message)
        }}
    }
    private fun nearbyGroup(group:NearbyGroup) {
        if(pendingNearby && !nearbyGroupSeen) {
            nearbyGroupSeen=true
            nearbyStage(if(group.owner)"附近网络已建立，等待对方连接" else "附近网络已建立，正在连接对方")
            nearbyTimeout(18_000,"附近网络已建立，但未能连接到对方的互传服务。请确认双方均已更新附近连接版本后重试")
        }
        if(group.owner || claimed || socket!=null || directJob?.isActive==true)return
        directJob=scope.launch {
            var ownsClaim=false;var handedOff=false;var probe:java.net.Socket?=null;var tracked:java.net.Socket?=null
            try {
                val local=nearbyOffer()
                if(local.busy)return@launch
                val route=TransferRoute(group.host,NearbyHandshake.PORT,localAddress=group.localAddress,nearby=true)
                val linked=TransferConnection.connectNearby({TransferTls.context()},route,local,
                    if(local.sending)outgoing?.optString("peerId") else null,
                    claim={claim().also {ownsClaim=it;if(it){nearTask=true;pendingNearby=false;connectionTimeout?.cancel();change(TransferPhase.CONNECTING,"正在建立加密连接")}}},
                    trackProbe={probe=it;nearbyProbeSocket=it},track={tracked=it;connectingSocket=it})?:return@launch
                val endpoint=TransferPeer(linked.remote.id,linked.remote.name,group.host,NearbyHandshake.PORT,routes=listOf(route),nearbyProtocol=NearbyHandshake.VERSION)
                handedOff=true
                runSocket(linked.socket,false,endpoint)
            } catch(e:Exception) {
                if(e !is CancellationException && ownsClaim)fail(e)
                else if(e !is CancellationException && pendingNearby && e !is java.io.IOException)nearbyFailure(e.message?:"附近服务连接失败")
            } finally {
                runCatching {probe?.close()};runCatching {tracked?.close()}
                if(nearbyProbeSocket===probe)nearbyProbeSocket=null
                if(connectingSocket===tracked)connectingSocket=null
                if(ownsClaim && !handedOff){claimed=false;stopServiceIfIdle()}
            }
        }
    }
    val peers=discovery.peers
    val discoveryMessage=discovery.message
    init {
        scope.launch {
            cleanup()
            val file=File(directory,"outgoing.json")
            if(file.exists()) runCatching { read(file).takeIf { System.currentTimeMillis()-it.getLong("createdAt")<TRANSFER_RETENTION && File(it.getString("file")).isFile } }.getOrNull()?.let {
                outgoing=it
                change(TransferPhase.WAITING_TO_RESUME,"上次传输尚未完成，点击继续",peer=peer(it))
            }
            if(outgoing==null)File(directory,"incoming.json").takeIf { it.isFile }?.let { incoming ->
                runCatching {read(incoming)}.getOrNull()?.takeIf {!it.has("result")}?.let { saved ->
                    mutableState.value=TransferSessionState(TransferPhase.WAITING_TO_RESUME,incoming=true,message="上次接收尚未完成，请让发送设备继续传输",peer=TransferPeer("resume",saved.optString("peerName","发送设备")))
                }
            }
        }
    }
    fun show() { mutableOpen.value=true }
    fun hide() {
        if(needsRestart())context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK);context.startActivity(it)
        }
        mutableOpen.value=false
    }
    fun setForeground(value:Boolean) {
        foreground=value
        if(value && enabled.value) startListening()
        else if(!value && !state.value.busy) stopIdle()
    }
    fun enable(value:Boolean) {
        check(prefs.edit().putBoolean("enabled",value).commit())
        mutableEnabled.value=value
        if(value && foreground) startListening() else if(!value && !state.value.busy) stopIdle(disconnectNearby=true)
    }
    fun scan() {
        if(!enabled.value) enable(true)
        startListening()
        scope.launch(Dispatchers.Main.immediate) { discovery.scan() }
        if(!state.value.busy) change(TransferPhase.DISCOVERING,"请让另一台设备打开 Ciallo Reader")
    }
    private fun startListening() {
        if(serverJob?.isActive==true) {
            server?.localPort?.let {port ->scope.launch(Dispatchers.Main.immediate){discovery.start(port,nearbyServer?.isClosed==false)}}
            return
        }
        serverJob=scope.launch {
            var listener:java.net.ServerSocket?=null
            var direct:java.net.ServerSocket?=null
            try {
                listener=java.net.ServerSocket().apply {
                    reuseAddress=true
                    try {bind(InetSocketAddress(TransferDiscovery.PORT))}catch(_:java.net.BindException){bind(InetSocketAddress(0))}
                }
                server=listener
                direct=runCatching {java.net.ServerSocket().apply {reuseAddress=true;bind(InetSocketAddress(NearbyHandshake.PORT))}}.getOrNull()
                nearbyServer=direct
                direct?.let {fixed ->nearbyServerJob=scope.launch {acceptConnections(fixed,true)}}
                withContext(Dispatchers.Main){discovery.start(listener.localPort,direct!=null)}
                acceptConnections(listener,false)
            } catch(e:Exception){if(e !is CancellationException && enabled.value && foreground)fail(e)}
            finally {
                runCatching {listener?.close()};runCatching {direct?.close()}
                if(server===listener)server=null
                if(nearbyServer===direct)nearbyServer=null
            }
        }
    }
    private suspend fun acceptConnections(listener:java.net.ServerSocket,nearby:Boolean) {
        while(currentCoroutineContext().isActive && !listener.isClosed) {
            val accepted=try {listener.accept()}catch(e:java.io.IOException){if(listener.isClosed)return else throw e}
            scope.launch {
                var ownsClaim=false;var handedOff=false;var tracked:java.net.Socket?=null
                fun takeClaim():Boolean=claim().also {got ->
                    if(got){ownsClaim=true;nearTask=nearby;pendingNearby=false;connectionTimeout?.cancel();tracked=accepted;connectingSocket=accepted}
                }
                try {
                    val linked=if(nearby)TransferConnection.acceptNearby({TransferTls.context()},accepted,
                        offer={remote ->nearbyOffer(remote.id)},claim=::takeClaim,track={tracked=it;connectingSocket=it})
                    else {
                        if(!takeClaim())return@launch
                        TransferConnection.NearbyAccepted(TransferConnection.accept(TransferTls.context(),accepted,track={tracked=it;connectingSocket=it}),NearbyOffer("","",false))
                    }
                    if(linked==null)return@launch
                    change(TransferPhase.CONNECTING,"正在建立加密连接")
                    val endpoint=if(nearby)TransferPeer(linked.remote.id,linked.remote.name,routes=listOf(TransferRoute(accepted.inetAddress.hostAddress,NearbyHandshake.PORT,nearby=true)),nearbyProtocol=NearbyHandshake.VERSION)else null
                    handedOff=true
                    runSocket(linked.socket,true,endpoint)
                } catch(e:Exception){if(ownsClaim && e !is CancellationException)fail(e)}
                finally {
                    runCatching {accepted.close()};runCatching {tracked?.close()}
                    if(connectingSocket===tracked)connectingSocket=null
                    if(ownsClaim && !handedOff){claimed=false;stopServiceIfIdle()}
                }
            }
        }
    }
    @Synchronized private fun claim():Boolean {
        if(claimed || state.value.phase in setOf(TransferPhase.PREPARING,TransferPhase.IMPORTING) ||
            outgoing==null && state.value.phase in setOf(TransferPhase.VERIFYING,TransferPhase.RECEIVED))return false
        claimed=true; return true
    }
    private fun stopIdle(disconnectNearby:Boolean=false) {
        if(claimed || state.value.busy) return
        serverJob?.cancel();runCatching {server?.close()};serverJob=null
        nearbyServerJob?.cancel();runCatching {nearbyServer?.close()};nearbyServerJob=null
        directJob?.cancel();runCatching {nearbyProbeSocket?.close()}
        scope.launch(Dispatchers.Main.immediate) {discovery.stop(disconnectNearby)}
    }
    fun startSend(peer:TransferPeer,selection:ExportSelection) {
        if(state.value.busy || task?.isActive==true) return
        receiveMode=ImportMode.MERGE
        mutableState.value=TransferSessionState(TransferPhase.PREPARING,peer,TransferProgress(TransferPhase.PREPARING))
        task=scope.launch {
            try {
                startService()
                val sessionId=UUID.randomUUID().toString()
                val target=File(directory,"$sessionId.ciallo")
                backup.exportArchive(target,selection) { p -> mutableState.update { it.copy(progress=p) } }
                val preview=backup.inspectArchive(target)
                if(preview.warnings.isNotEmpty()) {
                    localDecision=CompletableDeferred()
                    mutableState.update { it.copy(phase=TransferPhase.AWAITING_CONFIRMATION,preview=preview,message="部分资源无法带走，请查看提示后确认继续") }
                    require(withTimeout(120_000) { localDecision!!.await() }) { "已取消发送" }; localDecision=null
                }
                val token=ByteArray(32).also { SecureRandom().nextBytes(it) }.hex()
                val session=JSONObject().put("sessionId",sessionId).put("token",token).put("file",target.absolutePath)
                    .put("size",target.length()).put("sha256",fileHash(target)).put("createdAt",System.currentTimeMillis())
                    .put("peerId",peer.id).put("peerName",peer.name).put("host",peer.host?:"").put("port",peer.port).put("p2pAddress",peer.p2pAddress?:"")
                    .put("nearbyProtocol",peer.nearbyProtocol).put("directPort",peer.directPort?:NearbyHandshake.PORT)
                outgoing=session; save(File(directory,"outgoing.json"),session)
                connectOutgoing(peer)
            } catch(e:Exception) { if(e !is CancellationException) fail(e) }
            finally { stopServiceIfIdle() }
        }
    }
    fun retry(useNearby:Boolean=false) {
        val session=outgoing
        if(session==null) { scan();change(TransferPhase.DISCOVERING,"已准备接收，请在发送设备继续传输");return }
        if(state.value.busy || claimed || task?.isActive==true) return
        task=scope.launch { try { startService(); connectOutgoing(peer(session).let { if(useNearby)it.copy(host=null,routes=emptyList()) else it }) }
            catch(e:Exception) { if(e !is CancellationException) fail(e) } finally { stopServiceIfIdle() } }
    }
    private suspend fun connectOutgoing(peer:TransferPeer) {
        change(TransferPhase.CONNECTING,"正在建立加密连接",peer)
        if(peer.host==null) {
            try {withContext(Dispatchers.Main){
                pendingNearby=true;nearbyGroupSeen=false
                nearbyTimeout(35_000,"附近网络未建立，连接请求已超时。可重新连接")
                discovery.connect(peer)
            }}catch(e:Exception){nearbyFailure(e.message?:"附近连接启动失败")}
            return
        }
        nearTask=false;pendingNearby=false
        var last:Exception?=null
        for(attempt in 0..3) {
            var established=false
            try { connectSocket(peers.value.firstOrNull {it.id==peer.id}?:peer) {established=true}; return }
            catch(e:Exception) {
                if(e is CancellationException)throw e
                last=e
                if(e is RejectedException || e is SecurityException || e is IllegalArgumentException)throw e
                // Initial dial already tries the discovered routes. Do not spend minutes redialling a dead address.
                if(!established)break
                if(attempt<3) { change(TransferPhase.WAITING_TO_RESUME,"连接中断，正在尝试继续（${attempt+1}/3）",peer); delay((attempt+1)*1000L) }
            }
        }
        change(TransferPhase.WAITING_TO_RESUME,"${last?.message?:"连接中断"}。可继续传输${if(peer.p2pAddress!=null)"或改用附近直连" else ""}",peer)
    }
    private suspend fun connectSocket(peer:TransferPeer,onEstablished:()->Unit={}) {
        check(claim()) { "当前已有连接" }
        var handedOff=false;var tracked:java.net.Socket?=null
        try {
            val deadline=android.os.SystemClock.elapsedRealtime()+18_000
            var last:Exception?=null
            val connectivity=context.getSystemService(android.net.ConnectivityManager::class.java)
            val tls=TransferTls.context()
            for(route in connectionRoutes(peer)) {
                currentCoroutineContext().ensureActive()
                val remaining=(deadline-android.os.SystemClock.elapsedRealtime()).toInt()
                if(remaining<=0)break
                var authenticated=false
                try {
                    val address=java.net.InetAddress.getByName(route.host)
                    val network=if(route.nearby)null else (connectivity.allNetworks.firstOrNull {it.networkHandle==route.networkHandle}
                        ?:connectivity.allNetworks.firstOrNull { candidate ->
                            connectivity.getNetworkCapabilities(candidate)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)==true &&
                                connectivity.getLinkProperties(candidate)?.linkAddresses?.any {link ->sameSubnet(address.address,link.address.address,link.prefixLength)}==true
                        })
                    val connection=TransferConnection.connect(tls,route,bind={raw ->network?.bindSocket(raw);route.localAddress?.let {raw.bind(InetSocketAddress(it,0))}},
                        track={tracked=it;connectingSocket=it},stage={message ->change(TransferPhase.CONNECTING,message,peer)},
                        connectTimeout=minOf(4_000,remaining),handshakeTimeout=minOf(8_000,(remaining-4_000).coerceAtLeast(1_000)))
                    onEstablished()
                    authenticated=true;handedOff=true
                    runSocket(connection,false,peer)
                    return
                } catch(e:Exception) {
                    if(e is CancellationException || e is RejectedException || e is SecurityException || state.value.phase==TransferPhase.CANCELLED)throw e
                    last=e
                    // Once authenticated, only the session resume policy may open another connection.
                    if(authenticated)throw e
                }
            }
            throw last?:java.io.IOException("设备地址已失效，请重新发现设备")
        }
        finally {runCatching {tracked?.close()};if(connectingSocket===tracked)connectingSocket=null;if(!handedOff)claimed=false}
    }
    private class RejectedException(message:String):Exception(message)
    private suspend fun runSocket(connection:SSLSocket,accepted:Boolean,endpoint:TransferPeer?=null) {
        socket=connection
        connectionTimeout?.cancel()
        var controlOutput:DataOutputStream?=null
        try {
            TransferTls.configure(connection); connection.soTimeout=8_000
            val input=DataInputStream(connection.inputStream.buffered(CHUNK_SIZE+4096)); val output=DataOutputStream(connection.outputStream.buffered(CHUNK_SIZE+4096))
            controlOutput=output
            val nonce=ByteArray(24).also { SecureRandom().nextBytes(it) }.hex()
            val sending=outgoing!=null && state.value.phase !in setOf(TransferPhase.COMPLETED,TransferPhase.CANCELLED)
            val hello=JSONObject().put("type","hello").put("v",1).put("id",id).put("name",deviceName).put("nonce",nonce).put("sending",sending)
            if(sending) hello.put("sessionId",outgoing!!.getString("sessionId")).put("token",outgoing!!.getString("token"))
            send(output,hello)
            val remote=read(input); require(remote.getString("type")=="hello" && remote.getInt("v")==1)
            require(remote.getString("nonce").matches(Regex("[a-f0-9]{48}")))
            if(sending==remote.getBoolean("sending"))throw RejectedException("双方都在发送或都在等待，请让一台设备选择发送")
            if(endpoint?.routes?.any {it.nearby}==true && endpoint.id!=remote.getString("id"))throw SecurityException("附近握手设备身份不一致")
            val remotePeer=endpoint?.copy(id=remote.getString("id"),name=remote.getString("name").take(64))?:TransferPeer(remote.getString("id"),remote.getString("name").take(64))
            if(sending && outgoing!!.optString("peerFingerprint").isEmpty() && outgoing!!.optString("peerId")!=remote.getString("id"))
                throw SecurityException("连接的设备与所选设备不一致，请重新发现")
            val fingerprint=TransferTls.peerFingerprint(connection)
            val session=if(sending)outgoing!! else {
                val sessionId=remote.getString("sessionId"); require(sessionId.matches(Regex("[a-f0-9-]{36}")))
                val token=remote.getString("token"); require(token.matches(Regex("[a-f0-9]{64}")))
                val persisted=File(directory,"incoming.json").takeIf { it.isFile }?.let(::read)
                if(persisted!=null && !persisted.has("result") && persisted.getString("sessionId")!=sessionId && System.currentTimeMillis()-persisted.getLong("createdAt")<TRANSFER_RETENTION)
                    throw RejectedException("接收设备有未完成任务，请先继续或取消该任务")
                if(persisted?.optString("sessionId")==sessionId) {
                    require(persisted.getString("token")==token && persisted.getString("peerFingerprint")==fingerprint) { "续传身份不匹配" }; persisted
                } else JSONObject().put("sessionId",sessionId).put("token",token).put("createdAt",System.currentTimeMillis())
            }
            val locallyResuming=session.optString("peerFingerprint").isNotEmpty()
            if(locallyResuming) require(session.getString("peerFingerprint")==fingerprint) { "设备身份已改变，请取消后重新发送" }
            send(output,JSONObject().put("type","pairing_state").put("resume",locallyResuming))
            val remotePairing=read(input);require(remotePairing.optString("type")=="pairing_state")
            val resuming=locallyResuming && remotePairing.getBoolean("resume")
            val code=TransferTls.code(connection,nonce,remote.getString("nonce"))
            if(!resuming) {
                localDecision=CompletableDeferred()
                mutableState.value=TransferSessionState(TransferPhase.AWAITING_CONFIRMATION,remotePeer,
                    TransferProgress(TransferPhase.AWAITING_CONFIRMATION),code,incoming=!sending,
                    message=if(sending)"核对两台设备上的校验码，确认发送" else "核对校验码，选择接收方式")
                // No user payload or manifest is sent before both parties authenticate the handshake.
                connection.soTimeout=125_000
                val consent=withTimeoutOrNull(120_000) { localDecision!!.await() }?:false
                send(output,JSONObject().put("type","confirm").put("accepted",consent))
                val response=read(input)
                if(!consent || response.optString("type")!="confirm" || !response.optBoolean("accepted")) throw RejectedException("对方拒绝或确认已超时")
                session.put("peerFingerprint",fingerprint)
                if(!sending)session.put("mode",receiveMode.name)
                if(sending)save(File(directory,"outgoing.json"),session)
            } else {
                send(output,JSONObject().put("type","resume").put("token",session.getString("token")))
                val response=read(input); require(response.optString("type")=="resume" && response.getString("token")==session.getString("token"))
            }
            localDecision=null; connection.soTimeout=30_000
            if(!sending)receiveMode=ImportMode.valueOf(session.optString("mode",ImportMode.MERGE.name))
            if(sending) sendFile(connection,input,output,session,remotePeer) else receiveFile(connection,input,output,session,remotePeer)
        } catch(e:Exception) {
            if(state.value.phase==TransferPhase.COMPLETED && state.value.incoming && e is java.io.IOException)return
            controlOutput?.let { output -> runCatching { send(output,JSONObject().put("type","error").put("message",e.message?.take(512)?:"传输未完成")) } }
            if(accepted && e !is CancellationException) fail(e)
            else throw e
        } finally {
            localDecision?.cancel(); localDecision=null; importDecision?.cancel(); importDecision=null
            runCatching { connection.close() }; if(socket===connection)socket=null; claimed=false
            stopServiceIfIdle()
        }
    }
    private suspend fun sendFile(connection:SSLSocket,input:DataInputStream,output:DataOutputStream,session:JSONObject,peer:TransferPeer) {
        val file=File(session.getString("file")); require(file.isFile && file.length()==session.getLong("size") && fileHash(file)==session.getString("sha256")) { "发送数据已改变，请重新准备" }
        send(output,JSONObject().put("type","offer").put("size",file.length()).put("sha256",session.getString("sha256")))
        val ready=read(input)
        if(ready.optString("type")=="imported") { completeSender(ready); return }
        if(ready.optString("type")=="received") { mutableState.update { it.copy(peerReceived=true) }; awaitImport(connection,input); return }
        require(ready.getString("type")=="ready")
        val offset=ready.getLong("offset"); require(offset in 0..file.length() && (offset%CHUNK_SIZE==0L || offset==file.length()))
        change(TransferPhase.TRANSFERRING,"正在发送给 ${peer.name}",peer)
        RandomAccessFile(file,"r").use { data -> data.seek(offset); val buffer=ByteArray(CHUNK_SIZE); var sent=offset; var mark=offset; var markTime=System.nanoTime()
            while(sent<file.length()) {
                currentCoroutineContext().ensureActive(); val n=data.read(buffer,0,minOf(CHUNK_SIZE.toLong(),file.length()-sent).toInt()); check(n>0)
                send(output,JSONObject().put("type","chunk").put("offset",sent).put("length",n).put("sha256",sha256(buffer.copyOf(n))))
                output.write(buffer,0,n); output.flush()
                val ack=read(input); require(ack.getString("type")=="ack" && ack.getLong("offset")==sent+n) { "传输确认异常" }
                sent+=n
                val now=System.nanoTime(); val speed=if(now>markTime)((sent-mark)*1_000_000_000L/(now-markTime))else 0
                mutableState.update { it.copy(progress=TransferProgress(TransferPhase.TRANSFERRING,sent,file.length(),speed)) }
                if(now-markTime>1_000_000_000L) { mark=sent; markTime=now }
            }
        }
        send(output,JSONObject().put("type","end"))
        change(TransferPhase.VERIFYING,"等待对方校验数据")
        // Large offline libraries can take longer than a chunk timeout to hash and unpack.
        connection.soTimeout=15*60*1000
        val received=read(input); require(received.getString("type")=="received")
        mutableState.update { it.copy(peerReceived=true) }
        awaitImport(connection,input)
    }
    private suspend fun awaitImport(connection:SSLSocket,input:DataInputStream) {
        change(TransferPhase.RECEIVED,"对方已收到，等待导入结果")
        connection.soTimeout=15*60*1000
        val result=read(input)
        if(result.optString("type")=="import_failed")throw IllegalStateException("对方已收到，但导入未完成：${result.optString("message")}")
        require(result.optString("type")=="imported")
        completeSender(result)
    }
    private fun completeSender(result:JSONObject) {
        mutableState.update { it.copy(phase=TransferPhase.COMPLETED,peerReceived=true,peerImported=true,
            progress=it.progress.copy(phase=TransferPhase.COMPLETED),message="对方已完成导入",
            result=ImportResult(result.optInt("added"),result.optInt("merged"),result.optInt("skipped"))) }
        outgoing?.optString("file")?.let { File(it).delete() }; File(directory,"outgoing.json").delete(); outgoing=null
        history("发送完成",state.value.peer?.name?:"设备")
    }
    private suspend fun receiveFile(connection:SSLSocket,input:DataInputStream,output:DataOutputStream,session:JSONObject,peer:TransferPeer) {
        val offer=read(input); require(offer.getString("type")=="offer")
        val size=offer.getLong("size"); val hash=offer.getString("sha256")
        require(size in 1..MAX_ARCHIVE_BYTES && hash.matches(Regex("[a-f0-9]{64}")))
        if(session.has("size")) require(session.getLong("size")==size && session.getString("sha256")==hash) { "续传数据已改变" }
        session.put("size",size).put("sha256",hash).put("peerName",peer.name)
        session.optJSONObject("result")?.let { send(output,it); return }
        val file=File(directory,"${session.getString("sessionId")}.incoming")
        val offset=session.optLong("offset",0)
        require(offset in 0..size && (offset%CHUNK_SIZE==0L || offset==size))
        if(offset>0) require(file.isFile && file.length()>=offset) { "续传缓存缺失，请重新发送" }
        require(directory.usableSpace>size-offset+16L*1024*1024) { "接收设备存储空间不足" }
        save(File(directory,"incoming.json"),session)
        startService()
        send(output,JSONObject().put("type","ready").put("offset",offset))
        mutableState.update { it.copy(phase=TransferPhase.TRANSFERRING,incoming=true,peer=peer,message="正在接收数据") }
        RandomAccessFile(file,"rw").use { data -> data.setLength(offset); data.seek(offset); var received=offset; val buffer=ByteArray(CHUNK_SIZE); var mark=offset; var markTime=System.nanoTime()
            while(received<size) {
                currentCoroutineContext().ensureActive()
                val chunk=read(input); require(chunk.getString("type")=="chunk" && chunk.getLong("offset")==received)
                val length=chunk.getInt("length"); require(length==minOf(CHUNK_SIZE.toLong(),size-received).toInt())
                input.readFully(buffer,0,length); TransferWire.verifyChunk(chunk,received,size,buffer,length)
                data.write(buffer,0,length); data.fd.sync(); received+=length
                session.put("offset",received); save(File(directory,"incoming.json"),session)
                send(output,JSONObject().put("type","ack").put("offset",received))
                val now=System.nanoTime(); val speed=if(now>markTime)((received-mark)*1_000_000_000L/(now-markTime))else 0
                mutableState.update { it.copy(progress=TransferProgress(TransferPhase.TRANSFERRING,received,size,speed)) }
                if(now-markTime>1_000_000_000L) { mark=received; markTime=now }
            }
        }
        require(read(input).getString("type")=="end")
        change(TransferPhase.VERIFYING,"校验完整数据包")
        require(fileHash(file)==hash) { "完整文件校验失败，请重新发送" }
        val preview=backup.inspectArchive(file)
        send(output,JSONObject().put("type","received"))
        mutableState.update { it.copy(phase=TransferPhase.RECEIVED,preview=preview,message="接收完成，准备导入") }
        try {
            if(preview.containsProtected && !PrivacyManager(context).isEnabled()) {
                importDecision=CompletableDeferred()
                mutableState.update { it.copy(message="包含受保护内容，请先设置本机隐私 PIN，再继续导入") }
                receiveMode=importDecision!!.await()
            }
            change(TransferPhase.IMPORTING,"正在导入已接收的数据")
            val result=backup.importArchive(file,receiveMode) { p -> mutableState.update { it.copy(phase=p.phase,progress=p) } }
            val receipt=JSONObject().put("type","imported").put("added",result.added).put("merged",result.merged).put("skipped",result.skipped)
            session.put("result",receipt); save(File(directory,"incoming.json"),session)
            // Persist success before notifying peer, so a lost final acknowledgement is safely replayable.
            mutableState.update { it.copy(phase=TransferPhase.COMPLETED,result=result,message=if(receiveMode==ImportMode.REPLACE)"迁移完成，重新打开后应用所有设置" else "数据已合并到本机") }
            file.delete(); history("接收完成",peer.name); send(output,receipt)
        } catch(e:Exception) { runCatching { send(output,JSONObject().put("type","import_failed").put("message",e.message?:"导入失败")) }; throw e }
    }
    fun confirm(accepted:Boolean,mode:ImportMode=ImportMode.MERGE) {
        receiveMode=mode
        if(accepted && state.value.incoming) runCatching { startService() }.onFailure { fail(it); localDecision?.complete(false); return }
        localDecision?.complete(accepted)
    }
    fun continueProtectedImport(mode:ImportMode=receiveMode) { importDecision?.complete(mode) }
    fun cancel() {
        if(state.value.phase==TransferPhase.IMPORTING) return
        localDecision?.complete(false); importDecision?.cancel(); task?.cancel()
        directJob?.cancel();connectionTimeout?.cancel();runCatching {connectingSocket?.close()};runCatching {nearbyProbeSocket?.close()}
        if(pendingNearby || nearTask)scope.launch(Dispatchers.Main.immediate){discovery.cancelConnection()}
        pendingNearby=false;nearTask=false
        runCatching { socket?.close() }; socket=null; claimed=false
        outgoing?.optString("file")?.let { File(it).delete() }; outgoing=null
        File(directory,"outgoing.json").delete()
        File(directory,"incoming.json").takeIf { it.isFile }?.let { runCatching { read(it).getString("sessionId") }.getOrNull()?.let { id -> if(id.matches(Regex("[a-f0-9-]{36}")))File(directory,"$id.incoming").delete() }; it.delete() }
        mutableState.value=TransferSessionState(TransferPhase.CANCELLED,message="传输已取消，本机原有数据保留")
        stopServiceIfIdle()
    }
    fun openShared(uri:Uri) {
        if(state.value.busy || claimed) { show(); return }
        show()
        task=scope.launch { try {
            change(TransferPhase.VERIFYING,"正在识别分享的数据")
            val target=File(directory,"shared_${UUID.randomUUID()}.ciallo")
            context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { output -> com.example.data.ArchiveBudget(MAX_ARCHIVE_BYTES,MAX_ARCHIVE_BYTES,1).copyEntry(input,output) } }?:error("无法打开分享文件")
            val preview=backup.inspectArchive(target)
            importDecision=CompletableDeferred()
            mutableState.value=TransferSessionState(TransferPhase.RECEIVED,incoming=true,preview=preview,message="已识别 Ciallo 数据，请选择合并或整机迁移")
            val mode=importDecision!!.await(); startService()
            val result=backup.importArchive(target,mode) { p -> mutableState.update { it.copy(phase=p.phase,progress=p) } }
            receiveMode=mode
            mutableState.update { it.copy(phase=TransferPhase.COMPLETED,result=result,message=if(mode==ImportMode.REPLACE)"迁移完成，重新打开后应用设置" else "数据已合并到本机") }
            target.delete(); history("文件导入完成","分享文件")
        } catch(e:Exception) { if(e !is CancellationException)fail(e) } finally { importDecision=null; stopServiceIfIdle() } }
    }
    fun importShared(mode:ImportMode) { importDecision?.complete(mode) }
    fun reset() { if(!state.value.busy && !claimed) mutableState.value=TransferSessionState() }
    fun needsRestart():Boolean=receiveMode==ImportMode.REPLACE && state.value.phase==TransferPhase.COMPLETED && state.value.incoming
    fun history():List<String> = prefs.getString("history","").orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
    private fun history(action:String,peer:String) { val text="${java.text.SimpleDateFormat("MM-dd HH:mm",java.util.Locale.CHINA).format(java.util.Date())}  $action · $peer"
        prefs.edit().putString("history",(listOf(text)+history()).take(20).joinToString("\n")).apply() }
    private fun change(phase:TransferPhase,message:String,peer:TransferPeer?=state.value.peer) { mutableState.update { it.copy(phase=phase,message=message,peer=peer,progress=TransferProgress(phase)) } }
    private fun fail(e:Throwable) {
        if(state.value.phase==TransferPhase.CANCELLED || e is CancellationException)return
        val resumable=outgoing!=null || File(directory,"incoming.json").exists()
        val phase=if(resumable && e is java.io.IOException)TransferPhase.WAITING_TO_RESUME else TransferPhase.FAILED
        val error=when { e is SecurityException -> TransferError.PERMISSION; e is RejectedException -> TransferError.REJECTED
            e.message?.contains("空间")==true -> TransferError.SPACE; e.message?.contains("校验")==true -> TransferError.CORRUPT
            e.message?.contains("版本")==true -> TransferError.VERSION; e is java.io.IOException -> TransferError.CONNECTION; else -> TransferError.UNKNOWN }
        mutableState.update { it.copy(phase=phase,error=error,message=e.message?:"传输未完成，请重试",verificationCode=null) }
    }
    private fun peer(session:JSONObject)=peers.value.firstOrNull {it.id==session.optString("peerId")}
        ?:TransferPeer(session.getString("peerId"),session.getString("peerName"),session.optString("host").takeIf {it.isNotEmpty()},session.getInt("port"),session.optString("p2pAddress").takeIf {it.isNotEmpty()},
            directPort=session.optInt("directPort",NearbyHandshake.PORT),nearbyProtocol=session.optInt("nearbyProtocol",0))
    private fun cleanup() { val cutoff=System.currentTimeMillis()-TRANSFER_RETENTION
        directory.listFiles()?.filter { it.lastModified()<cutoff }?.forEach { it.delete() }
        File(context.cacheDir,"share_temp").listFiles()?.filter { it.extension=="ciallo" && it.lastModified()<cutoff }?.forEach { it.delete() }
    }
    private fun startService() { val intent=Intent(context,TransferService::class.java)
        if(Build.VERSION.SDK_INT>=26)context.startForegroundService(intent)else context.startService(intent) }
    private fun stopServiceIfIdle() { if(!state.value.busy && state.value.phase!=TransferPhase.RECEIVED && !claimed) {
        context.stopService(Intent(context,TransferService::class.java)); if(!foreground)stopIdle()
    } }
    private fun save(file:File,value:JSONObject) { val atomic=AtomicFile(file); val stream=atomic.startWrite()
        try { stream.write(value.toString().toByteArray()); atomic.finishWrite(stream) } catch(e:Exception) { atomic.failWrite(stream); throw e } }
    private fun read(file:File)=JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
    private fun send(output:DataOutputStream,value:JSONObject)=TransferWire.write(output,value)
    private fun read(input:DataInputStream):JSONObject=TransferWire.read(input).also {
        if(it.optString("type")=="error")throw RejectedException(it.optString("message","对方无法继续传输"))
    }
    private fun sameSubnet(a:ByteArray,b:ByteArray,prefix:Int):Boolean {
        if(a.size!=b.size)return false
        for(bit in 0 until prefix.coerceAtMost(a.size*8))if((a[bit/8].toInt() and (128 shr (bit%8)))!=(b[bit/8].toInt() and (128 shr (bit%8))))return false
        return true
    }
}
