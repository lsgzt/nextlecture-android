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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
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
    val blocks = remember(markdown) {
        runCatching { parseRichMarkdown(markdown) }
            .getOrElse { listOf(RichBlock.Paragraph(markdown)) }
    }
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
    val annotated = remember(text, style) {
        runCatching { inlineAnnotated(text) }.getOrElse { AnnotatedString(safeInlineFallback(text)) }
    }
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
                val formulaParts = splitFormulaContinuation(item.text)
                if (formulaParts == null) {
                    RichInlineText(item.text, MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp))
                } else {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RichInlineText(formulaParts.label, MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp))
                        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            MathLayout(formulaParts.formula, display = false)
                        }
                    }
                }
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
    val clipboard = LocalClipboardManager.current
    var copied by remember(expression) { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // High-fidelity rendered formula
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.15f)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                MathLayout(expression, display)
            }
        }
        
        // Raw LaTeX code block (collapsible or small)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "latex",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace
                )
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(expression))
                        copied = true
                    },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                expression,
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            )
        }
    }
}

@Composable
private fun MathLayout(expression: String, display: Boolean) {
    val formula = remember(expression) { parseFormula(expression) }
    val style = if (display) {
        MaterialTheme.typography.headlineSmall.copy(
            fontFamily = FontFamily.Serif,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            lineHeight = 34.sp
        )
    } else {
        MaterialTheme.typography.titleMedium.copy(
            fontFamily = FontFamily.Serif,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            lineHeight = 28.sp
        )
    }
    if (formula.children.isEmpty()) {
        Text(safeNormalizeLatex(expression), style = style, softWrap = false)
    } else {
        FormulaNodeView(formula, style)
    }
}

@Composable
private fun FormulaNodeView(node: FormulaNode, style: TextStyle) {
    when (node) {
        is FormulaNode.Sequence -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            node.children.forEach { FormulaNodeView(it, style) }
        }
        is FormulaNode.Text -> Text(node.value, style = style, softWrap = false)
        is FormulaNode.Fraction -> FormulaFraction(node.numerator, node.denominator, style)
        is FormulaNode.Root -> FormulaRoot(node.index, node.radicand, style)
        is FormulaNode.Script -> Row(verticalAlignment = Alignment.CenterVertically) {
            FormulaNodeView(node.base, style)
            Column(
                modifier = Modifier.padding(start = 1.dp),
                verticalArrangement = Arrangement.Center
            ) {
                node.superscript?.let { FormulaNodeView(it, style.copy(fontSize = (style.fontSize.value * 0.58f).sp, lineHeight = 14.sp)) }
                node.subscript?.let { FormulaNodeView(it, style.copy(fontSize = (style.fontSize.value * 0.58f).sp, lineHeight = 14.sp)) }
            }
        }
    }
}

@Composable
private fun FormulaFraction(numerator: FormulaNode, denominator: FormulaNode, style: TextStyle) {
    val barColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
    Layout(
        content = {
            Box(Modifier.height(1.dp).background(barColor))
            FormulaNodeView(numerator, style.copy(fontSize = (style.fontSize.value * 0.78f).sp, lineHeight = 20.sp))
            FormulaNodeView(denominator, style.copy(fontSize = (style.fontSize.value * 0.78f).sp, lineHeight = 20.sp))
        }
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0, maxWidth = Constraints.Infinity, maxHeight = Constraints.Infinity)
        val numeratorPlaceable = measurables[1].measure(loose)
        val denominatorPlaceable = measurables[2].measure(loose)
        val width = maxOf(numeratorPlaceable.width, denominatorPlaceable.width).coerceAtLeast(2.dp.roundToPx())
        val barPlaceable = measurables[0].measure(Constraints.fixedWidth(width))
        val gap = 2.dp.roundToPx()
        val height = numeratorPlaceable.height + gap + barPlaceable.height + gap + denominatorPlaceable.height
        layout(width, height) {
            numeratorPlaceable.placeRelative((width - numeratorPlaceable.width) / 2, 0)
            barPlaceable.placeRelative(0, numeratorPlaceable.height + gap)
            denominatorPlaceable.placeRelative((width - denominatorPlaceable.width) / 2, numeratorPlaceable.height + gap + barPlaceable.height + gap)
        }
    }
}

@Composable
private fun FormulaRoot(index: FormulaNode?, radicand: FormulaNode, style: TextStyle) {
    Row(verticalAlignment = Alignment.Bottom) {
        Box(contentAlignment = Alignment.TopStart) {
            index?.let {
                FormulaNodeView(it, style.copy(fontSize = (style.fontSize.value * 0.48f).sp, lineHeight = 12.sp))
            }
            Text("√", style = style.copy(fontSize = (style.fontSize.value * 1.28f).sp, lineHeight = style.lineHeight))
        }
        Column(
            modifier = Modifier.padding(start = 2.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f))
            FormulaNodeView(radicand, style)
        }
    }
}

internal sealed interface FormulaNode {
    data class Sequence(val children: List<FormulaNode>) : FormulaNode
    data class Text(val value: String) : FormulaNode
    data class Fraction(val numerator: FormulaNode, val denominator: FormulaNode) : FormulaNode
    data class Root(val index: FormulaNode?, val radicand: FormulaNode) : FormulaNode
    data class Script(
        val base: FormulaNode,
        val superscript: FormulaNode?,
        val subscript: FormulaNode?
    ) : FormulaNode
}

private data class FormulaContinuation(val label: String, val formula: String)

private fun splitFormulaContinuation(text: String): FormulaContinuation? {
    val lines = text.split('\n')
    if (lines.size < 2) return null
    val label = lines.first().trim()
    if (!label.contains("rendered formula", ignoreCase = true)) return null
    val formula = lines.drop(1).joinToString("\n").trim()
    return formula.takeIf { it.isNotBlank() }?.let { FormulaContinuation(label, it) }
}

internal fun parseFormula(source: String): FormulaNode.Sequence {
    return runCatching { FormulaParser(source).parse() }
        .getOrElse { FormulaNode.Sequence(listOf(FormulaNode.Text(safeNormalizeLatex(source)))) }
}

private class FormulaParser(private val source: String) {
    private var index = 0

    fun parse(): FormulaNode.Sequence = parseSequence(stopAtClosingBrace = false)

    private fun parseSequence(stopAtClosingBrace: Boolean): FormulaNode.Sequence {
        val children = mutableListOf<FormulaNode>()
        while (index < source.length) {
            when (val char = source[index]) {
                '}' -> {
                    if (stopAtClosingBrace) {
                        index++
                        return FormulaNode.Sequence(children)
                    }
                    index++
                }
                '{' -> {
                    index++
                    children += parseSequence(stopAtClosingBrace = true)
                }
                '^', '_' -> {
                    val isSuperscript = char == '^'
                    index++
                    val script = parseScriptArgument()
                    val base = children.removeLastOrNull()
                    if (base == null) {
                        children += FormulaNode.Text(if (isSuperscript) "^" else "_")
                    } else {
                        val existing = base as? FormulaNode.Script
                        if (existing != null) {
                            children += if (isSuperscript) existing.copy(superscript = script) else existing.copy(subscript = script)
                        } else {
                            children += FormulaNode.Script(
                                base = base,
                                superscript = script.takeIf { isSuperscript },
                                subscript = script.takeIf { !isSuperscript }
                            )
                        }
                    }
                }
                '\\' -> children += parseCommand()
                else -> {
                    val start = index
                    while (index < source.length && source[index] !in charArrayOf('\\', '{', '}', '^', '_')) index++
                    children += FormulaNode.Text(source.substring(start, index))
                }
            }
        }
        return FormulaNode.Sequence(children)
    }

    private fun parseScriptArgument(): FormulaNode {
        while (index < source.length && source[index].isWhitespace()) index++
        if (index >= source.length) return FormulaNode.Text("")
        return if (source[index] == '{') {
            index++
            parseSequence(stopAtClosingBrace = true)
        } else if (source[index] == '\\') {
            parseCommand()
        } else {
            FormulaNode.Text(source[index++].toString())
        }
    }

    private fun parseCommand(): FormulaNode {
        if (index >= source.length || source[index] != '\\') return FormulaNode.Text("")
        index++
        if (index >= source.length) return FormulaNode.Text("\\")
        if (!source[index].isLetter()) return FormulaNode.Text(source[index++].toString())
        val start = index
        while (index < source.length && source[index].isLetter()) index++
        val command = source.substring(start, index)
        return when (command) {
            "frac", "dfrac", "tfrac" -> FormulaNode.Fraction(parseRequiredGroup(), parseRequiredGroup())
            "sqrt" -> {
                skipWhitespace()
                val optionalIndex = if (index < source.length && source[index] == '[') {
                    index++
                    val optionalStart = index
                    while (index < source.length && source[index] != ']') index++
                    val rawIndex = source.substring(optionalStart, index)
                    if (index < source.length) index++
                    parseFormula(rawIndex)
                } else null
                FormulaNode.Root(optionalIndex, parseRequiredGroup())
            }
            "mathbf", "boldsymbol", "mathrm", "mathit", "text", "operatorname", "vec", "overline", "bar", "underline", "mathbb" -> parseRequiredGroup()
            "left", "right" -> FormulaNode.Text("")
            else -> FormulaNode.Text(formulaSymbol(command))
        }
    }

    private fun parseRequiredGroup(): FormulaNode {
        skipWhitespace()
        return if (index < source.length && source[index] == '{') {
            index++
            parseSequence(stopAtClosingBrace = true)
        } else {
            parseScriptArgument()
        }
    }

    private fun skipWhitespace() {
        while (index < source.length && source[index].isWhitespace()) index++
    }
}

private fun formulaSymbol(command: String): String = when (command) {
    "cdot" -> "·"; "cdots" -> "⋯"; "ldots" -> "…"; "times" -> "×"; "div" -> "÷"
    "leq", "le" -> "≤"; "geq", "ge" -> "≥"; "neq" -> "≠"; "approx" -> "≈"; "equiv" -> "≡"
    "pm" -> "±"; "mp" -> "∓"; "infty" -> "∞"; "rightarrow", "longrightarrow", "to" -> "→"; "leftarrow" -> "←"
    "nabla" -> "∇"; "partial" -> "∂"; "ell" -> "ℓ"; "sum" -> "Σ"; "int" -> "∫"; "prod" -> "Π"
    "alpha" -> "α"; "beta" -> "β"; "gamma" -> "γ"; "delta" -> "δ"; "epsilon", "varepsilon" -> "ε"
    "theta", "vartheta" -> "θ"; "lambda" -> "λ"; "mu" -> "μ"; "nu" -> "ν"; "xi" -> "ξ"; "pi" -> "π"
    "rho" -> "ρ"; "sigma" -> "σ"; "phi", "varphi" -> "φ"; "omega" -> "ω"
    "Gamma" -> "Γ"; "Delta" -> "Δ"; "Theta" -> "Θ"; "Lambda" -> "Λ"; "Xi" -> "Ξ"; "Pi" -> "Π"; "Sigma" -> "Σ"; "Phi" -> "Φ"; "Psi" -> "Ψ"; "Omega" -> "Ω"
    "in" -> "∈"; "notin" -> "∉"; "subseteq" -> "⊆"; "subset" -> "⊂"; "cup" -> "∪"; "cap" -> "∩"; "forall" -> "∀"; "exists" -> "∃"
    "quad" -> " "; "qquad" -> "  "
    "lim" -> "lim"
    else -> command
}

@Composable
private fun RichTable(table: RichBlock.Table) {
    val rawColumnCount = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
    if (rawColumnCount > 12) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            RichInlineText(
                buildString {
                    append(table.header.joinToString(" | "))
                    table.rows.forEach { append("\n").append(it.joinToString(" | ")) }
                },
                MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp)
            )
        }
        return
    }
    val columnCount = rawColumnCount
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
            while (i < lines.size) {
                val candidate = lines[i].trim()
                if (candidate.isBlank() || isTableDivider(candidate)) break
                if (candidate.contains('|')) {
                    val parsedRow = splitTableRow(candidate)
                    val looksLikeRow = parsedRow.size >= 2 && (header.size <= 1 || parsedRow.size >= header.size - 1)
                    if (looksLikeRow) {
                        rows += parsedRow
                        i++
                        continue
                    }
                }
                val lastCell = rows.lastOrNull()?.lastOrNull().orEmpty()
                if (isTableContinuation(candidate, lastCell)) {
                    val previous = rows.removeLastOrNull()?.toMutableList()
                    if (previous != null) {
                        val cellIndex = continuationCellIndex(previous)
                        previous[cellIndex] = listOf(previous[cellIndex], candidate).filter { it.isNotBlank() }.joinToString("\n")
                        rows += previous
                        i++
                        continue
                    }
                }
                break
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

private fun parseList(lines: List<String>): List<RichListItem> {
    val items = mutableListOf<RichListItem>()
    val itemPattern = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.+)$")
    lines.forEach { line ->
        val match = itemPattern.find(line)
        if (match != null) {
            val indent = match.groupValues[1].replace("\t", "    ").length
            val marker = match.groupValues[2]
            items += RichListItem(indent / 2, marker.first().isDigit(), marker, match.groupValues[3])
        } else if (items.isNotEmpty() && line.isNotBlank()) {
            // Gemini often places a displayed equation on an indented line below a label.
            // Keep that continuation attached to the preceding list item instead of dropping it.
            val previous = items.removeAt(items.lastIndex)
            items += previous.copy(text = previous.text + "\n" + line.trim())
        }
    }
    return items
}

private fun looksLikeTableHeader(line: String): Boolean = line.count { it == '|' } >= 1

private fun isTableContinuation(line: String, previousCell: String): Boolean {
    if (line.startsWith("#") || line.matches(Regex("^(---+|\\*\\*\\*+|___+)$"))) return false
    val isBullet = line.startsWith("•") || line.matches(Regex("^(?:[-*+]\\s+|\\d+[.)]\\s+).+"))
    val previousStartsList = previousCell.trimStart().matches(Regex("^(?:[-*+]\\s+|\\d+[.)]\\s+|•).+"))
    return isBullet || previousStartsList
}

private fun continuationCellIndex(row: List<String>): Int {
    val descriptiveIndex = row.drop(1).indexOfFirst { cell ->
        val value = cell.trim()
        value.isNotBlank() && value.firstOrNull()?.isDigit() != true
    }
    return if (descriptiveIndex >= 0) descriptiveIndex + 1 else row.lastIndex.coerceAtLeast(0)
}

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

private const val MAX_INLINE_DEPTH = 24
private val AUTO_URL_REGEX = Regex("https?://[^\\s)]+", RegexOption.IGNORE_CASE)

private fun inlineAnnotated(value: String): AnnotatedString = buildAnnotatedString {
    appendInline(this, value, depth = 0)
}

private val BARE_LATEX_COMMANDS = setOf(
    "frac", "dfrac", "tfrac", "sqrt", "mathbf", "boldsymbol", "mathrm", "mathit", "text", "operatorname", "vec",
    "overline", "bar", "underline", "cdot", "cdots", "ldots", "times", "div", "leq", "le", "geq", "ge", "neq",
    "approx", "equiv", "pm", "mp", "infty", "rightarrow", "longrightarrow", "to", "leftarrow", "nabla", "partial",
    "ell", "sum", "int", "prod", "alpha", "beta", "gamma", "delta", "epsilon", "varepsilon", "theta", "lambda",
    "mu", "nu", "xi", "pi", "rho", "sigma", "phi", "omega", "in", "notin", "subseteq", "subset", "cup", "cap",
    "forall", "exists"
)

private fun looksLikeBareLatex(source: String, start: Int): Boolean {
    if (start + 1 >= source.length || source[start] != '\\' || !source[start + 1].isLetter()) return false
    var end = start + 1
    while (end < source.length && source[end].isLetter()) end++
    return source.substring(start + 1, end) in BARE_LATEX_COMMANDS
}

private fun appendInline(
    builder: androidx.compose.ui.text.AnnotatedString.Builder,
    rawSource: String,
    depth: Int
) {
    val source = rawSource.replace("\\\\", "\\").replace("\\$", "$")
    if (depth > MAX_INLINE_DEPTH) {
        builder.append(source)
        return
    }
    var i = 0
    while (i < source.length) {
        if (source[i] == '\\' && i + 1 < source.length) {
            val bareFormulaEnd = if (looksLikeBareLatex(source, i)) {
                source.indexOf('\n', i).takeIf { it >= 0 } ?: source.length
            } else {
                -1
            }
            if (bareFormulaEnd > i) {
                builder.withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = Color(0xFF176B70))) {
                    append(safeNormalizeLatex(source.substring(i, bareFormulaEnd)))
                }
                i = bareFormulaEnd
                continue
            }
            val mathDelimiter = when {
                source.startsWith("\\(", i) -> "\\)"
                source.startsWith("\\[", i) -> "\\]"
                else -> null
            }
            if (mathDelimiter != null) {
                val start = i + 2
                val end = source.indexOf(mathDelimiter, start)
                if (end >= 0) {
                    builder.withStyle(
                        SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = Color(0xFF176B70))
                    ) { append(safeNormalizeLatex(source.substring(start, end))) }
                    i = end + mathDelimiter.length
                    continue
                }
            }
            builder.append(source[i + 1]); i += 2; continue
        }

        val mathDollar = source[i] == '$' && i + 1 < source.length && source[i + 1] != ' '
        if (mathDollar) {
            val delimiterLength = if (source.startsWith("$$", i)) 2 else 1
            val delimiter = "$".repeat(delimiterLength)
            val end = source.indexOf(delimiter, i + delimiterLength)
            if (end > i + delimiterLength) {
                builder.withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = Color(0xFF176B70))) {
                    append(safeNormalizeLatex(source.substring(i + delimiterLength, end)))
                }
                i = end + delimiterLength
                continue
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

        if (source[i] == '[') {
            val closeText = source.indexOf(']', i + 1)
            if (closeText >= 0 && source.startsWith("](", closeText)) {
                val closeUrl = source.indexOf(')', closeText + 2)
                if (closeUrl > closeText + 2) {
                    val label = source.substring(i + 1, closeText)
                    val url = source.substring(closeText + 2, closeUrl)
                    builder.pushStringAnnotation("URL", url)
                    builder.withStyle(SpanStyle(color = Color(0xFF176B70), textDecoration = TextDecoration.Underline)) { appendInline(builder, label, depth + 1) }
                    builder.pop()
                    i = closeUrl + 1; continue
                }
            }
        }

        val triple = if (source.startsWith("***", i)) "***" else if (source.startsWith("___", i)) "___" else null
        if (triple != null) {
            val end = source.indexOf(triple, i + 3)
            if (end > i + 3) {
                builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { appendInline(builder, source.substring(i + 3, end), depth + 1) }
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
                builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(builder, source.substring(i + 2, end), depth + 1) }
                i = end + 2; continue
            }
        }

        if (source.startsWith("~~", i)) {
            val end = source.indexOf("~~", i + 2)
            if (end > i + 2) {
                builder.withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInline(builder, source.substring(i + 2, end), depth + 1) }
                i = end + 2; continue
            }
        }

        if (source[i] == '*' || source[i] == '_') {
            val delimiter = source[i].toString()
            val end = source.indexOf(delimiter, i + 1)
            if (end > i + 1 && !source.substring(i + 1, end).contains('\n')) {
                builder.withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { appendInline(builder, source.substring(i + 1, end), depth + 1) }
                i = end + 1; continue
            }
        }

        val autoUrl = if (source.startsWith("http://", i, ignoreCase = true) || source.startsWith("https://", i, ignoreCase = true)) {
            AUTO_URL_REGEX.find(source, i)
        } else {
            null
        }
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

private fun safeInlineFallback(value: String): String {
    val plain = safeNormalizeLatex(value)
        .replace("**", "")
        .replace("__", "")
        .replace("~~", "")
        .replace("`", "")
    return plain.lineSequence().joinToString("\n") { line ->
        line.trimStart().let { current ->
            if (current.startsWith("#")) current.dropWhile { it == '#' || it == ' ' } else current
        }
    }
}

private fun safeNormalizeLatex(value: String): String = try {
    normalizeLatex(value)
} catch (_: Exception) {
    value.replace("\\\\", "\\").replace("\\$", "$")
}

internal fun normalizeLatex(value: String): String {
    var result = value.trim()
        .replace("\\\\", "\\")
        .replace("\\$", "$")

    // Consume brace-based commands before removing braces. This keeps \frac{a}{b},
    // \sqrt{x}, and styled arguments intact even when the formula is streamed halfway.
    result = replaceLatexCommand(result, "frac", 2) { args ->
        "(${normalizeLatex(args[0])})⁄(${normalizeLatex(args[1])})"
    }
    result = replaceLatexCommand(result, "dfrac", 2) { args ->
        "(${normalizeLatex(args[0])})⁄(${normalizeLatex(args[1])})"
    }
    result = replaceLatexCommand(result, "tfrac", 2) { args ->
        "(${normalizeLatex(args[0])})⁄(${normalizeLatex(args[1])})"
    }
    result = replaceLatexCommand(result, "sqrt", 1) { args ->
        "√(${normalizeLatex(args[0])})"
    }
    listOf("mathbf", "boldsymbol", "mathrm", "mathit", "text", "operatorname", "vec", "overline", "bar", "underline").forEach { command ->
        result = replaceLatexCommand(result, command, 1) { args -> args[0] }
    }

    val symbols = linkedMapOf(
        "\\cdot" to "·", "\\cdots" to "⋯", "\\ldots" to "…", "\\times" to "×", "\\div" to "÷",
        "\\leq" to "≤", "\\le" to "≤", "\\geq" to "≥", "\\ge" to "≥", "\\neq" to "≠",
        "\\approx" to "≈", "\\equiv" to "≡", "\\pm" to "±", "\\mp" to "∓", "\\infty" to "∞",
        "\\rightarrow" to "→", "\\longrightarrow" to "⟶", "\\to" to "→", "\\leftarrow" to "←",
        "\\nabla" to "∇", "\\partial" to "∂", "\\ell" to "ℓ", "\\sum" to "Σ", "\\int" to "∫", "\\prod" to "Π",
        "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ", "\\epsilon" to "ε",
        "\\varepsilon" to "ε", "\\zeta" to "ζ", "\\eta" to "η", "\\theta" to "θ", "\\vartheta" to "ϑ",
        "\\iota" to "ι", "\\kappa" to "κ", "\\lambda" to "λ", "\\mu" to "μ", "\\nu" to "ν",
        "\\xi" to "ξ", "\\omicron" to "ο", "\\pi" to "π", "\\varpi" to "ϖ", "\\rho" to "ρ",
        "\\sigma" to "σ", "\\tau" to "τ", "\\upsilon" to "υ", "\\phi" to "φ", "\\varphi" to "ϕ",
        "\\chi" to "χ", "\\psi" to "ψ", "\\omega" to "ω", "\\Gamma" to "Γ", "\\Delta" to "Δ",
        "\\Theta" to "Θ", "\\Lambda" to "Λ", "\\Xi" to "Ξ", "\\Pi" to "Π", "\\Sigma" to "Σ",
        "\\Phi" to "Φ", "\\Psi" to "Ψ", "\\Omega" to "Ω", "\\in" to "∈", "\\notin" to "∉",
        "\\subseteq" to "⊆", "\\subset" to "⊂", "\\cup" to "∪", "\\cap" to "∩", "\\forall" to "∀",
        "\\exists" to "∃", "\\quad" to " ", "\\qquad" to "  ", "\\," to " ", "\\;" to " ", "\\!" to ""
    )
    symbols.forEach { (command, replacement) -> result = result.replace(command, replacement) }
    result = convertLatexScripts(result)
    result = result.replace("{", "").replace("}", "")
    result = stripUnknownLatexCommands(result)
    return result.trim()
}

private fun convertLatexScripts(source: String): String {
    val output = StringBuilder(source.length)
    var index = 0
    while (index < source.length) {
        val marker = source[index]
        if ((marker == '^' || marker == '_') && index + 1 < source.length) {
            val next = index + 1
            val content: String?
            val endExclusive: Int
            if (source[next] == '{') {
                val close = matchingBrace(source, next)
                if (close > next) {
                    content = source.substring(next + 1, close)
                    endExclusive = close + 1
                } else {
                    content = null
                    endExclusive = next
                }
            } else if (!source[next].isWhitespace() && source[next] != '\\') {
                content = source[next].toString()
                endExclusive = next + 1
            } else {
                content = null
                endExclusive = next
            }
            if (content != null) {
                output.append(if (marker == '^') toSuperscript(content) else toSubscript(content))
                index = endExclusive
                continue
            }
        }
        output.append(source[index])
        index++
    }
    return output.toString()
}

private fun stripUnknownLatexCommands(source: String): String {
    val output = StringBuilder(source.length)
    var index = 0
    while (index < source.length) {
        if (source[index] == '\\' && index + 1 < source.length) {
            val next = source[index + 1]
            if (next.isLetter()) {
                index++
                continue
            }
            if (!next.isWhitespace()) {
                output.append(next)
                index += 2
                continue
            }
        }
        output.append(source[index])
        index++
    }
    return output.toString()
}

private fun toSuperscript(value: String): String = value.map { SUPERSCRIPT[it] ?: it }.joinToString("")
private fun toSubscript(value: String): String = value.map { SUBSCRIPT[it] ?: it }.joinToString("")

private val SUPERSCRIPT = mapOf(
    '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
    '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾', 'n' to 'ⁿ', 'i' to 'ⁱ'
)
private val SUBSCRIPT = mapOf(
    '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
    '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎', 'n' to 'ₙ', 'i' to 'ᵢ'
)

private fun replaceLatexCommand(
    source: String,
    command: String,
    argumentCount: Int,
    format: (List<String>) -> String
): String {
    val token = "\\" + command
    val output = StringBuilder(source.length)
    var i = 0
    while (i < source.length) {
        if (source.startsWith(token, i)) {
            var cursor = i + token.length
            val args = mutableListOf<String>()
            repeat(argumentCount) {
                while (cursor < source.length && source[cursor].isWhitespace()) cursor++
                if (cursor >= source.length || source[cursor] != '{') return@repeat
                val end = matchingBrace(source, cursor)
                if (end <= cursor) return@repeat
                args += source.substring(cursor + 1, end)
                cursor = end + 1
            }
            if (args.size == argumentCount) {
                output.append(format(args))
                i = cursor
                continue
            }
        }
        output.append(source[i])
        i++
    }
    return output.toString()
}

private fun matchingBrace(source: String, start: Int): Int {
    var depth = 0
    var escaped = false
    for (index in start until source.length) {
        val char = source[index]
        if (escaped) {
            escaped = false
            continue
        }
        if (char == '\\') {
            escaped = true
            continue
        }
        if (char == '{') depth++
        if (char == '}') {
            depth--
            if (depth == 0) return index
        }
    }
    return -1
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
