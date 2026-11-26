package com.openminis.app.provider

import com.openminis.app.ui.settings.normalizeOllamaBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OllamaUrlTest {
    @Test fun `bare host and port gains http scheme`() {
        assertEquals("http://192.168.1.20:11434", normalizeOllamaBase("192.168.1.20:11434"))
    }
    @Test fun `localhost default survives`() {
        assertEquals("http://localhost:11434", normalizeOllamaBase("http://localhost:11434"))
    }
    @Test fun `trailing slash and v1 are stripped`() {
        assertEquals("http://localhost:11434", normalizeOllamaBase("http://localhost:11434/v1/"))
        assertEquals("http://localhost:11434", normalizeOllamaBase("localhost:11434/"))
    }
    @Test fun `https is kept`() {
        assertEquals("https://ollama.example.com:11434", normalizeOllamaBase("https://ollama.example.com:11434"))
    }
    @Test fun `blank and garbage are rejected`() {
        assertNull(normalizeOllamaBase(""))
        assertNull(normalizeOllamaBase("   "))
        assertNull(normalizeOllamaBase("http://"))
        assertNull(normalizeOllamaBase("192.168.1.20:abc"))
        assertNull(normalizeOllamaBase("ftp://example.com:11434"))
        assertNull(normalizeOllamaBase("http://example.com/some/path"))
    }
}
