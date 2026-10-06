package net.holowbark.peers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadPeerClockTest {

    private val tickMs = 30_000L
    private var now = 0L

    private fun DeadPeerClock.dead(times: Int): List<Boolean> =
        List(times) { now += tickMs; tick(anyPeerUp = false, eligible = true, nowMs = now) }

    private fun DeadPeerClock.up() = tick(anyPeerUp = true, eligible = true, nowMs = now.also { now += tickMs })

    private fun DeadPeerClock.ineligible(times: Int) =
        repeat(times) { now += tickMs; tick(anyPeerUp = false, eligible = false, nowMs = now) }

    @Test
    fun tick_fourDeadEligibleTicks_firesOnTheFourth() {
        val clock = DeadPeerClock()
        assertEquals(listOf(false, false, false, true), clock.dead(4))
    }

    @Test
    fun tick_notEligible_neverCounts() {
        val clock = DeadPeerClock()
        clock.ineligible(100)
        assertEquals(listOf(false, false, false, true), clock.dead(4))
    }

    @Test
    fun tick_ineligibleStretchBetweenDeadTicks_keepsTheCount() {
        val clock = DeadPeerClock()
        clock.dead(2)
        clock.ineligible(5)
        assertEquals(listOf(false, true), clock.dead(2))
    }

    @Test
    fun tick_aPeerComesUp_resetsTheCount() {
        val clock = DeadPeerClock()
        clock.dead(3)
        clock.up()
        assertEquals(listOf(false, false, false, true), clock.dead(4))
    }

    @Test
    fun tick_afterFiring_theNextOneNeedsFourMoreDeadTicks() {
        val clock = DeadPeerClock()
        assertTrue(clock.dead(4).last())
        assertEquals(listOf(false, false, false), clock.dead(3))
    }

    @Test
    fun tick_pauseDoubles_soTheSecondRepeatWaitsLonger() {
        val clock = DeadPeerClock()
        assertTrue(clock.dead(4).last())                  // t = 2 min, pause 2 min
        assertTrue(clock.dead(4).last())                  // t = 4 min, allowed again, pause 4 min
        val third = clock.dead(8)                         // t = 4 to 8 min
        assertFalse(third.take(7).any { it })             // still inside the 4 min pause
        assertTrue(third.last())                          // t = 8 min
    }

    @Test
    fun tick_pauseStopsDoublingAtTheCap() {
        val clock = DeadPeerClock(minPauseMs = 2 * 60_000L, maxPauseMs = 4 * 60_000L)
        clock.dead(4)
        clock.dead(4)
        clock.dead(8)
        val fourth = clock.dead(8)                        // the pause is still 4 min, not 8
        assertTrue(fourth.last())
        assertFalse(fourth.take(7).any { it })
    }

    @Test
    fun tick_aPeerComesUp_resetsThePause() {
        val clock = DeadPeerClock()
        clock.dead(4)
        clock.dead(4)                                     // the pause is now 4 min
        clock.up()
        assertEquals(listOf(false, false, false, true), clock.dead(4))
    }

    @Test
    fun reset_clearsTheCountAndThePause() {
        val clock = DeadPeerClock()
        clock.dead(4)
        clock.dead(3)
        clock.reset()
        assertEquals(listOf(false, false, false, true), clock.dead(4))
    }
}
