package ru.uvw.dshnc

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ru.uvw.dshnc.logic.AnswerMode
import ru.uvw.dshnc.logic.VoiceChatBus
import ru.uvw.dshnc.logic.VoiceInbox
import ru.uvw.dshnc.logic.VoiceMenu
import ru.uvw.dshnc.logic.VoiceMode
import ru.uvw.dshnc.logic.VoiceReplay
import ru.uvw.dshnc.store.TokenStore

/**
 * The voice chat's own settings, on a screen of their own.
 *
 * They used to live in the chat's overflow menu, which meant two things in one place: a menu that
 * answers «what do I do now» and settings that answer «how does this behave». A settings screen
 * keeps the chat free of a menu and gives the choices room for the explanation each of them needs.
 *
 * Every choice is written the moment it is tapped: there is nothing to save, and nothing restarts —
 * the mode and the answer form are read when a line is drawn, the synthesis when a take is sent.
 */
class VoiceSettingsActivity : AppCompatActivity() {
    private lateinit var store: TokenStore
    private lateinit var root: LinearLayout

    override fun onCreate (savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = TokenStore(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        root = Ui.screen(this, "Голосовой чат") { finish() }

        // a new conversation is an action, not a setting, but it has no other home now
        root.addView(Ui.item(this, "Новая сессия", "Начать разговор с чистого листа") { newSession() })
        root.addView(Ui.divider(this))

        group("Режим записи", listOf(
            Row("mode-hold", "Держите и говорите", "Говорите, удерживая кнопку с микрофоном нажатой.", VoiceMode.of(store.voiceMode) == VoiceMode.PTT),
            Row("mode-tap", "Тапните для записи", "Первый тап — включает запись, второй — завершает и отправляет голосовое сообщение.", VoiceMode.of(store.voiceMode) == VoiceMode.TOGGLE),
        ))

        val answer = AnswerMode.of(store.answerMode)
        group("Показывать ответы", listOf(
            Row("answer-short", AnswerMode.SHORT.title, null, answer == AnswerMode.SHORT),
            Row("answer-long", AnswerMode.LONG.title, null, answer == AnswerMode.LONG),
            Row("answer-both", AnswerMode.BOTH.title, null, answer == AnswerMode.BOTH),
        ))

        group("Способ синтеза звука", listOf(
            Row("synth-device", "TTS на Android", null, store.voiceSynth != "host"),
            Row("synth-host", "Синтез на DSH", null, store.voiceSynth == "host"),
        ))

        root.setPadding(0, 0, 0, pad)
        setContentView(android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })
    }

    /** One choice on the list: an id, a title, an explanation, and whether it is the current one. */
    private data class Row (val id: String, val title: String, val subtitle: String?, val checked: Boolean)

    /** A group: a heading and its choices, each with a mark when it is the current one. */
    private fun group (title: String, rows: List<Row>) {
        root.addView(Ui.group(this, title))
        for (row in rows) {
            root.addView(Ui.item(this, if (row.checked) "✓ ${row.title}" else row.title, row.subtitle) { choose(row.id) })
        }
    }

    /** Write the choice straight into the node state: no save button, no restart. */
    private fun choose (id: String) {
        when {
            id == "mode-hold" -> store.voiceMode = VoiceMode.PTT.wire
            id == "mode-tap" -> store.voiceMode = VoiceMode.TOGGLE.wire
            id == "answer-short" -> store.answerMode = AnswerMode.SHORT.wire
            id == "answer-long" -> store.answerMode = AnswerMode.LONG.wire
            id == "answer-both" -> store.answerMode = AnswerMode.BOTH.wire
            id == "synth-device" -> store.voiceSynth = "device"
            id == "synth-host" -> store.voiceSynth = "host"
            else -> return
        }
        store.addEvent("voice-settings", id)
        // redraw with the new mark, and let the chat redraw when it comes back
        recreate()
    }

    /** Start a new conversation on the host and drop the local history with it. */
    private fun newSession () {
        val base = store.base
        val token = store.token
        if (base == null || token == null) {
            toast("хост не ответил")
            return
        }
        Thread({
            val ok = runCatching {
                ru.uvw.dshnc.api.NodeApi(base).sendEvent(token, org.json.JSONObject().put("type", "new-session"))
                true
            }.getOrDefault(false)
            runOnUiThread {
                if (!ok) {
                    toast("хост не ответил")
                    return@runOnUiThread
                }
                VoiceInbox.clear()
                VoiceChatBus.clear()
                VoiceReplay.clear()
                toast("новая сессия")
            }
        }, "dshnc-voice-session").start()
    }

    private fun toast (text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
    }

}
