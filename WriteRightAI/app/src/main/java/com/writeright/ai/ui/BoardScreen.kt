package com.writeright.ai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.writeright.ai.analysis.MistakeAnalyzer
import com.writeright.ai.analysis.writtenMarks
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLayoutDirection
import com.writeright.ai.R
import com.writeright.ai.board.BoardMetrics
import com.writeright.ai.board.BoardViewModel
import com.writeright.ai.board.buildInkPath

private val InkStrokeWidth = 5.dp

@Composable
fun BoardScreen(vm: BoardViewModel) {
    val density = LocalDensity.current
    LaunchedEffect(density.density) { vm.density = density.density }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(Modifier.fillMaxSize().background(Brand.Paper)) {
            BoardCanvas(vm)
            if (vm.strokes.isEmpty() && vm.activePoints.isEmpty()) {
                EmptyHint(Modifier.align(Alignment.Center))
            }
            TopBar(vm, Modifier.align(Alignment.TopCenter))
            BottomBar(vm, Modifier.align(Alignment.BottomCenter))
        }

        vm.selectedIssue?.let { issue ->
            ExplainSheet(
                issue = issue,
                onSpeak = { vm.speak(issue.suggestion) },
                onRewrite = { vm.rewrite(issue) },
                onMarkCorrect = { vm.markCorrect(issue) },
                onDismiss = { vm.dismissIssue() },
            )
        }
    }
}

// -------------------------------------------------------------------------
// The board itself
// -------------------------------------------------------------------------

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BoardCanvas(vm: BoardViewModel) {
    val measurer = rememberTextMeasurer()
    val labelStyle = remember {
        TextStyle(
            fontFamily = FontFamily.Cursive,
            fontWeight = FontWeight.SemiBold,
            fontSize = 34.sp,
            color = Brand.GreenInk,
        )
    }

    val cleanStyle = remember {
        TextStyle(
            fontFamily = FontFamily.Cursive,
            fontWeight = FontWeight.SemiBold,
            fontSize = 34.sp,
            color = Brand.Ink,
        )
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged {
                vm.boardWidth = it.width.toFloat()
                vm.boardHeight = it.height.toFloat()
            }
            .pointerInput(vm) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    vm.onDown(down.position, down.uptimeMillis)
                    do {
                        val event = awaitPointerEvent()
                        for (change in event.changes) {
                            if (change.positionChanged()) {
                                for (h in change.historical) vm.onMove(h.position, h.uptimeMillis)
                                vm.onMove(change.position, change.uptimeMillis)
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    vm.onUp()
                }
            },
    ) {
        // ruled lines
        val spacing = BoardMetrics.LINE_SPACING_DP.dp.toPx()
        val offset = BoardMetrics.LINE_OFFSET_DP.dp.toPx()
        var y = offset + spacing - 1.dp.toPx()
        while (y < size.height) {
            drawLine(
                color = Brand.RuleLine,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 2.dp.toPx(),
            )
            y += spacing
        }

        val inkStyle = Stroke(width = InkStrokeWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

        // words that were read are shown as a clean handwritten word, the raw ink is hidden
        val converted = vm.convertedStrokeIds()

        // finished strokes
        for (s in vm.strokes) {
            if (s.id in converted) continue
            drawPath(s.path, color = Brand.Ink, style = inkStyle)
        }
        // the stroke that is being drawn right now
        if (vm.activePoints.isNotEmpty()) {
            drawPath(buildInkPath(vm.activePoints.toList()), color = Brand.Ink, style = inkStyle)
        }

        // clean handwriting: a correct word is black, a wrong letter turns red
        fun drawCleanWord(text: String, correct: String?, bounds: androidx.compose.ui.geometry.Rect) {
            val annotated = cleanWordText(text, correct)
            var fontPx = (bounds.height * 0.8f).coerceIn(30.dp.toPx(), 80.dp.toPx())
            var layout = measurer.measure(annotated, cleanStyle.copy(fontSize = fontPx.toSp()))
            val maxWidth = size.width - 28.dp.toPx()
            if (layout.size.width > maxWidth) {
                fontPx *= maxWidth / layout.size.width
                layout = measurer.measure(annotated, cleanStyle.copy(fontSize = fontPx.toSp()))
            }
            val lx = (bounds.center.x - layout.size.width / 2f)
                .coerceIn(8.dp.toPx(), (size.width - layout.size.width - 8.dp.toPx()).coerceAtLeast(8.dp.toPx()))
            val ly = bounds.center.y - layout.size.height / 2f
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(lx - 8.dp.toPx(), ly),
                size = Size(layout.size.width + 16.dp.toPx(), layout.size.height.toFloat()),
                cornerRadius = CornerRadius(10.dp.toPx()),
            )
            drawText(layout, topLeft = Offset(lx, ly))
        }

        for (recognized in vm.recognizedWords) {
            drawCleanWord(recognized.text, null, recognized.bounds)
        }
        for (issue in vm.issues) {
            drawCleanWord(issue.written, issue.suggestion, issue.bounds)
        }

        // Words recognized correctly are confirmed in green so the user sees live feedback.
        val confirmationStyle = labelStyle.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold)
        for (recognized in vm.recognizedWords) {
            val b = recognized.bounds
            val text = "${recognized.text} ✓"
            val layout = measurer.measure(text, confirmationStyle)
            val lx = b.left.coerceAtLeast(14.dp.toPx())
            val ly = (b.top - layout.size.height - 8.dp.toPx()).coerceAtLeast(8.dp.toPx())
            drawRoundRect(
                color = Brand.Mint.copy(alpha = 0.95f),
                topLeft = Offset(lx - 6.dp.toPx(), ly - 3.dp.toPx()),
                size = Size(layout.size.width + 12.dp.toPx(), layout.size.height + 6.dp.toPx()),
                cornerRadius = CornerRadius(10.dp.toPx()),
            )
            drawText(layout, topLeft = Offset(lx, ly), color = Brand.GreenInk)
        }

        // corrections written on the board
        for (issue in vm.issues) {
            val b = issue.bounds.inflate(6.dp.toPx())
            drawRoundRect(
                color = Brand.Coral,
                topLeft = b.topLeft,
                size = b.size,
                cornerRadius = CornerRadius(14.dp.toPx()),
                style = Stroke(width = 3.dp.toPx()),
            )

            val layout = measurer.measure(issue.suggestion, labelStyle)
            val lx = b.left.coerceAtLeast(14.dp.toPx())
            val ly = (b.top - layout.size.height - 4.dp.toPx()).coerceAtLeast(8.dp.toPx())
            drawRoundRect(
                color = Color.White.copy(alpha = 0.92f),
                topLeft = Offset(lx - 6.dp.toPx(), ly - 2.dp.toPx()),
                size = Size(layout.size.width + 12.dp.toPx(), layout.size.height + 4.dp.toPx()),
                cornerRadius = CornerRadius(10.dp.toPx()),
            )
            drawText(layout, topLeft = Offset(lx, ly))
        }

        // the electronic pen in the user's hand: its tip is exactly where the ink is being drawn
        if (vm.tool == BoardViewModel.Tool.Pen && vm.activePoints.isNotEmpty()) {
            val tip = vm.activePoints.last()
            val u = 1.dp.toPx()
            fun at(dx: Float, dy: Float) = Offset(tip.x + dx * u, tip.y + dy * u)
            val body = Path().apply {
                val p0 = at(3f, -12f)
                moveTo(p0.x, p0.y)
                at(42f, -52f).let { lineTo(it.x, it.y) }
                at(54f, -40f).let { lineTo(it.x, it.y) }
                at(14f, -3f).let { lineTo(it.x, it.y) }
                close()
            }
            val nib = Path().apply {
                moveTo(tip.x, tip.y)
                at(3f, -12f).let { lineTo(it.x, it.y) }
                at(14f, -3f).let { lineTo(it.x, it.y) }
                close()
            }
            drawPath(body, color = Brand.Mint)
            drawPath(body, color = Brand.Ink, style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round))
            drawPath(nib, color = Brand.Ink)
        }
    }
}

private val WrongRed = Color(0xFFE5392D)

/** The written word as clean text. Wrong letters are red, a missing letter is shown as a red underscore. */
private fun cleanWordText(text: String, correct: String?): AnnotatedString {
    val marks = if (correct != null) MistakeAnalyzer.writtenMarks(text, correct) else null
    val red = SpanStyle(color = WrongRed, fontWeight = FontWeight.Black)
    return buildAnnotatedString {
        text.forEachIndexed { i, ch ->
            if (marks != null && i in marks.gaps && i !in marks.wrong) withStyle(red) { append('_') }
            if (marks != null && i in marks.wrong) withStyle(red) { append(ch) } else append(ch)
        }
        if (marks != null && text.length in marks.gaps) withStyle(red) { append('_') }
    }
}

@Composable
private fun EmptyHint(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                text = "write anything...",
                style = TextStyle(
                    fontFamily = FontFamily.Cursive,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 48.sp,
                    color = Brand.Muted,
                ),
            )
        }
        Text(
            text = "اكتب أي كلمة أو جملة بالإنجليزي بإيدك، وأنا هصححها قدامك على اللوحة",
            style = TextStyle(fontSize = 16.sp, lineHeight = 26.sp, color = Brand.Muted),
            textAlign = TextAlign.Center,
        )
    }
}

// -------------------------------------------------------------------------
// Top bar
// -------------------------------------------------------------------------

@Composable
private fun TopBar(vm: BoardViewModel, modifier: Modifier = Modifier) {
    var showSettings by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 12.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SquareButton(R.drawable.ic_menu, "إعدادات الذكاء الاصطناعي") { showSettings = true }
            Wordmark()
            IssueChip(vm.issues.size)
        }
        StatusBanner(vm) { showSettings = true }
    }

    if (showSettings) {
        AiSettingsDialog(
            savedApiKey = vm.apiKeyForSettings(),
            savedModel = vm.aiModel,
            status = vm.aiStatus,
            onSave = { key, model -> vm.saveAiSettings(key, model) },
            onTest = { key, model, callback -> vm.testAiConnection(key, model, callback) },
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun Wordmark() {
    Box(
        modifier = Modifier
            .brandBox(Brand.Mint, 999.dp)
            .padding(horizontal = 18.dp, vertical = 6.dp),
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                text = "Write Right AI",
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Black, color = Brand.Ink),
            )
        }
    }
}

@Composable
private fun IssueChip(count: Int) {
    val bad = count > 0
    Box(
        modifier = Modifier
            .height(48.dp)
            .brandBox(if (bad) Brand.CoralSoft else Brand.MintSoft, 14.dp)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (bad) issueCountText(count) else "كله تمام",
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Brand.Ink),
        )
    }
}

private fun issueCountText(count: Int): String {
    val n = count.toString().map { '٠' + (it - '0') }.joinToString("")
    return when {
        count == 1 -> "غلطة واحدة"
        count == 2 -> "غلطتين"
        count in 3..10 -> "$n غلطات"
        else -> "$n غلطة"
    }
}

@Composable
private fun StatusBanner(vm: BoardViewModel, onOpenSettings: () -> Unit) {
    when {
        vm.modelStatus == BoardViewModel.ModelStatus.Downloading ->
            Pill("بنحمّل نموذج قراءة الخط (مرة واحدة بس)…", Brand.Yellow, null)

        vm.modelStatus == BoardViewModel.ModelStatus.NeedsInternet ->
            Pill("محتاج إنترنت عشان نحمّل نموذج قراءة الخط. اضغط للمحاولة", Brand.CoralSoft) { vm.retryModel() }

        vm.modelStatus == BoardViewModel.ModelStatus.Unsupported ->
            Pill("اللغة الإنجليزية مش متاحة في نموذج قراءة الخط", Brand.CoralSoft, null)

        vm.aiStatus.startsWith("تعذر") ->
            Pill(vm.aiStatus, Brand.CoralSoft, onOpenSettings)

        !vm.aiConfigured ->
            Pill("اضغط هنا لإضافة OpenRouter وتفعيل التصحيح الذكي", Brand.MintSoft, onOpenSettings)

        else -> Unit
    }
}

@Composable
private fun Pill(text: String, bg: Color, onClick: (() -> Unit)?) {
    val base = Modifier
        .fillMaxWidth()
        .brandBox(bg, 14.dp, shadow = 2.dp, border = 2.dp)
    Box(
        modifier = (if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Brand.Ink),
        )
    }
}

// -------------------------------------------------------------------------
// Bottom bar
// -------------------------------------------------------------------------

@Composable
private fun BottomBar(vm: BoardViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
    if (vm.aiFeedback.isNotBlank()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .brandBox(Brand.MintSoft, 14.dp, shadow = 2.dp, border = 2.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = vm.aiFeedback,
                style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = Brand.Ink),
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val statusText = when {
            vm.modelStatus != BoardViewModel.ModelStatus.Ready -> "جاهز بعد التحميل"
            vm.analyzing -> "بيراجع…"
            else -> if (vm.aiConfigured) vm.aiStatus.take(30) else "وصّل OpenRouter"
        }
        Row(
            modifier = Modifier
                .height(48.dp)
                .brandBox(Brand.Yellow, 999.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(10.dp).background(Brand.Ink, RoundedCornerShape(50)))
            Text(
                text = statusText,
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Brand.Ink),
            )
        }

        Row(
            modifier = Modifier
                .brandBox(Brand.Paper, 18.dp)
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolButton(R.drawable.ic_pen, "قلم", vm.tool == BoardViewModel.Tool.Pen) {
                vm.tool = BoardViewModel.Tool.Pen
            }
            ToolButton(R.drawable.ic_eraser, "ممحاة", vm.tool == BoardViewModel.Tool.Eraser) {
                vm.tool = BoardViewModel.Tool.Eraser
            }
            ToolButton(R.drawable.ic_undo, "تراجع", false) { vm.undo() }
            ToolButton(R.drawable.ic_trash, "امسح الكل", false) { vm.clearAll() }
        }
    }
    }
}

@Composable
private fun ToolButton(iconRes: Int, description: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(if (selected) Brand.Mint else Brand.Paper, shape)
            .border(3.dp, Brand.Ink, shape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = description,
            tint = Brand.Ink,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun SquareButton(iconRes: Int, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .brandBox(Brand.Paper, 14.dp)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = description,
            tint = Brand.Ink,
            modifier = Modifier.size(22.dp),
        )
    }
}
