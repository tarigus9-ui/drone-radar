package com.droneradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the code-review findings (each failed before its fix). */
class SpoofRulesTest {

    private fun drone(lat: Double, lon: Double, ts10: Int? = null) =
        DroneInfo("k").apply { this.lat = lat; this.lon = lon; this.ts10 = ts10; idType = 1; serial = "1581F5FKD229400E1GR3" }

    // Finding 3: a single bad reading used to mark the drone forever.
    @Test fun flagsClearAfterAGoodUpdate() {
        val d = drone(25.1000, 55.3800, ts10 = 100)
        SpoofCheck.local(d, 25.0000, 55.3800, 0L, 90, null)          // ~11 km in 1 s → flagged
        assertTrue("impossible_speed" in d.spoof)
        d.lat = 25.1001; d.ts10 = 110
        SpoofCheck.local(d, 25.1000, 55.3800, 0L, 100, null)         // 11 m in 1 s → normal
        assertFalse("impossible_speed" in d.spoof)
        assertTrue("history keeps it", "impossible_speed" in d.everFlagged)
    }

    // Finding 4: jumps of 50 m or less were never flagged, whatever the time.
    @Test fun shortJumpInNoTimeIsFlagged() {
        assertTrue(SpoofCheck.impossibleJump(45.0, 0.0))
        assertFalse(SpoofCheck.impossibleJump(45.0, 1.0))
        assertTrue(SpoofCheck.impossibleJump(500.0, 5.0))
    }

    // Finding 4: time from the drone's own timestamps, wrapping at the hour.
    @Test fun intervalUsesDroneTimestamps() {
        assertEquals(0.5, SpoofCheck.interval(35_998, 3, 0L, 99_000L), 1e-9)   // wraps the hour
        assertEquals(2.0, SpoofCheck.interval(null, null, 0L, 2_000L), 1e-9)   // falls back to receive time
        assertEquals(3.0, SpoofCheck.interval(50, 50, 0L, 3_000L), 1e-9)       // stuck timestamp → receive time
    }

    // Finding 6: the secret part without its dash (19 chars) must still be checked.
    @Test fun operatorIdWithSecretNoDash() {
        assertEquals(true, GlobalId.operator("FIN87astrdge12k8xyz")?.ok)
        assertEquals(true, GlobalId.operator("FIN87astrdge12k8-xyz")?.ok)
        assertEquals(false, GlobalId.operator("FIN87astrdge12k9-xyz")?.ok)
    }

    // Finding 8: a character outside base-36 means "cannot compute", not "forged".
    @Test fun luhnNotComputable() {
        assertNull(GlobalId.luhn36("87astrdge12k#yz"))
        assertEquals('8', GlobalId.luhn36("87astrdge12kxyz"))
    }

    // Finding 9: the literal NULL means "not declared".
    @Test fun nullLiteralIsUndeclared() {
        assertEquals("undeclared", GlobalId.operator("NULL")?.scheme)
        assertNull(GlobalId.operator("NULL")?.ok)
    }

    // Finding 7: one function decides — a bad serial is reported by it, not only by SpoofCheck.
    @Test fun singleDecisionIncludesSerial() {
        val d = DroneInfo("k").apply { idType = 1; serial = "XX" }
        assertTrue("bad_serial" in GlobalId.idFlags(d))
        assertTrue(GlobalId.badId(d))
    }
}
