package com.openminis.app.agent.jobs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [B11] Deliberation engine: the critique prompt, the cross-share cap and the team-run payload markers. */
class DeliberationEngineTest {

    @Test
    fun `buildDeliberationPrompt names agreements disagreements and errors and asks for a revised answer in full`() {
        val prompt = HelperRunner.buildDeliberationPrompt(
            ownResult = "My answer: 42.",
            otherResults = listOf("researcher" to "Their answer: 43."),
        )
        assertTrue(prompt.contains("agreements"))
        assertTrue(prompt.contains("disagreements"))
        assertTrue(prompt.contains("errors"))
        assertTrue(prompt.contains("REVISED answer in full"))
        assertTrue(prompt.contains("My answer: 42."))
        assertTrue(prompt.contains("researcher"))
        assertTrue(prompt.contains("Their answer: 43."))
    }

    @Test
    fun `buildDeliberationPrompt with no other results still returns the own result`() {
        val prompt = HelperRunner.buildDeliberationPrompt(
            ownResult = "My answer: 42.",
            otherResults = emptyList(),
        )
        assertTrue(prompt.contains("My answer: 42."))
        assertTrue(prompt.contains("REVISED answer in full"))
    }

    @Test
    fun `truncateDeliberationResult caps a shared result at 4000 characters`() {
        val long = "x".repeat(5000)
        assertEquals(4000, HelperRunner.truncateDeliberationResult(long).length)
        assertEquals("x".repeat(4000), HelperRunner.truncateDeliberationResult(long))
        assertEquals("short", HelperRunner.truncateDeliberationResult("short"))
    }

    @Test
    fun `buildDeliberationPrompt truncates each other result to 4000 characters`() {
        val longA = "a".repeat(4000) + "TAIL-A" + "a".repeat(1000)
        val longB = "b".repeat(4000) + "TAIL-B" + "b".repeat(1000)
        val prompt = HelperRunner.buildDeliberationPrompt(
            ownResult = "Mine.",
            otherResults = listOf("one" to longA, "two" to longB),
        )
        assertTrue(prompt.contains("a".repeat(4000)))
        assertTrue(prompt.contains("b".repeat(4000)))
        assertFalse(prompt.contains("TAIL-A"))
        assertFalse(prompt.contains("TAIL-B"))
    }

    @Test
    fun `resultJson carries team run id and deliberation round for round 2`() {
        val payload = JSONObject(
            HelperRunner.resultJson(
                "completed", "done", "m", HelperModelTier.PRIMARY, "primary",
                2, 3000, "C", "J",
                teamRunId = "team-1",
                deliberationRound = 2,
            ),
        )
        assertEquals("team-1", payload.getString("team_run_id"))
        assertEquals(2, payload.getInt("deliberation_round"))
    }

    @Test
    fun `resultJson carries deliberation round 1 for a team run's first answers`() {
        val payload = JSONObject(
            HelperRunner.resultJson(
                "completed", "done", "m", HelperModelTier.PRIMARY, "primary",
                2, 3000, "C", "J",
                teamRunId = "team-1",
                deliberationRound = 1,
            ),
        )
        assertEquals("team-1", payload.getString("team_run_id"))
        assertEquals(1, payload.getInt("deliberation_round"))
    }

    @Test
    fun `resultJson omits team markers for a run that is not a team run`() {
        val payload = JSONObject(
            HelperRunner.resultJson(
                "completed", "done", "m", HelperModelTier.PRIMARY, "primary",
                2, 3000, "C", "J",
            ),
        )
        assertFalse(payload.has("team_run_id"))
        assertFalse(payload.has("deliberation_round"))
    }
}
