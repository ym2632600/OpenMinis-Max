package com.openminis.app.ui.sandbox

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * C14: mobile coding companion - a lightweight built-in code editor with
 * syntax highlighting. Opened from the file preview's Edit action; saves in
 * place. Run-in-sandbox, git flows and diff review are covered by the
 * bundled coding-companion skill (agent side).
 *
 * Soft wrap is off: the line-number gutter and the text share one vertical
 * scroll state, which only lines up when every source line is one row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeEditorScreen(
    item: FileItem,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }

    LaunchedEffect(item.file.absolutePath) {
        text = withContext(Dispatchers.IO) {
            runCatching {
                // Cap like the preview path: editing a huge file on a phone
                // is never the right tool; refuse rather than hang.
                if (item.file.length() > 512_000L) null
                else item.file.readText(Charsets.UTF_8)
            }.getOrNull()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = item.name + if (dirty) " *" else "",
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val content = text ?: return@IconButton
                            val ok = runCatching {
                                item.file.writeText(content, Charsets.UTF_8)
                            }.isSuccess
                            if (ok) dirty = false
                            Toast.makeText(
                                context,
                                context.getString(
                                    if (ok) R.string.code_editor_saved else R.string.file_save_failed_toast
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                        enabled = dirty,
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.code_editor_save))
                    }
                },
            )
        },
    ) { padding ->
        val content = text
        if (content == null) {
            Column(modifier = Modifier.padding(padding).padding(16.dp)) {
                Text(stringResource(R.string.code_editor_cannot_open))
            }
            return@Scaffold
        }
        val vScroll = rememberScrollState()
        val hScroll = rememberScrollState()
        val lines = content.count { it == '\n' } + 1
        Row(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(vScroll)
                .horizontalScroll(hScroll),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(start = 8.dp, end = 8.dp, top = 4.dp),
                horizontalAlignment = Alignment.End,
            ) {
                for (n in 1..lines) {
                    Text(
                        text = n.toString(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
            BasicTextField(
                value = content,
                onValueChange = { text = it; dirty = true },
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                visualTransformation = CodeHighlightTransformation,
                modifier = Modifier.padding(top = 4.dp, end = 12.dp),
            )
        }
    }
}

/**
 * Lightweight keyword/string/comment highlighting for common languages
 * (Kotlin, Java, Python, JS/TS, shell). Regex-based on purpose - this is a
 * companion editor, not an IDE.
 */
private object CodeHighlightTransformation : VisualTransformation {

    private val keyword = Regex(
        "\\b(fun|val|var|class|object|interface|return|if|else|when|for|while|do|break|continue" +
            "|import|package|private|public|internal|protected|override|data|sealed|enum|companion" +
            "|def|lambda|elif|except|finally|raise|with|as|from|yield|pass|None|True|False" +
            "|const|let|async|await|new|typeof|instanceof|switch|case|default|try|catch|throw|throws" +
            "|extends|implements|static|final|abstract|void|int|long|double|float|boolean|char|byte" +
            "|echo|then|fi|done|esac|function|local|export)\\b"
    )
    private val comment = Regex("//[^\n]*|#[^\n]*|/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
    private val stringLit = Regex("\"(?:[^\"\\\\\n]|\\\\.)*\"|'(?:[^'\\\\\n]|\\\\.)*'")
    private val number = Regex("\\b\\d+(\\.\\d+)?[fLlL]?\\b")

    private val keywordColor = Color(0xFF7C4DFF)
    private val commentColor = Color(0xFF6A8759)
    private val stringColor = Color(0xFFFF8A65)
    private val numberColor = Color(0xFF4FC3F7)

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val styled = buildAnnotatedString {
            append(raw)
            fun paint(regex: Regex, color: Color) {
                for (m in regex.findAll(raw)) {
                    runCatching {
                        addStyle(SpanStyle(color = color), m.range.first, m.range.last + 1)
                    }.onFailure {
                        AppLogger.warning("CodeEditor", "highlight span failed: ${it.message}")
                    }
                }
            }
            paint(comment, commentColor)
            paint(stringLit, stringColor)
            paint(keyword, keywordColor)
            paint(number, numberColor)
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }
}
