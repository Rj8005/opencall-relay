package com.opencall.relay.offline

import org.junit.Assert.assertEquals
import org.junit.Test

/** PHASE 6 TRACK E: pure-JVM tests for the deterministic election scoring —
 *  same spirit as MeshCarrierTest/MeshBleBeaconTest. */
class MeshElectionTest {

    @Test
    fun `higher battery wins outright`() {
        val winner = MeshElection.pickWinner(
            listOf(Triple(1L, 90, 1), Triple(2L, 50, 1))
        )
        assertEquals(1L, winner)
    }

    @Test
    fun `more visible peers can outweigh lower battery`() {
        // node1: (90/10)*1000 + 1*10 = 9010; node2: (50/10)*1000 + 500*10 = 10000
        val winner = MeshElection.pickWinner(
            listOf(Triple(1L, 90, 1), Triple(2L, 50, 500))
        )
        assertEquals(2L, winner)
    }

    @Test
    fun `exact tie breaks to the lowest nodeId`() {
        val winner = MeshElection.pickWinner(
            listOf(Triple(99L, 80, 5), Triple(3L, 80, 5), Triple(50L, 80, 5))
        )
        assertEquals(3L, winner)
    }

    @Test
    fun `single candidate wins trivially`() {
        assertEquals(42L, MeshElection.pickWinner(listOf(Triple(42L, 0, 0))))
    }

    @Test
    fun `scoreFor matches the spec formula`() {
        assertEquals(9010, MeshElection.scoreFor(batteryPercent = 90, peerCount = 1))
        assertEquals(0, MeshElection.scoreFor(batteryPercent = 5, peerCount = 0))
        assertEquals(10000, MeshElection.scoreFor(batteryPercent = 100, peerCount = 0))
    }

    @Test
    fun `scoreFor clamps out-of-range inputs rather than producing nonsense`() {
        assertEquals(MeshElection.scoreFor(100, 0), MeshElection.scoreFor(150, -5))
        assertEquals(MeshElection.scoreFor(0, 0), MeshElection.scoreFor(-20, -1))
    }
}
