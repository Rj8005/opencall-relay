package com.opencall.relay.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `charging is a bonus on top of the existing formula, not a replacement for it`() {
        assertEquals(
            MeshElection.scoreFor(batteryPercent = 90, peerCount = 1) + 400,
            MeshElection.scoreFor(batteryPercent = 90, peerCount = 1, charging = true)
        )
    }

    @Test
    fun `charging defaults to false for existing 2-arg callers`() {
        assertEquals(
            MeshElection.scoreFor(batteryPercent = 60, peerCount = 3),
            MeshElection.scoreFor(batteryPercent = 60, peerCount = 3, charging = false)
        )
    }

    @Test
    fun `a charging device can outrank a higher-battery uncharged device`() {
        // node1: uncharged, 90% battery -> 9010. node2: charging, 70% battery -> 7000+10+400=7410.
        // A 20-point battery gap is bigger than the 400-point bonus can close...
        assertTrue(MeshElection.scoreFor(90, 1) > MeshElection.scoreFor(70, 1, charging = true))
        // ...but a same-decile gap (battery contribution ties at 7000, since the
        // formula only counts battery in 10%-wide steps) is closed by the bonus:
        // 74% uncharged -> 7010; 70% charging -> 7410.
        assertTrue(MeshElection.scoreFor(70, 1, charging = true) > MeshElection.scoreFor(74, 1))
    }

    @Test
    fun `charging bonus alone never outweighs a large peer-count lead`() {
        // node1: charging, 10% battery, 1 peer -> 1000+10+400=1410.
        // node2: uncharged, 10% battery, 50 peers -> 1000+500=1500.
        assertTrue(MeshElection.scoreFor(10, 50) > MeshElection.scoreFor(10, 1, charging = true))
    }
}
