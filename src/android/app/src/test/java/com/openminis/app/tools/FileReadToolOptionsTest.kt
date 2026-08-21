package com.openminis.app.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileReadToolOptionsTest {

    @Test
    fun `defaults use head direction and safe max length`() {
        val options = FileReadTool.parseOptions(JSONObject()).getOrThrow()

        assertEquals(1, options.offset)
        assertEquals(FileReadTool.DEFAULT_MAX_LENGTH, options.maxLength)
        assertEquals("head", options.direction)
        assertEquals(null, options.requestedLines)
    }

    @Test
    fun `max length is capped at the hard safety limit`() {
        val options = FileReadTool.parseOptions(
            JSONObject().put("max_length", 1_000_000),
        ).getOrThrow()

        assertEquals(FileReadTool.MAX_LENGTH_HARD_CAP, options.maxLength)
    }

    @Test
    fun `tail direction is normalized`() {
        val options = FileReadTool.parseOptions(
            JSONObject()
                .put("direction", "TAIL")
                .put("lines", 12),
        ).getOrThrow()

        assertEquals("tail", options.direction)
        assertEquals(12, options.requestedLines)
    }

    @Test
    fun `invalid values are rejected before file access`() {
        val invalidRequests = listOf(
            JSONObject().put("max_length", 0),
            JSONObject().put("lines", 0),
            JSONObject().put("direction", "middle"),
        )

        invalidRequests.forEach { request ->
            assertTrue(FileReadTool.parseOptions(request).isFailure)
        }
    }
}
