package com.example.transfer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NearbyConnectorTest {
    private class Platform:NearbyPlatform {
        var value:NearbyGroup?=null
        var error:String?=null
        var silent=false
        var deferRemoval=false
        var saved:((NearbyGroup?)->Unit)?=null
        val events=mutableListOf<String>()
        override fun group(callback:(NearbyGroup?)->Unit){events.add("query");saved=callback;if(!silent)callback(value)}
        override fun pauseDiscovery(callback:()->Unit){events.add("pause");callback()}
        override fun connect(address:String,callback:(String?)->Unit){events.add("connect:$address");callback(error)}
        override fun removeGroup(callback:(String?)->Unit){events.add("remove");if(!deferRemoval)value=null;callback(null)}
        override fun cancelPending(){events.add("cancel")}
    }
    private val group=NearbyGroup(false,"192.168.49.1","192.168.49.2",setOf("peer"))
    @Test fun acceptedRequestWaitsForGroupAndPollsWithoutAnyBroadcast()=runTest {
        val p=Platform();val ready=mutableListOf<NearbyGroup>();val errors=mutableListOf<String>()
        val c=NearbyConnector(backgroundScope,p,ready::add,{},errors::add)
        c.connect("peer");runCurrent()
        assertTrue(ready.isEmpty());assertTrue(p.events.contains("connect:peer"))
        p.value=group
        advanceTimeBy(751);runCurrent()
        assertEquals(group,ready.first());assertTrue(errors.isEmpty());c.stop()
    }
    @Test fun registrationAndForegroundReturnReadAlreadyFormedGroup()=runTest {
        val p=Platform().apply {value=group};val ready=mutableListOf<NearbyGroup>()
        val c=NearbyConnector(backgroundScope,p,ready::add,{}, {fail(it)})
        c.observe();runCurrent();assertEquals(1,ready.size)
        c.stop();assertFalse(p.events.contains("remove"));assertFalse(p.events.contains("cancel"))
        c.observe();runCurrent();assertEquals(2,ready.size);c.stop()
    }
    @Test fun reuseConnectedPeerDoesNotWaitForNewConnectionBroadcast()=runTest {
        val p=Platform().apply {value=group};val ready=mutableListOf<NearbyGroup>()
        val c=NearbyConnector(backgroundScope,p,ready::add,{}, {fail(it)})
        c.connect("PEER");runCurrent()
        assertEquals(group,ready.first());assertFalse(p.events.any {it.startsWith("connect:")});assertFalse(p.events.contains("remove"));c.stop()
    }
    @Test fun anotherIdleGroupIsRemovedBeforeConnectingToSelectedPeer()=runTest {
        val p=Platform().apply {value=group.copy(members=setOf("other"))}
        val ready=mutableListOf<NearbyGroup>();val c=NearbyConnector(backgroundScope,p,ready::add,{}, {fail(it)})
        c.connect("peer");runCurrent()
        assertTrue(p.events.indexOf("remove")<p.events.indexOf("pause"))
        assertTrue(p.events.indexOf("pause")<p.events.indexOf("connect:peer"))
        p.value=group;advanceTimeBy(751);runCurrent();assertTrue(ready.isNotEmpty());c.stop()
    }
    @Test fun rejectedConnectReportsImmediatelyInsteadOfWaitingForTimeout()=runTest {
        val p=Platform().apply {error="system busy"};val errors=mutableListOf<String>()
        val c=NearbyConnector(backgroundScope,p,{fail("not connected")},{},errors::add)
        c.connect("peer");runCurrent();assertEquals(listOf("system busy"),errors)
        advanceTimeBy(40_000);runCurrent();assertEquals(1,errors.size)
    }
    @Test fun missingFrameworkCallbacksStillHaveAFiniteDeadline()=runTest {
        val p=Platform().apply {silent=true};val errors=mutableListOf<String>()
        val c=NearbyConnector(backgroundScope,p,{fail("not connected")},{},errors::add)
        c.connect("peer");runCurrent();advanceTimeBy(35_001);runCurrent()
        assertEquals(1,errors.size);assertTrue(errors.single().contains("附近网络未建立"));assertTrue(p.events.contains("cancel"))
    }
    @Test fun explicitCancellationDropsLateCallbacksAndDoesNotRestartOnStaleBroadcast()=runTest {
        val p=Platform().apply {silent=true};val ready=mutableListOf<NearbyGroup>();val errors=mutableListOf<String>()
        val c=NearbyConnector(backgroundScope,p,ready::add,{},errors::add)
        c.connect("peer");runCurrent();val late=p.saved!!
        c.stop(disconnect=true);late(group);c.refresh();advanceTimeBy(40_000);runCurrent()
        assertTrue(ready.isEmpty());assertTrue(errors.isEmpty());assertEquals(1,p.events.count {it=="query"})
        assertTrue(p.events.contains("cancel"));assertTrue(p.events.contains("remove"))
    }
    @Test fun cancellingATaskStillAllowsANewInvitationWithoutAnotherBroadcast()=runTest {
        val p=Platform().apply {value=group};val ready=mutableListOf<NearbyGroup>()
        val c=NearbyConnector(backgroundScope,p,ready::add,{}, {fail(it)})
        c.observe();runCurrent();assertEquals(1,ready.size)
        c.cancelConnection();runCurrent();assertNull(p.value)
        p.value=group.copy(host="192.168.50.1")
        advanceTimeBy(751);runCurrent()
        assertEquals("192.168.50.1",ready.last().host);c.stop()
    }
    @Test fun removalAcknowledgementDoesNotMeanTheOldGroupHasDisappeared()=runTest {
        val p=Platform().apply {value=group.copy(members=setOf("other"));deferRemoval=true}
        val c=NearbyConnector(backgroundScope,p,{}, {}, {fail(it)})
        c.connect("peer");runCurrent();advanceTimeBy(2_000);runCurrent()
        assertTrue(p.events.contains("remove"));assertFalse(p.events.contains("connect:peer"))
        p.value=null;advanceTimeBy(751);runCurrent()
        assertTrue(p.events.contains("connect:peer"));c.stop()
    }
    @Test fun cancellationDoesNotReconnectTheGroupWhileRemovalIsStillPending()=runTest {
        val p=Platform().apply {value=group;deferRemoval=true};val ready=mutableListOf<NearbyGroup>()
        val c=NearbyConnector(backgroundScope,p,ready::add,{}, {fail(it)})
        c.observe();runCurrent();c.cancelConnection();runCurrent();advanceTimeBy(2_000);runCurrent()
        assertEquals(1,ready.size)
        p.value=null;advanceTimeBy(751);runCurrent()
        p.value=group.copy(host="192.168.50.1");advanceTimeBy(751);runCurrent()
        assertEquals("192.168.50.1",ready.last().host);c.stop()
    }
}
