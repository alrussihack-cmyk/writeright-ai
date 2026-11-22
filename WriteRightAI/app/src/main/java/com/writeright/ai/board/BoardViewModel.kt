package com.writeright.ai.board

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.util.Base64
import java.io.ByteArrayOutputStream
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.google.mlkit.vision.digitalink.RecognitionContext
import com.google.mlkit.vision.digitalink.WritingArea
import com.writeright.ai.ai.AiWordReading
import com.writeright.ai.ai.AiSettingsStore
import com.writeright.ai.ai.OpenRouterClient
import com.writeright.ai.analysis.MistakeAnalyzer
import com.writeright.ai.analysis.TextDistance
import com.writeright.ai.speech.Speaker
import com.writeright.ai.spell.SpellEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.cancellation.CancellationException

class BoardViewModel(app: Application) : AndroidViewModel(app) {

    enum class Tool { Pen, Eraser }

    enum class ModelStatus { Checking, Downloading, Ready, NeedsInternet, Unsupported }

    /** Result of checking one group of strokes. A null issue means the word is fine. */
    private class Outcome(val issue: WordIssue?, val recognized: RecognizedWord? = null)

    // ---- state the UI reads ----
    val strokes = mutableStateListOf<StrokeData>()
    val activePoints = mutableStateListOf<InkPoint>()
    val issues = mutableStateListOf<WordIssue>()
    val recognizedWords = mutableStateListOf<RecognizedWord>()
    var tool by mutableStateOf(Tool.Pen)
    var selectedIssue by mutableStateOf<WordIssue?>(null)
    var modelStatus by mutableStateOf(ModelStatus.Checking)
    var analyzing by mutableStateOf(false)
    var spellAvailable by mutableStateOf(true)
    var aiConfigured by mutableStateOf(false)
    var aiModel by mutableStateOf(AiSettingsStore.DEFAULT_MODEL)
    var aiStatus by mutableStateOf("المدقق المحلي جاهز")
    var aiFeedback by mutableStateOf("")

    // ---- set by the UI ----
    var density = 1f
    var boardWidth = 1080f
    var boardHeight = 1920f

    // ---- internals ----
    private val spell = SpellEngine(app)
    private val aiSettings = AiSettingsStore(app)
    private val aiClient = OpenRouterClient()
    private val speaker = Speaker(app)
    private var recognizer: DigitalInkRecognizer? = null
    private var nextId = 1
    private val ignored = HashSet<String>()
    private val cache = HashMap<String, Outcome>()
    private val aiCache = HashMap<String, AiWordReading>()
    private var analysisJob: Job? = null

    private var tapIssue: WordIssue? = null
    private var downPos = Offset.Zero
    private var moved = false

    init {
        aiConfigured = aiSettings.hasApiKey()
        aiModel = aiSettings.model
        aiStatus = if (aiConfigured) "OpenRouter متصل بالإعدادات" else "أضف مفتاح OpenRouter لتفعيل التصحيح الذكي"
        viewModelScope.launch { prepareModel() }
    }

    // ---------------------------------------------------------------------
    // Handwriting model
    // ---------------------------------------------------------------------

    fun retryModel() {
        modelStatus = ModelStatus.Checking
        viewModelScope.launch { prepareModel() }
    }

    fun apiKeyForSettings(): String = aiSettings.getApiKey().orEmpty()

    fun saveAiSettings(apiKey: String, model: String) {
        try {
            aiSettings.saveApiKey(apiKey)
        } catch (e: Exception) {
            aiStatus = "تعذر حفظ المفتاح على الجهاز: ${e.message ?: "خطأ في التشفير"}"
            return
        }
        aiSettings.model = model
        aiModel = aiSettings.model
        aiConfigured = aiSettings.hasApiKey()
        aiStatus = if (aiConfigured) "إعدادات OpenRouter محفوظة" else "التصحيح المحلي فقط — أضف مفتاح API"
        aiFeedback = ""
        cache.clear()
        aiCache.clear()
        scheduleAnalysis()
    }

    fun testAiConnection(apiKey: String, model: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult("جاري اختبار الاتصال…")
            try {
                aiClient.test(apiKey.trim(), model.trim().ifBlank { AiSettingsStore.DEFAULT_MODEL })
                onResult("الاتصال ناجح ✓")
            } catch (e: Exception) {
                onResult("فشل الاتصال: ${e.message ?: "خطأ غير معروف"}")
            }
        }
    }

    private suspend fun prepareModel() {
        val id = try {
            DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US")
        } catch (e: MlKitException) {
            null
        }
        if (id == null) {
            modelStatus = ModelStatus.Unsupported
            return
        }
        val model = DigitalInkRecognitionModel.builder(id).build()
        val manager = RemoteModelManager.getInstance()
        try {
            val downloaded = manager.isModelDownloaded(model).await()
            if (!downloaded) {
                modelStatus = ModelStatus.Downloading
                manager.download(model, DownloadConditions.Builder().build()).await()
            }
            recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
            modelStatus = ModelStatus.Ready
            scheduleAnalysis()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            modelStatus = ModelStatus.NeedsInternet
        }
    }

    // ---------------------------------------------------------------------
    // Touch input
    // ---------------------------------------------------------------------

    fun onDown(pos: Offset, t: Long) {
        downPos = pos
        moved = false
        if (tool == Tool.Pen) {
            val hit = issues.firstOrNull { hitRect(it).contains(pos) }
            if (hit != null) {
                // touching a correction: a tap opens the explanation, nothing is drawn
                tapIssue = hit
                return
            }
            tapIssue = null
            activePoints.clear()
            activePoints.add(InkPoint(pos.x, pos.y, t))
        } else {
            tapIssue = null
            eraseAt(pos)
        }
    }

    fun onMove(pos: Offset, t: Long) {
        if (tapIssue != null) {
            if ((pos - downPos).getDistance() > 24f * density) moved = true
            return
        }
        if (tool == Tool.Pen) {
            activePoints.add(InkPoint(pos.x, pos.y, t))
        } else {
            eraseAt(pos)
        }
    }

    fun onUp() {
        val tap = tapIssue
        if (tap != null) {
            if (!moved) selectedIssue = tap
            tapIssue = null
            return
        }
        if (tool == Tool.Pen && activePoints.isNotEmpty()) {
            strokes.add(StrokeData(nextId++, activePoints.toList()))
            activePoints.clear()
        }
        scheduleAnalysis()
    }

    private fun hitRect(i: WordIssue): Rect = Rect(
        i.bounds.left - 8f * density,
        i.bounds.top - 80f * density,
        i.bounds.right + 8f * density,
        i.bounds.bottom + 8f * density,
    )

    private fun eraseAt(pos: Offset) {
        val r = BoardMetrics.ERASE_RADIUS_DP * density
        val r2 = r * r
        val removed = strokes.removeAll { s ->
            s.points.any { p ->
                val dx = p.x - pos.x
                val dy = p.y - pos.y
                dx * dx + dy * dy <= r2
            }
        }
        if (removed) dropStaleIssues()
    }

    // ---------------------------------------------------------------------
    // Toolbar actions
    // ---------------------------------------------------------------------

    fun undo() {
        if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex)
        scheduleAnalysis()
    }

    fun clearAll() {
        strokes.clear()
        activePoints.clear()
        issues.clear()
        recognizedWords.clear()
        aiFeedback = ""
        cache.clear()
        aiCache.clear()
        ignored.clear()
        selectedIssue = null
        analysisJob?.cancel()
    }

    // ---------------------------------------------------------------------
    // Actions on a correction
    // ---------------------------------------------------------------------

    /** Strokes that are shown as a clean handwritten word instead of the raw ink. */
    fun convertedStrokeIds(): Set<Int> {
        val ids = HashSet<Int>()
        for (issue in issues) ids.addAll(issue.strokeIds)
        for (word in recognizedWords) word.id.split(',').mapNotNull { it.toIntOrNull() }.forEach { ids.add(it) }
        return ids
    }

    fun speak(word: String) = speaker.speak(word)

    fun dismissIssue() {
        selectedIssue = null
    }

    /** Removes the strokes of the wrong word so it can be written again in the same place. */
    fun rewrite(issue: WordIssue) {
        strokes.removeAll { it.id in issue.strokeIds }
        selectedIssue = null
        dropStaleIssues()
        scheduleAnalysis()
    }

    /** The user says the word is right (the recognizer probably misread the handwriting). */
    fun markCorrect(issue: WordIssue) {
        ignored.add(issue.id)
        issues.remove(issue)
        recognizedWords.add(RecognizedWord(issue.id, issue.written, issue.bounds))
        selectedIssue = null
    }

    // ---------------------------------------------------------------------
    // Analysis
    // ---------------------------------------------------------------------

    private fun dropStaleIssues() {
        val alive = strokes.map { it.id }.toHashSet()
        issues.removeAll { issue -> issue.strokeIds.any { it !in alive } }
        recognizedWords.removeAll { word ->
            val ids = word.id.split(',').mapNotNull { it.toIntOrNull() }.toSet()
            ids.any { it !in alive }
        }
    }

    private fun scheduleAnalysis() {
        dropStaleIssues()
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            delay(900)
            analyze()
        }
    }

    private suspend fun analyze() {
        val rec = recognizer
        val hasKey = aiSettings.hasApiKey()
        if (rec == null && !hasKey) return
        analyzing = true
        try {
            spellAvailable = spell.isAvailable()
            val snapshot = strokes.toList()
            val groups = WordSegmenter.group(
                snapshot,
                lineSpacing = BoardMetrics.LINE_SPACING_DP * density,
                lineOffset = BoardMetrics.LINE_OFFSET_DP * density,
                wordGap = BoardMetrics.WORD_GAP_DP * density,
            ).filter {
                it.signature !in ignored &&
                    // a lone tap on the board is not a word
                    !(it.bounds.width < 10f * density && it.bounds.height < 10f * density)
            }

            // 1. the vision model reads every new word from its own picture (all words at the same time)
            var aiError: String? = null
            val pending = groups.filter { cache[it.signature] == null }
            if (hasKey && pending.isNotEmpty()) {
                aiStatus = "الذكاء الاصطناعي بيقرأ الكتابة…"
                val key = aiSettings.getApiKey().orEmpty()
                val model = aiSettings.model
                val results = coroutineScope {
                    pending.map { g ->
                        async {
                            try {
                                g to aiOutcome(key, model, g)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                aiError = e.message ?: "تحقق من المفتاح والإنترنت"
                                g to null
                            }
                        }
                    }.awaitAll()
                }
                for ((g, outcome) in results) if (outcome != null) cache[g.signature] = outcome
            }

            // 2. words the vision model could not give (no internet, no key...) use the on-device reader
            if (rec != null) {
                for (g in groups) {
                    if (cache[g.signature] != null) continue
                    val outcome = try {
                        checkGroup(rec, g)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    if (outcome != null) cache[g.signature] = outcome
                }
            }

            val found = mutableListOf<WordIssue>()
            val recognized = mutableListOf<RecognizedWord>()
            val sentence = mutableListOf<String>()
            for (g in groups) {
                val outcome = cache[g.signature] ?: continue
                outcome.issue?.let { found.add(it); sentence.add(it.suggestion) }
                outcome.recognized?.let { recognized.add(it); sentence.add(it.text) }
            }

            aiStatus = when {
                !hasKey -> "المدقق المحلي فقط — أضف مفتاح OpenRouter"
                aiError != null -> "تعذر فحص OpenRouter: $aiError"
                found.isNotEmpty() -> "اكتشف الذكاء الاصطناعي ${found.size} ملاحظة"
                recognized.isNotEmpty() -> "المراجعة اكتملت — الكتابة سليمة ✓"
                else -> aiStatus
            }
            aiFeedback = if (hasKey && found.isNotEmpty()) "الكتابة الصحيحة: " + sentence.joinToString(" ") else ""
            issues.clear()
            issues.addAll(found)
            recognizedWords.clear()
            recognizedWords.addAll(recognized)
        } finally {
            analyzing = false
        }
    }

    /** The vision model reads one word from its picture. Returns null when it could not read anything. */
    private suspend fun aiOutcome(key: String, model: String, g: WordGroup): Outcome? {
        val cacheKey = model + "|" + g.signature
        val reading = aiCache[cacheKey]
            ?: aiClient.readWord(key, model, renderBoardImage(g.strokes)).also { aiCache[cacheKey] = it }
        val written = reading.written.filter { it.isLetter() || it == '\'' }.trim('\'')
        val correct = reading.corrected.filter { it.isLetter() || it == '\'' }.trim('\'')
        if (written.isEmpty()) return null
        if (correct.isEmpty() || written == correct) {
            return Outcome(null, RecognizedWord(g.signature, written, g.bounds))
        }
        val base = MistakeAnalyzer.analyze(written, correct)
        return Outcome(
            WordIssue(
                id = g.signature,
                strokeIds = g.strokes.map { it.id }.toSet(),
                bounds = g.bounds,
                written = written,
                suggestion = correct,
                info = base.copy(tip = reading.explanation.ifBlank { base.tip }),
            )
        )
    }

    /** Returns null when the check could not be done (it will be tried again later). */
    private suspend fun checkGroup(rec: DigitalInkRecognizer, g: WordGroup): Outcome? {
        val inkBuilder = Ink.builder()
        for (s in g.strokes) {
            val sb = Ink.Stroke.builder()
            for (p in s.points) sb.addPoint(Ink.Point.create(p.x, p.y, p.t))
            inkBuilder.addStroke(sb.build())
        }
        val context = RecognitionContext.builder()
            .setWritingArea(WritingArea(boardWidth, boardHeight))
            .build()
        val result = rec.recognize(inkBuilder.build(), context).await()

        val raw = result.candidates.firstOrNull()?.text ?: return null
        val word = raw.filter { it.isLetter() || it == '\'' }.trim('\'')
        if (word.length < 2) return Outcome(null)

        val spelled = spell.check(word) ?: return Outcome(null, RecognizedWord(g.signature, word, g.bounds))
        if (spelled.isCorrect) return Outcome(null, RecognizedWord(g.signature, word, g.bounds))

        val best = pickBest(word, spelled.suggestions) ?: return Outcome(null)
        return Outcome(
            WordIssue(
                id = g.signature,
                strokeIds = g.strokes.map { it.id }.toSet(),
                bounds = g.bounds,
                written = word,
                suggestion = best,
                info = MistakeAnalyzer.analyze(word, best),
            )
        )
    }


    /** Renders the written strokes (cropped to the writing area) into an image for the vision model. */
    private fun renderBoardImage(snapshot: List<StrokeData>): String {
        val strokesToDraw = snapshot.filter { it.points.isNotEmpty() }
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (s in strokesToDraw) {
            left = minOf(left, s.bounds.left)
            top = minOf(top, s.bounds.top)
            right = maxOf(right, s.bounds.right)
            bottom = maxOf(bottom, s.bounds.bottom)
        }
        if (strokesToDraw.isEmpty()) {
            left = 0f; top = 0f; right = 1f; bottom = 1f
        }
        val pad = 32f * density
        left = (left - pad).coerceAtLeast(0f)
        top = (top - pad).coerceAtLeast(0f)
        right += pad
        bottom += pad
        val cropW = (right - left).coerceAtLeast(1f)
        val cropH = (bottom - top).coerceAtLeast(1f)
        // keep the image small enough to upload quickly but big enough to read
        val scale = (1280f / maxOf(cropW, cropH)).coerceIn(0.4f, 1.5f)
        val width = (cropW * scale).toInt().coerceAtLeast(1)
        val height = (cropH * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)
        canvas.drawColor(AndroidColor.WHITE)
        canvas.scale(scale, scale)
        canvas.translate(-left, -top)
        val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(16, 33, 28)
            style = AndroidPaint.Style.STROKE
            strokeWidth = 5f * density
            strokeCap = AndroidPaint.Cap.ROUND
            strokeJoin = AndroidPaint.Join.ROUND
        }
        for (stroke in strokesToDraw) {
            val path = AndroidPath()
            val first = stroke.points.first()
            path.moveTo(first.x, first.y)
            if (stroke.points.size == 1) {
                path.lineTo(first.x + 0.2f, first.y)
            } else {
                for (index in 1 until stroke.points.size) {
                    val previous = stroke.points[index - 1]
                    val current = stroke.points[index]
                    path.quadTo(previous.x, previous.y, (previous.x + current.x) / 2f, (previous.y + current.y) / 2f)
                }
                val last = stroke.points.last()
                path.lineTo(last.x, last.y)
            }
            canvas.drawPath(path, paint)
        }
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
        bitmap.recycle()
        return "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    /** The suggestion that is closest to what was written. */
    private fun pickBest(word: String, suggestions: List<String>): String? {
        val lower = word.lowercase()
        return suggestions
            .filter { it != word && it.none { ch -> ch == ' ' } }
            .map { it to TextDistance.osa(lower, it.lowercase()) }
            .filter { it.second <= 3 }
            .minByOrNull { it.second }
            ?.first
    }

    override fun onCleared() {
        speaker.shutdown()
        spell.close()
        recognizer?.close()
        super.onCleared()
    }
}
