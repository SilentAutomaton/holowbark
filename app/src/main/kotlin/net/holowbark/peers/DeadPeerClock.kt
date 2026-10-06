package net.holowbark.peers

/** Time between looks at whether any peer is up. */
const val PEER_WATCH_TICK_MS = 30_000L
/** Eligible ticks with no peer up before a new search: two minutes. */
const val REDISCOVERY_AFTER_TICKS = 4
private const val REDISCOVERY_MIN_PAUSE_MS = 2 * 60_000L
private const val REDISCOVERY_MAX_PAUSE_MS = 30 * 60_000L

/**
 * Decides when a search should start again because no peer has answered for a
 * long time. A tick counts only when [tick] is told it is eligible, that is,
 * when nothing explains the silence: a phone with no network, a dark screen or
 * Doze is quiet for good reason, and searching then would only spend the radio.
 *
 * A search that brings nobody up is followed by a longer pause, doubling to a
 * cap, so a network that blocks every peer is not probed every two minutes.
 */
class DeadPeerClock(
    private val minPauseMs: Long = REDISCOVERY_MIN_PAUSE_MS,
    private val maxPauseMs: Long = REDISCOVERY_MAX_PAUSE_MS,
) {
    private var deadTicks = 0
    private var pauseMs = minPauseMs
    private var quietUntilMs = 0L

    /** True when a new search should start now. */
    @Synchronized
    fun tick(anyPeerUp: Boolean, eligible: Boolean, nowMs: Long): Boolean {
        if (!eligible) return false
        if (anyPeerUp) {
            reset()
            return false
        }
        if (deadTicks < REDISCOVERY_AFTER_TICKS) deadTicks++
        if (deadTicks < REDISCOVERY_AFTER_TICKS || nowMs < quietUntilMs) return false
        deadTicks = 0
        quietUntilMs = nowMs + pauseMs
        pauseMs = minOf(pauseMs * 2, maxPauseMs)
        return true
    }

    /** A new network or a wake: the peers get their redial before the count starts. */
    @Synchronized
    fun reset() {
        deadTicks = 0
        pauseMs = minPauseMs
        quietUntilMs = 0L
    }
}
