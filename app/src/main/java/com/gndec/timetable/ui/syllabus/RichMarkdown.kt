package com.gndec.timetable.ui.syllabus

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private sealed interface RichBlock {
    data class Heading(val level: Int, val text: String) : RichBlock
    data class Paragraph(val text: String) : RichBlock
    data class Quote(val text: String) : RichBlock
    data class Code(val language: String, val code: String) : RichBlock
    data class Math(val expression: String, val display: Boolean) : RichBlock
    data object Rule : RichBlock
    data class ListBlock(val items: List<RichListItem>) : RichBlock
    data class Table(
        val header: List<String>,
        val rows: List<List<String>>,
        val alignments: List<TableAlignment>
    ) : RichBlock
}

private data class RichListItem(
    val level: Int,
    val ordered: Boolean,
    val marker: String,
    val text: String
)

private enum class TableAlignment { LEFT, CENTER, RIGHT }

/**
 * Mobile-first Markdown renderer. The parser deliberately accepts incomplete input:
 * streamed code/math fences remain visible as ordinary blocks until their closing fence arrives.
 */
@Composable
fun RichMarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { parseRichMarkdown(markdown) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEachIndexed { index, block ->
            RichBlockView(block, key = "rich-block-$index")
        }
    }
}

@Composable
private fun RichBlockView(block: RichBlock, key: String) {
    when (block) {
        is RichBlock.Heading -> RichInlineText(
            text = block.text,
            style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                2 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                3 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                4 -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                5 -> MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                else -> MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
            }
        )
        is RichBlock.Paragraph -> RichInlineText(
            text = block.text,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 25.sp)
        )
        is RichBlock.Quote -> QuoteBlock(block.text)
        is RichBlock.Code -> CodeBlock(block.language, block.code)
        is RichBlock.Math -> MathBlock(block.expression, block.display)
        RichBlock.Rule -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        is RichBlock.ListBlock -> RichList(block.items)
        is RichBlock.Table -> RichTable(block)
    }
}

@Composable
private fun RichInlineText(text: String, style: TextStyle) {
    val uriHandler = LocalUriHandler.current
    val annotated = remember(text, style) { inlineAnnotated(text) }
    ClickableText(
        text = annotated,
        modifier = Modifier.fillMaxWidth(),
        style = style,
        onClick = { offset ->
            annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { annotation ->
                runCatching { uriHandler.openUri(annotation.item) }
            }
        }
    )
}

@Composable
private fun QuoteBlock(text: String) {
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.width(3.dp).height(42.dp).background(MaterialTheme.colorScheme.primary))
        Spacer(Modifier.width(10.dp))
        RichInlineText(
            text = text,
            style = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                lineHeight = 24.sp
            )
        )
    }
}

@Composable
private fun RichList(items: List<RichListItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        items.forEachIndexed { index, item ->
            Row(
                Modifier.fillMaxWidth().padding(start = (item.level * 18).dp),
                verticalAlignment = Alignment.Top
            ) {
                val marker = if (item.ordered) item.marker else "•"
                Text(
                    marker,
                    modifier = Modifier.width(24.dp),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge
                )
                RichInlineText(item.text, MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp))
            }
        }
    }
}

@Composable
private fun CodeBlock(language: String, code: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    val highlighted = remember(code, language) { syntaxHighlight(code, language) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    language.ifBlank { "code" }.lowercase(Locale.ROOT),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace
                )
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(code))
                        copied = true
                    },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = if (copied) "Copied" else "Copy code",
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
            androidx.compose.foundation.text.BasicText(
                highlighted,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 19.sp)
            )
        }
    }
}

@Composable
private fun MathBlock(expression: String, display: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Text(
            normalizeLatex(expression),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(14.dp),
            style = if (display) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Serif,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
        )
    }
}

@Composable
private fun RichTable(table: RichBlock.Table) {
    val columnCount = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
    val cellWidth = 148.dp
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            TableRow(table.header, table.alignments, columnCount, cellWidth, header = true)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            table.rows.forEachIndexed { index, row ->
                TableRow(row, table.alignments, columnCount, cellWidth, header = false)
                if (index < table.rows.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun TableRow(
    values: List<String>,
    alignments: List<TableAlignment>,
    columnCount: Int,
    cellWidth: androidx.compose.ui.unit.Dp,
    header: Boolean
) {
    Row(Modifier.width(cellWidth * columnCount)) {
        repeat(columnCount) { index ->
            val alignment = when (alignments.getOrNull(index) ?: TableAlignment.LEFT) {
                TableAlignment.LEFT -> androidx.compose.ui.text.style.TextAlign.Start
                TableAlignment.CENTER -> androidx.compose.ui.text.style.TextAlign.Center
                TableAlignment.RIGHT -> androidx.compose.ui.text.style.TextAlign.End
            }
            Box(Modifier.width(cellWidth).padding(horizontal = 11.dp, vertical = 10.dp)) {
                RichInlineText(
                    text = values.getOrNull(index).orEmpty(),
                    style = MaterialTheme.typography.bodySmall.copy(
                        lineHeight = 18.sp,
                        fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                        textAlign = alignment
                    )
                )
            }
        }
    }
}

private fun parseRichMarkdown(source: String): List<RichBlock> {
    val normalizedSource = source
        .replace(Regex("(?m)(---+|\\*\\*\\*+|___+)\\s*(?=#{1,6}\\s+)"), "$1\n")
    val lines = normalizedSource
        .replace("\\r", "")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .split('\n')
    val blocks = mutableListOf<RichBlock>()
    val paragraph = mutableListOf<String>()
    var i = 0

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            val text = paragraph.joinToString("\n").trim()
            if (text.isNotBlank()) blocks += RichBlock.Paragraph(text)
            paragraph.clear()
        }
    }

    while (i < lines.size) {
        val original = lines[i]
        val trimmed = original.trim()
        if (trimmed.isBlank()) {
            flushParagraph(); i++; continue
        }

        val fence = Regex("^\\s*(```+|~~~+)\\s*([\\w#+.-]*)\\s*$").find(original)
        if (fence != null) {
            flushParagraph()
            val marker = fence.groupValues[1]
            val language = fence.groupValues[2]
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith(marker.first().toString().repeat(marker.length))) {
                codeLines += lines[i]
                i++
            }
            if (i < lines.size) i++
            blocks += RichBlock.Code(language, codeLines.joinToString("\n"))
            continue
        }

        if (trimmed == "\\[" || trimmed == "$$") {
            flushParagraph()
            val closing = if (trimmed == "\\[") "\\]" else "$$"
            val mathLines = mutableListOf<String>()
            i++
            while (i < lines.size && lines[i].trim() != closing) {
                mathLines += lines[i]
                i++
            }
            if (i < lines.size) i++
            blocks += RichBlock.Math(mathLines.joinToString("\n").trim(), display = true)
            continue
        }

        val heading = Regex("^\\s*(#{1,6})\\s+(.+?)\\s*#*\\s*$").find(original)
        if (heading != null) {
            flushParagraph()
            blocks += RichBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
            i++; continue
        }

        if (trimmed.matches(Regex("^(---+|\\*\\*\\*+|___+)$"))) {
            flushParagraph(); blocks += RichBlock.Rule; i++; continue
        }

        if (trimmed.startsWith(">")) {
            flushParagraph()
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                quoteLines += lines[i].trimStart().removePrefix(">").trimStart()
                i++
            }
            blocks += RichBlock.Quote(quoteLines.joinToString("\n"))
            continue
        }

        if (isListLine(original)) {
            flushParagraph()
            val listLines = mutableListOf<String>()
            while (i < lines.size && (isListLine(lines[i]) || (lines[i].isNotBlank() && lines[i].takeWhile { it == ' ' || it == '\t' }.length >= 2))) {
                listLines += lines[i]
                i++
            }
            blocks += RichBlock.ListBlock(parseList(listLines))
            continue
        }

        val nextLine = lines.getOrNull(i + 1)?.trim().orEmpty()
        val hasStandardTableDivider = looksLikeTableHeader(trimmed) && isTableDivider(nextLine)
        val hasLooseTableRows = looksLikeTableHeader(trimmed) && nextLine.contains('|') && !nextLine.startsWith("http")
        if (hasStandardTableDivider || hasLooseTableRows) {
            flushParagraph()
            val header = splitTableRow(trimmed)
            val alignments = if (hasStandardTableDivider) {
                splitTableRow(nextLine).map { cell ->
                    val value = cell.trim()
                    when {
                        value.startsWith(":") && value.endsWith(":") -> TableAlignment.CENTER
                        value.endsWith(":") -> TableAlignment.RIGHT
                        else -> TableAlignment.LEFT
                    }
                }
            } else {
                List(header.size) { TableAlignment.LEFT }
            }
            i += if (hasStandardTableDivider) 2 else 1
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && lines[i].trim().contains('|')) {
                val candidate = lines[i].trim()
                if (candidate.isBlank() || isTableDivider(candidate)) break
                rows += splitTableRow(candidate)
                i++
            }
            blocks += RichBlock.Table(header, rows, alignments)
            continue
        }

        val singleLineDisplayMath = when {
            trimmed.startsWith("\\[") && trimmed.endsWith("\\]") && trimmed.length > 4 -> trimmed.removePrefix("\\[").removeSuffix("\\]")
            trimmed.startsWith("$$") && trimmed.endsWith("$$") && trimmed.length > 4 -> trimmed.removePrefix("$$").removeSuffix("$$")
            else -> null
        }
        if (singleLineDisplayMath != null) {
            flushParagraph(); blocks += RichBlock.Math(singleLineDisplayMath.trim(), display = true); i++; continue
        }

        if (trimmed.startsWith("\\(") && trimmed.endsWith("\\)")) {
            flushParagraph(); blocks += RichBlock.Math(trimmed.removePrefix("\\(").removeSuffix("\\)"), display = false); i++; continue
        }
        paragraph += original
        i++
    }
    flushParagraph()
    return blocks
}

private fun isListLine(line: String): Boolean = Regex("^\\s*(?:[-*+]\\s+|\\d+[.)]\\s+).+").matches(line)

private fun parseList(lines: List<String>): List<RichListItem> = lines.mapNotNull { line ->
    val match = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.+)$").find(line) ?: return@mapNotNull null
    val indent = match.groupValues[1].replace("\t", "    ").length
    val marker = match.groupValues[2]
    RichListItem(indent / 2, marker.first().isDigit(), marker, match.groupValues[3])
}

private fun looksLikeTableHeader(line: String): Boolean = line.count { it == '|' } >= 1

private fun isTableDivider(line: String): Boolean {
    val cells = splitTableRow(line)
    return cells.size >= 2 && cells.all { it.trim().matches(Regex(":?-{3,}:?")) }
}

private fun splitTableRow(line: String): List<String> {
    val value = line.trim().removePrefix("|").removeSuffix("|")
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var escaped = false
    value.forEach { char ->
        if (escaped) { current.append(char); escaped = false }
        else if (char == '\\') { escaped = true }
        else if (char == '|') { cells += current.toString().trim(); current.clear() }
        else current.append(char)
    }
    if (escaped) current.append('\\')
    cells += current.toString().trim()
    return cells
}

private fun inlineAnnotated(value: String): AnnotatedString = buildAnnotatedString {
    appendInline(this, value)
}

private fun appendInline(builder: androidx.compose.ui.text.AnnotatedString.Builder, source: String) {
    var i = 0
    while (i < source.length) {
        if (source[i] == '\\' && i + 1 < source.length) {
            if (source.startsWith("\\(", i)) {
                val end = source.indexOf("\\)", i + 2)
                if (end >= 0) {
                    builder.withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = Color(0xFF176B70))) { append(normalizeLatex(source.substring(i + 2, end))) }
                    i = end + 2; continue
                }
            }
            builder.append(source[i + 1]); i += 2; continue
        }

        val mathDollar = source[i] == '$' && i + 1 < source.length && source[i + 1] != ' '
        if (mathDollar) {
            val end = source.indexOf('$', i + 1)
            if (end > i + 1) {
                builder.withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = Color(0xFF176B70))) { append(normalizeLatex(source.substring(i + 1, end))) }
                i = end + 1; continue
            }
        }

        if (source[i] == '`') {
            val run = if (source.startsWith("```", i)) 3 else 1
            val end = source.indexOf("`".repeat(run), i + run)
            if (end > i + run) {
                builder.withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x18000000))) { append(source.substring(i + run, end)) }
                i = end + run; continue
            }
        }

        val linkStart = source.indexOf('[', i).takeIf { it == i }
        if (linkStart != null) {
            val closeText = source.indexOf(']', i + 1)
            if (closeText >= 0 && source.startsWith("](", closeText)) {
                val closeUrl = source.indexOf(')', closeText + 2)
                if (closeUrl > closeText + 2) {
                    val label = source.substring(i + 1, closeText)
                    val url = source.substring(closeText + 2, closeUrl)
                    builder.pushStringAnnotation("URL", url)
                    builder.withStyle(SpanStyle(color = Color(0xFF176B70), textDecoration = TextDecoration.Underline)) { appendInline(builder, label) }
                    builder.pop()
                    i = closeUrl + 1; continue
                }
            }
        }

        val triple = if (source.startsWith("***", i)) "***" else if (source.startsWith("___", i)) "___" else null
        if (triple != null) {
            val end = source.indexOf(triple, i + 3)
            if (end > i + 3) {
                builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { appendInline(builder, source.substring(i + 3, end)) }
                i = end + 3; continue
            }
        }

        val strong = when {
            source.startsWith("**", i) -> "**"
            source.startsWith("__", i) -> "__"
            else -> null
        }
        if (strong != null) {
            val end = source.indexOf(strong, i + 2)
            if (end > i + 2) {
                builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(builder, source.substring(i + 2, end)) }
                i = end + 2; continue
            }
        }

        if (source.startsWith("~~", i)) {
            val end = source.indexOf("~~", i + 2)
            if (end > i + 2) {
                builder.withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInline(builder, source.substring(i + 2, end)) }
                i = end + 2; continue
            }
        }

        if (source[i] == '*' || source[i] == '_') {
            val delimiter = source[i].toString()
            val end = source.indexOf(delimiter, i + 1)
            if (end > i + 1 && !source.substring(i + 1, end).contains('\n')) {
                builder.withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { appendInline(builder, source.substring(i + 1, end)) }
                i = end + 1; continue
            }
        }

        val autoUrl = Regex("https?://[^\\s)]+", RegexOption.IGNORE_CASE).find(source, i)
        if (autoUrl?.range?.first == i) {
            builder.pushStringAnnotation("URL", autoUrl.value)
            builder.withStyle(SpanStyle(color = Color(0xFF176B70), textDecoration = TextDecoration.Underline)) { append(autoUrl.value) }
            builder.pop()
            i = autoUrl.range.last + 1; continue
        }

        builder.append(source[i])
        i++
    }
}

private fun normalizeLatex(value: String): String {
    var result = value.trim()
        .replace("\\cdot", "·")
        .replace("\\times", "×")
        .replace("\\div", "÷")
        .replace("\\leq", "≤")
        .replace("\\geq", "≥")
        .replace("\\neq", "≠")
        .replace("\\infty", "∞")
        .replace("\\rightarrow", "→")
        .replace("\\to", "→")
        .replace("\\pm", "±")
        .replace("\\alpha", "α")
        .replace("\\beta", "β")
        .replace("\\gamma", "γ")
        .replace("\\delta", "δ")
        .replace("\\theta", "θ")
        .replace("\\lambda", "λ")
        .replace("\\mu", "μ")
        .replace("\\pi", "π")
        .replace("\\sigma", "σ")
        .replace("\\phi", "φ")
        .replace("\\omega", "ω")
        .replace("\\text", "")
        .replace("\\left", "")
        .replace("\\right", "")
    result = Regex("\\\\frac\\s*\\{([^{}]*)}\\s*\\{([^{}]*)}").replace(result) { "${it.groupValues[1]}/${it.groupValues[2]}" }
    result = Regex("\\\\sqrt(?:\\s*\\[([^]]*)])?\\s*\\{([^{}]*)}").replace(result) { match ->
        val index = match.groupValues[1].takeIf { it.isNotBlank() }?.let { "^$it" }.orEmpty()
        "√$index(${match.groupValues[2]})"
    }
    result = result.replace("{", "").replace("}", "")
    result = result.replace("^", "˄").replace("_", "₋")
    return result.trim()
}

private fun detectCodeLanguage(code: String): String {
    val value = code.trimStart()
    return when {
        value.startsWith("{") || value.startsWith("[") -> "json"
        value.contains("fun ") || value.contains("val ") || value.contains("androidx.") -> "kotlin"
        value.contains("def ") || (value.contains("import ") && value.contains(" from ")) -> "python"
        value.contains("<html", ignoreCase = true) || value.contains("</") -> "html"
        value.contains("const ") || value.contains("=>") || value.contains("function ") -> "javascript"
        value.contains("SELECT ", ignoreCase = true) || value.contains(" FROM ", ignoreCase = true) -> "sql"
        else -> "code"
    }
}

private fun syntaxHighlight(code: String, language: String): AnnotatedString = buildAnnotatedString {
    val tokenRegex = Regex("(//.*|#.*|/\\*[\\s\\S]*?\\*/)|((?:\\\"(?:\\\\.|[^\\\"])*\\\")|(?:'(?:\\\\.|[^'])*'))|\\b(?:fun|val|var|class|interface|object|if|else|for|while|return|when|true|false|null|import|package|public|private|protected|override|suspend|const|new|def|return|function|let|const|async|await)\\b|\\b\\d+(?:\\.\\d+)?\\b")
    var cursor = 0
    tokenRegex.findAll(code).forEach { token ->
        append(code.substring(cursor, token.range.first))
        val style = when {
            token.value.startsWith("//") || token.value.startsWith("#") || token.value.startsWith("/*") -> SpanStyle(color = Color(0xFF66806B))
            token.value.startsWith("\"") || token.value.startsWith("'") -> SpanStyle(color = Color(0xFFB35B3E))
            token.value.firstOrNull()?.isDigit() == true -> SpanStyle(color = Color(0xFF7A55A3))
            else -> SpanStyle(color = Color(0xFF176B70), fontWeight = FontWeight.SemiBold)
        }
        withStyle(style) { append(token.value) }
        cursor = token.range.last + 1
    }
    append(code.substring(cursor))
}
