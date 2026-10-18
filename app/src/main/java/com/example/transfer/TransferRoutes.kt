package com.example.transfer

internal fun mergePeer(old:TransferPeer?,fresh:TransferPeer):TransferPeer {
    val networks=fresh.routes.map {it.networkHandle}.toSet()
    val routes=(fresh.routes+old?.routes.orEmpty().filterNot {it.networkHandle in networks})
        .distinct().take(8)
    return fresh.copy(host=fresh.host?:old?.host,
        port=if(fresh.host!=null)fresh.port else old?.takeIf {it.host!=null}?.port?:fresh.port,
        p2pAddress=fresh.p2pAddress?:old?.p2pAddress,
        directPort=fresh.directPort?:old?.directPort,routes=routes,
        nearbyProtocol=if(fresh.p2pAddress!=null)fresh.nearbyProtocol else old?.nearbyProtocol?:fresh.nearbyProtocol)
}

internal fun connectionRoutes(peer:TransferPeer):List<TransferRoute> =
    (peer.routes+listOfNotNull(peer.host?.let {TransferRoute(it,peer.port)}))
        .filter {it.port in 1..65535}.distinctBy {it.host to it.port}
        .sortedBy {if(it.host.contains(':'))1 else 0}.take(4)
