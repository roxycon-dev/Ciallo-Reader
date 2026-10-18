package com.example.transfer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

internal class TransferDiscovery(private val context: Context,private val id:String,private val name:String,
    private val onP2pConnected:(Boolean,String,Int)->Unit) {
    companion object { const val SERVICE="_ciallo-xfer._tcp."; const val PORT=38842
        fun permissions(): Array<String> = permissionsFor(Build.VERSION.SDK_INT)
        internal fun permissionsFor(sdk:Int):Array<String> = if(sdk>=33)arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)
        fun allowed(context: Context)=permissions().all { ContextCompat.checkSelfPermission(context,it)==PackageManager.PERMISSION_GRANTED }
    }
    private val nsd=context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val p2p=context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel:WifiP2pManager.Channel?=null
    private val peerMap=ConcurrentHashMap<String,TransferPeer>()
    private val mutablePeers=MutableStateFlow<List<TransferPeer>>(emptyList()); val peers=mutablePeers.asStateFlow()
    private val mutableMessage=MutableStateFlow(""); val message=mutableMessage.asStateFlow()
    private var registration:NsdManager.RegistrationListener?=null
    private var discovery:NsdManager.DiscoveryListener?=null
    private var receiver:BroadcastReceiver?=null
    private var selectedPeer:TransferPeer?=null
    private var localPort=PORT
    private val resolutionQueue=java.util.concurrent.ConcurrentLinkedQueue<NsdServiceInfo>()
    private val resolving=java.util.concurrent.atomic.AtomicBoolean(false)
    private val main=android.os.Handler(Looper.getMainLooper())
    private fun put(peer:TransferPeer) {
        if(peer.id==id || peer.protocol!=1) return
        if(peerMap.size>=128 && !peerMap.containsKey(peer.id))return
        peerMap.compute(peer.id) { _,old -> mergePeer(old,peer) }
        mutablePeers.value=peerMap.values.sortedBy { it.name }
    }
    fun start(port:Int) {
        if(registration!=null && localPort==port) return
        registration?.let {runCatching {nsd.unregisterService(it)}}
        registration=null
        val portChanged=localPort!=port
        localPort=port
        val listener=object:NsdManager.RegistrationListener {
            override fun onServiceRegistered(info:NsdServiceInfo) {}
            override fun onRegistrationFailed(info:NsdServiceInfo,code:Int) { mutableMessage.value="局域网广播失败，可使用附近直连或分享文件" }
            override fun onServiceUnregistered(info:NsdServiceInfo) {}
            override fun onUnregistrationFailed(info:NsdServiceInfo,code:Int) {}
        }
        registration=listener
        runCatching { nsd.registerService(NsdServiceInfo().apply { serviceName="Ciallo-${id.take(8)}"; serviceType=SERVICE; setPort(port); setAttribute("id",id); setAttribute("name",name); setAttribute("v","1") },NsdManager.PROTOCOL_DNS_SD,listener) }
            .onFailure { mutableMessage.value="局域网广播不可用：${it.message}" }
        if(!allowed(context)) { mutableMessage.value="授权附近设备后，还可启用离线直连"; return }
        if(portChanged && channel!=null) {
            val ch=channel!!
            p2p?.clearLocalServices(ch,object:WifiP2pManager.ActionListener {
                override fun onSuccess(){advertiseDirect(ch)}
                override fun onFailure(reason:Int){mutableMessage.value="附近广播更新失败，请重新开启接收"}
            })
        } else startP2p()
    }
    fun scan() {
        if(discovery==null) {
            val listener=object:NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type:String) {}
                override fun onDiscoveryStopped(type:String) {}
                override fun onStartDiscoveryFailed(type:String,code:Int) { mutableMessage.value="未能开始局域网发现，请重试或使用附近直连"; discovery=null }
                override fun onStopDiscoveryFailed(type:String,code:Int) {}
                override fun onServiceLost(info:NsdServiceInfo) {
                    val lost=peerMap.values.filter { "Ciallo-${it.id.take(8)}"==info.serviceName }
                    lost.forEach { peer -> if(peer.p2pAddress==null) peerMap.remove(peer.id) else peerMap[peer.id]=peer.copy(host=null,routes=emptyList()) }
                    mutablePeers.value=peerMap.values.toList()
                }
                @Suppress("DEPRECATION") override fun onServiceFound(info:NsdServiceInfo) {
                    if(!info.serviceName.startsWith("Ciallo-") || info.serviceName=="Ciallo-${id.take(8)}") return
                    if(resolutionQueue.size<64){resolutionQueue.add(info);resolveNext()}
                }
            }
            discovery=listener
            runCatching { nsd.discoverServices(SERVICE,NsdManager.PROTOCOL_DNS_SD,listener) }.onFailure { discovery=null; mutableMessage.value="局域网发现不可用" }
        }
        if(allowed(context)) {
            if(channel==null) startP2p()
            channel?.let { p2p?.discoverServices(it,action("附近发现")) }
        }
    }
    @Suppress("DEPRECATION") private fun resolveNext() {
        if(!resolving.compareAndSet(false,true))return
        val info=resolutionQueue.poll()
        if(info==null){resolving.set(false);return}
        fun next(){resolving.set(false);main.post {if(discovery!=null)resolveNext()}}
        try {nsd.resolveService(info,object:NsdManager.ResolveListener {
            override fun onResolveFailed(info:NsdServiceInfo,code:Int){next()}
            override fun onServiceResolved(info:NsdServiceInfo){
                try {
                    fun attr(key:String)=info.attributes[key]?.toString(Charsets.UTF_8)
                    val peerId=attr("id")
                    val addresses=(if(Build.VERSION.SDK_INT>=34)info.hostAddresses else listOfNotNull(info.host))
                        .filterNot {it.isAnyLocalAddress || it.isLoopbackAddress || it.isMulticastAddress}
                        .sortedBy {if(it is java.net.Inet4Address)0 else 1}
                    val network=if(Build.VERSION.SDK_INT>=33)info.network?.networkHandle else null
                    val routes=addresses.mapNotNull { it.hostAddress?.let {host -> TransferRoute(host,info.port,network)} }
                    if(discovery!=null && peerId!=null && routes.isNotEmpty() && info.port in 1..65535)
                        put(TransferPeer(peerId,(attr("name")?:"Android 设备").take(64),routes.first().host,info.port,protocol=attr("v")?.toIntOrNull()?:0,routes=routes))
                }finally{next()}
            }
        })}catch(_:Exception){next()}
    }
    private fun action(operation:String)=object:WifiP2pManager.ActionListener {
        override fun onSuccess() {}
        override fun onFailure(reason:Int) { mutableMessage.value=when(reason) { WifiP2pManager.P2P_UNSUPPORTED -> "设备不支持 Wi-Fi Direct，请连接同一 Wi-Fi 或热点"; WifiP2pManager.BUSY -> "Wi-Fi 正忙，请稍后重试"; else -> "${operation}未成功，请检查 Wi-Fi、附近设备权限和位置开关" } }
    }
    private fun startP2p() {
        if(p2p==null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) { mutableMessage.value="设备不支持附近直连，可使用同 Wi-Fi 互传"; return }
        if(channel!=null || !allowed(context)) return
        val ch=p2p.initialize(context,Looper.getMainLooper()) { channel=null; mutableMessage.value="附近直连已断开，请重新进入互传" }; channel=ch
        p2p.setDnsSdResponseListeners(ch,{_,_,_->},{_,record,device ->
            if(record["app"]=="ciallo" && record["id"]!=null) put(TransferPeer(record.getValue("id"),(record["name"]?:"Android 设备").take(64),port=record["port"]?.toIntOrNull()?:PORT,p2pAddress=device.deviceAddress,protocol=record["v"]?.toIntOrNull()?:0,directPort=record["port"]?.toIntOrNull()?:PORT))
        })
        advertiseDirect(ch)
        p2p.addServiceRequest(ch,WifiP2pDnsSdServiceRequest.newInstance(),action("附近发现"))
        receiver=object:BroadcastReceiver() {
            override fun onReceive(c:Context,intent:Intent) {
                if(intent.action==WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION && allowed(context)) p2p.requestConnectionInfo(ch) { info ->
                    if(info.groupFormed && info.groupOwnerAddress!=null) {
                        if(info.isGroupOwner)onP2pConnected(true,info.groupOwnerAddress.hostAddress!!,localPort)
                        else p2p.requestGroupInfo(ch) { group ->
                            val owner=peerMap.values.firstOrNull {it.p2pAddress.equals(group?.owner?.deviceAddress,ignoreCase=true) && it.p2pAddress!=null}
                            val remote=owner?:selectedPeer
                            onP2pConnected(false,info.groupOwnerAddress.hostAddress!!,remote?.directPort?:remote?.port?.takeIf {it>0}?:PORT)
                        }
                    }
                }
                if(intent.action==WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION && intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE,-1)!=WifiP2pManager.WIFI_P2P_STATE_ENABLED) mutableMessage.value="请打开 Wi-Fi 后重试附近互传"
            }
        }
        ContextCompat.registerReceiver(context,receiver,IntentFilter().apply { addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION); addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION) },ContextCompat.RECEIVER_EXPORTED)
    }
    fun connect(peer:TransferPeer) {
        require(allowed(context)) { "请先授权附近设备" }
        val address=peer.p2pAddress?:error("设备不支持附近直连，请连接同一 Wi-Fi")
        selectedPeer=peer
        val ch=channel?:error("附近直连尚未就绪")
        p2p?.connect(ch,WifiP2pConfig().apply { deviceAddress=address; groupOwnerIntent=0 },action("连接设备"))
    }
    fun stopScanning() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }; discovery=null
        resolutionQueue.clear()
        channel?.let { runCatching { p2p?.stopPeerDiscovery(it,null) } }
    }
    private fun advertiseDirect(ch:WifiP2pManager.Channel) {
        p2p?.addLocalService(ch,WifiP2pDnsSdServiceInfo.newInstance("Ciallo-${id.take(8)}",SERVICE.trimEnd('.'),mapOf("app" to "ciallo","id" to id,"name" to name,"port" to localPort.toString(),"v" to "1")),action("设备广播"))
    }
    fun stop(keepConnection:Boolean=false) {
        stopScanning()
        registration?.let { runCatching { nsd.unregisterService(it) } }; registration=null
        if(!keepConnection) {
            channel?.let { ch -> runCatching { p2p?.clearLocalServices(ch,null); p2p?.clearServiceRequests(ch,null); p2p?.removeGroup(ch,null); if(Build.VERSION.SDK_INT>=27)ch.close() } }; channel=null
            receiver?.let { runCatching { context.unregisterReceiver(it) } }; receiver=null
            peerMap.clear(); mutablePeers.value=emptyList()
            selectedPeer=null
        }
    }
}
