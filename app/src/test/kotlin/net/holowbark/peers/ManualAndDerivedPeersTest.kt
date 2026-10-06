package net.holowbark.peers

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualAndDerivedPeersTest {

    @Test
    fun all_isManualAndDerivedTogether() {
        val selection = PeerSelection(manual = setOf("a"), derived = setOf("b"))
        assertEquals(setOf("a", "b"), selection.all)
    }

    @Test
    fun withManual_peerTheAppHadChosen_becomesTheUsers() {
        val selection = PeerSelection(derived = setOf("a")).withManual(listOf("a"))
        assertEquals(setOf("a"), selection.manual)
        assertEquals(emptySet<String>(), selection.derived)
    }

    @Test
    fun withDerived_replacesTheAppsChoiceAndKeepsTheUsers() {
        val selection = PeerSelection(manual = setOf("m"), derived = setOf("old"))
            .withDerived(listOf("new1", "new2"))
        assertEquals(setOf("m"), selection.manual)
        assertEquals(setOf("new1", "new2"), selection.derived)
    }

    @Test
    fun withDerived_emptyList_leavesOnlyTheUsersPeers() {
        val selection = PeerSelection(manual = setOf("m"), derived = setOf("old")).withDerived(emptyList())
        assertEquals(setOf("m"), selection.all)
    }

    @Test
    fun withDerived_peerTheUserAlreadyHas_isNotCountedTwice() {
        val selection = PeerSelection(manual = setOf("m")).withDerived(listOf("m", "x"))
        assertEquals(setOf("x"), selection.derived)
    }

    @Test
    fun without_removesFromWhicheverSetHoldsIt() {
        val selection = PeerSelection(manual = setOf("m"), derived = setOf("d")).without(listOf("m", "d"))
        assertEquals(emptySet<String>(), selection.all)
    }
}
