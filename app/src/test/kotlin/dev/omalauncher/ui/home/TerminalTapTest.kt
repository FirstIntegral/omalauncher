package dev.omalauncher.ui.home

import dev.omalauncher.ui.home.TerminalTiles.Companion.pressSoFar
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalTapTest {
    private val slop = 24f

    @Test fun aPressThatLiftsWhereItLandedIsATap() {
        assertEquals(TilePress.Tapped, pressSoFar(moved = 0f, slop = slop, pressed = false))
    }

    @Test fun aFingerThatShakesWithinTheSlopStillTaps() {
        assertEquals(TilePress.Held, pressSoFar(moved = 20f, slop = slop, pressed = true))
        assertEquals(TilePress.Tapped, pressSoFar(moved = 24f, slop = slop, pressed = false))
    }

    @Test fun aPressStillDownIsNotATapYet() {
        assertEquals(TilePress.Held, pressSoFar(moved = 0f, slop = slop, pressed = true))
    }

    @Test fun aSwipeThatTurnsThePageNeverWakesTheTile() {
        assertEquals(TilePress.Dragged, pressSoFar(moved = 25f, slop = slop, pressed = true))
        // Lifting after it left the slop is the end of a drag, not a tap.
        assertEquals(TilePress.Dragged, pressSoFar(moved = 300f, slop = slop, pressed = false))
    }
}
