package com.lucianotoscano.pvppokego.arena

import org.junit.Assert.*
import org.junit.Test

class ArenaCompetitionEngineTest {
    private fun report(
        reporter: String, opponent: String, outcome: ArenaOutcome,
        id: String = "match-1", season: String = "S1", league: Int = 1500
    ) = ArenaMatchReport(id, season, league, reporter, opponent, outcome, 100L)

    @Test fun singleSelfReportCannotAffectRatings() {
        val approved = ArenaCompetitionEngine.approvedMatches(
            listOf(report("a", "b", ArenaOutcome.WIN))
        )
        assertTrue(approved.isEmpty())
        assertTrue(ArenaCompetitionEngine.standings(approved, "S1", 1500).isEmpty())
    }

    @Test fun bothSidesMustAgreeOnOppositeResult() {
        val inconsistent = ArenaCompetitionEngine.approvedMatches(
            listOf(report("a", "b", ArenaOutcome.WIN), report("b", "a", ArenaOutcome.WIN))
        )
        assertTrue(inconsistent.isEmpty())
        val consistent = ArenaCompetitionEngine.approvedMatches(
            listOf(report("a", "b", ArenaOutcome.WIN), report("b", "a", ArenaOutcome.LOSS))
        )
        assertEquals(1, consistent.size)
        val ratings = ArenaCompetitionEngine.standings(consistent, "S1", 1500)
        assertEquals(1016, ratings.first().rating)
        assertEquals(984, ratings.last().rating)
        assertEquals(1, ratings.first().wins)
    }

    @Test fun mismatchedSeasonDuplicateReporterAndLeagueAreRejected() {
        assertTrue(ArenaCompetitionEngine.approvedMatches(listOf(
            report("a", "b", ArenaOutcome.WIN),
            report("b", "a", ArenaOutcome.LOSS, season = "S2")
        )).isEmpty())
        assertTrue(ArenaCompetitionEngine.approvedMatches(listOf(
            report("a", "b", ArenaOutcome.WIN),
            report("a", "b", ArenaOutcome.WIN)
        )).isEmpty())
        assertTrue(ArenaCompetitionEngine.approvedMatches(listOf(
            report("a", "b", ArenaOutcome.WIN),
            report("b", "a", ArenaOutcome.LOSS, league = 2500)
        )).isEmpty())
    }

    @Test fun ratingsAreSeparatedBySeasonAndLeague() {
        val pair = ArenaCompetitionEngine.approvedMatches(listOf(
            report("a", "b", ArenaOutcome.WIN), report("b", "a", ArenaOutcome.LOSS)
        ))
        assertTrue(ArenaCompetitionEngine.standings(pair, "S1", 2500).isEmpty())
        assertTrue(ArenaCompetitionEngine.standings(pair, "S2", 1500).isEmpty())
    }

    @Test fun seededFirstRoundRejectsDuplicates() {
        assertEquals(listOf("seed1" to "seed4", "seed2" to "seed3"),
            ArenaCompetitionEngine.firstRound(listOf("seed1", "seed2", "seed3", "seed4")))
        try {
            ArenaCompetitionEngine.firstRound(listOf("a", "a"))
            fail("duplicate entries must fail")
        } catch (_: IllegalArgumentException) { }
    }
}
