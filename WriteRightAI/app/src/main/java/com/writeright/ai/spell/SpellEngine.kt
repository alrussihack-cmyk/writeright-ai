package com.writeright.ai.spell

import android.content.Context
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

data class SpellResult(val isCorrect: Boolean, val suggestions: List<String>)

/**
 * Checks one English word with the spell checker that is enabled on the phone
 * (for example Google Spell Checker). It works offline on most phones.
 * All calls must come from a coroutine, the work itself runs on the main thread.
 */
class SpellEngine(context: Context) {

    private val manager =
        context.applicationContext.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as TextServicesManager
    private val mutex = Mutex()
    private var session: SpellCheckerSession? = null
    private var pending: CancellableContinuation<SpellResult?>? = null

    private val listener = object : SpellCheckerSession.SpellCheckerSessionListener {
        override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
            // not used, we ask for sentence suggestions
        }

        override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
            val cont = pending ?: return
            pending = null
            val sentence = results?.firstOrNull()
            val info = if (sentence != null && sentence.suggestionsCount > 0) {
                sentence.getSuggestionsInfoAt(0)
            } else {
                null
            }
            val result = if (info == null) {
                null
            } else {
                val typo = (info.suggestionsAttributes and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) != 0
                val list = (0 until info.suggestionsCount).mapNotNull { info.getSuggestionAt(it) }
                SpellResult(isCorrect = !typo, suggestions = list)
            }
            if (cont.isActive) cont.resume(result)
        }
    }

    private fun ensureSession(): SpellCheckerSession? {
        session?.let { return it }
        val created = try {
            manager.newSpellCheckerSession(null, Locale.US, listener, false)
        } catch (e: Exception) {
            null
        }
        session = created
        return created
    }

    /** True when a spell checker is turned on in the phone settings. */
    fun isAvailable(): Boolean = ensureSession() != null

    /** Returns null when the checker is not available or did not answer in time. */
    suspend fun check(word: String): SpellResult? = mutex.withLock {
        withContext(Dispatchers.Main) {
            val s = ensureSession() ?: return@withContext null
            withTimeoutOrNull(2500) {
                suspendCancellableCoroutine<SpellResult?> { cont ->
                    pending = cont
                    cont.invokeOnCancellation { pending = null }
                    s.getSentenceSuggestions(arrayOf(TextInfo(word)), 5)
                }
            }
        }
    }

    fun close() {
        session?.close()
        session = null
    }
}
