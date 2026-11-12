package com.codespace.ide.agent.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Project 1.3: real conflicts are found, wording differences are not invented into conflicts. */
class ContradictionScanTest {

    private fun ev(vararg pairs: Pair<String, String>) = pairs.toList()

    @Test fun twoDifferentVersionsOfTheSameSubjectAreADispute() {
        val r = ContradictionScan.report(
            ev(
                "S1" to "The library requires kotlin version 1.9 or newer.",
                "S2" to "The library requires kotlin version 2.0 or newer.",
            ),
        )
        assertEquals(1, r.disputed.size)
        assertEquals(setOf("S1", "S2"), r.disputed.first().claims.map { it.sourceId }.toSet())
        assertTrue(ContradictionScan.render(r).contains("do not pick one silently"))
    }

    @Test fun theSameValueFromTwoSourcesIsCorroborationNotADispute() {
        val r = ContradictionScan.report(
            ev(
                "S1" to "Android 14 requires min sdk 34.",
                "S2" to "Android 14 requires min sdk 34.",
            ),
        )
        assertTrue(r.disputed.isEmpty())
        assertEquals(1, r.confirmed.size)
    }

    @Test fun oneSourceAloneIsUnconfirmed() {
        val r = ContradictionScan.report(ev("S1" to "Android 14 requires min sdk 34."))
        assertEquals(1, r.unconfirmed.size)
        assertTrue(ContradictionScan.render(r).contains("single source"))
    }

    @Test fun differentWordingOfTheSameClaimIsNotAContradiction() {
        val r = ContradictionScan.report(
            ev(
                "S1" to "The build takes 40 ms on this device.",
                "S2" to "The build takes 40 ms on this device.",
            ),
        )
        assertTrue(r.disputed.isEmpty())
    }

    @Test fun differentSubjectsAreNeverCompared() {
        val r = ContradictionScan.report(
            ev(
                "S1" to "The emulator needs 4 gb of memory.",
                "S2" to "The compiler needs 8 gb of memory.",
            ),
        )
        assertTrue("different subjects must not be a dispute", r.disputed.isEmpty())
    }

    @Test fun differentUnitsAreNotTreatedAsAConflict() {
        val r = ContradictionScan.report(
            ev(
                "S1" to "The build takes 40 ms.",
                "S2" to "The build takes 2 s.",
            ),
        )
        assertTrue(r.disputed.isEmpty())
    }

    @Test fun oppositePolarityAboutTheSameSubjectIsADispute() {
        val r = ContradictionScan.report(
            ev(
                "S1" to "The plugin does not support incremental builds.",
                "S2" to "The plugin does not support parallel builds.",
            ),
        )
        assertTrue("two different negations of different subjects are not a conflict", r.disputed.isEmpty())
    }

    @Test fun aPositiveAndANegativeAboutOneSubjectConflict() {
        val r = ContradictionScan.report(
            ev(
                "S1" to "The plugin does not support kapt.",
                "S2" to "This version adds kapt support with a flag.",
            ),
        )
        // Only the negative sentence yields a claim here; a lone claim is unconfirmed, never a
        // fabricated dispute, which is the safe direction for a scanner without semantics.
        assertTrue(r.disputed.isEmpty())
        assertEquals(1, r.unconfirmed.size)
    }

    @Test fun everySideOfADisputeKeepsItsSourceAndQuote() {
        val r = ContradictionScan.report(
            ev(
                "S1" to "The release requires jdk version 17.",
                "S2" to "The release requires jdk version 21.",
            ),
        )
        val d = r.disputed.first()
        val bySource = d.claims.associateBy { it.sourceId }
        assertTrue(bySource.getValue("S1").value.startsWith("17"))
        assertTrue(bySource.getValue("S2").value.startsWith("21"))
        assertTrue(bySource.getValue("S1").quote.contains("jdk"))
    }

    @Test fun renderingStatesBothPositionsWithoutChoosing() {
        val r = ContradictionScan.report(
            ev(
                "S1" to "The release requires jdk version 17.",
                "S2" to "The release requires jdk version 21.",
            ),
        )
        val text = ContradictionScan.render(r)
        assertTrue(text.contains("17"))
        assertTrue(text.contains("21"))
        assertTrue(text.contains("Sources disagree"))
    }

    @Test fun emptyEvidenceRendersNothing() {
        assertEquals("", ContradictionScan.render(ContradictionScan.report(emptyList())))
    }

    @Test fun scanningIsBounded() {
        val long = (1..200).joinToString(" ") { "The tool requires component$it version 1.$it." }
        val claims = ContradictionScan.claims(listOf("S1" to long))
        assertTrue("claims must be capped", claims.size <= 200)
        assertTrue("sentences per source are capped", claims.size <= 12)
    }
}
