package com.writeright.ai.board

import android.app.Application
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
import com.writeright.ai.analysis.MistakeAnalyzer
import com.writeright.ai.analysis.TextDistance
import com.writeright.ai.speech.Speaker
import com.writeright.ai.spell.SpellEngine
import kotlinx.coroutines.Job
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

    // ---- set by the UI ----
    var density = 1f
    var boardWidth = 1080f
    var boardHeight = 1920f

    // ---- internals ----
    private val spell = SpellEngine(app)
    private val speaker = Speaker(app)
    private var recognizer: DigitalInkRecognizer? = null
    private var nextId = 1
    private val ignored = HashSet<String>()
    private val cache = HashMap<String, Outcome>()
    private var analysisJob: Job? = null

    private var tapIssue: WordIssue? = null
    private var downPos = Offset.Zero
    private var moved = false

    init {
        viewModelScope.launch { prepareModel() }
    }

    // ---------------------------------------------------------------------
    // Handwriting model
    // ---------------------------------------------------------------------

    fun retryModel() {
        modelStatus = ModelStatus.Checking
        viewModelScope.launch { prepareModel() }
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
        cache.clear()
        ignored.clear()
        selectedIssue = null
        analysisJob?.cancel()
    }

    // ---------------------------------------------------------------------
    // Actions on a correction
    // ---------------------------------------------------------------------

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
        val rec = recognizer ?: return
        analyzing = true
        try {
            spellAvailable = spell.isAvailable()
            val snapshot = strokes.toList()
            val groups = WordSegmenter.group(
                snapshot,
                lineSpacing = BoardMetrics.LINE_SPACING_DP * density,
                lineOffset = BoardMetrics.LINE_OFFSET_DP * density,
                wordGap = BoardMetrics.WORD_GAP_DP * density,
            )
            val found = mutableListOf<WordIssue>()
            val recognized = mutableListOf<RecognizedWord>()
            for (g in groups) {
                if (g.signature in ignored) continue
                var outcome = cache[g.signature]
                if (outcome == null) {
                    outcome = try {
                        checkGroup(rec, g)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    if (outcome != null) cache[g.signature] = outcome
                }
                outcome?.issue?.let { found.add(it) }
                outcome?.recognized?.let { recognized.add(it) }
            }
            issues.clear()
            issues.addAll(found)
            recognizedWords.clear()
            recognizedWords.addAll(recognized)
        } finally {
            analyzing = false
        }
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

        val spelled = spell.check(word) ?: return null
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
