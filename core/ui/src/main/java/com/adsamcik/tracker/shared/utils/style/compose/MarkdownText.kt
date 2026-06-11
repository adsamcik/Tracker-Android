package com.adsamcik.tracker.shared.utils.style.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Minimal Markdown renderer for in-app static content (Privacy Policy, About, etc.).
 *
 * Intentionally does not depend on a Markdown library — this app is offline-first and
 * supports only the subset of syntax actually used by its bundled docs:
 *
 *  * `# Heading 1`, `## Heading 2`, `### Heading 3`
 *  * `* item` or `- item` bullet lists
 *  * `**bold**` inline spans
 *  * `*italic*` inline spans (single asterisk)
 *  * Blank lines become paragraph spacing
 *  * Everything else renders as plain body text
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            when (block) {
                is MdBlock.Heading1 -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                is MdBlock.Heading2 -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp),
                )
                is MdBlock.Heading3 -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp),
                )
                is MdBlock.Paragraph -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                is MdBlock.Bullet -> Row(modifier = Modifier.padding(start = 8.dp)) {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = block.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

private sealed interface MdBlock {
    data class Heading1(val text: AnnotatedString) : MdBlock
    data class Heading2(val text: AnnotatedString) : MdBlock
    data class Heading3(val text: AnnotatedString) : MdBlock
    data class Paragraph(val text: AnnotatedString) : MdBlock
    data class Bullet(val text: AnnotatedString) : MdBlock
}

private fun parseMarkdown(src: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val paragraphBuffer = StringBuilder()

    fun flushParagraph() {
        val text = paragraphBuffer.toString().trim()
        if (text.isNotEmpty()) {
            blocks.add(MdBlock.Paragraph(parseInline(text)))
        }
        paragraphBuffer.clear()
    }

    src.lineSequence().forEach { rawLine ->
        val line = rawLine.trimEnd()
        when {
            line.isBlank() -> flushParagraph()
            line.startsWith("### ") -> {
                flushParagraph()
                blocks.add(MdBlock.Heading3(parseInline(line.removePrefix("### ").trim())))
            }
            line.startsWith("## ") -> {
                flushParagraph()
                blocks.add(MdBlock.Heading2(parseInline(line.removePrefix("## ").trim())))
            }
            line.startsWith("# ") -> {
                flushParagraph()
                blocks.add(MdBlock.Heading1(parseInline(line.removePrefix("# ").trim())))
            }
            line.startsWith("* ") || line.startsWith("- ") -> {
                flushParagraph()
                val body = line.drop(2).trim()
                blocks.add(MdBlock.Bullet(parseInline(body)))
            }
            else -> {
                if (paragraphBuffer.isNotEmpty()) paragraphBuffer.append(' ')
                paragraphBuffer.append(line.trim())
            }
        }
    }
    flushParagraph()
    return blocks
}

/**
 * Inline formatting: **bold** first, then *italic* on remainder. Does not support nesting.
 */
private fun parseInline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            i + 1 < text.length && text[i] == '*' && text[i + 1] == '*' -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    withSpan(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(text[i]); i++
                }
            }
            text[i] == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1) {
                    withSpan(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(text[i]); i++
                }
            }
            else -> {
                append(text[i]); i++
            }
        }
    }
}

private inline fun androidx.compose.ui.text.AnnotatedString.Builder.withSpan(
    style: SpanStyle,
    block: androidx.compose.ui.text.AnnotatedString.Builder.() -> Unit,
) {
    val idx = pushStyle(style)
    try {
        block()
    } finally {
        pop(idx)
    }
}
