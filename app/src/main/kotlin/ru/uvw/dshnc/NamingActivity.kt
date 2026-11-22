package ru.uvw.dshnc

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import ru.uvw.dshnc.api.ApiException
import ru.uvw.dshnc.api.NodeApi
import ru.uvw.dshnc.logic.Naming
import ru.uvw.dshnc.store.TokenStore

/**
 * The node's naming: its name and the aliases it answers to.
 *
 * Three things live on this screen and they are deliberately separate:
 *
 * - the fields change the copy kept on the phone. Nothing leaves the device: a rename here is a
 *   local rename, and the node is still called whatever the server has on its side;
 * - "скачать настройки с сервера" replaces the local copy with the server's;
 * - "отправить настройки на сервер" replaces the server's copy with the local one.
 *
 * The exchange is two explicit presses because both directions overwrite somebody's work, and a
 * device silently renaming itself on a server — or silently losing a name a person typed — is
 * exactly the kind of surprise an explicit button prevents.
 */
class NamingActivity : AppCompatActivity() {
    private lateinit var store: TokenStore
    private lateinit var nameField: EditText
    private lateinit var aliasField: EditText
    private lateinit var status: TextView

    override fun onCreate (savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = TokenStore(this)
        val pad = (20 * resources.displayMetrics.density).toInt()

        val root = Ui.screen(this, "Наименование") { finish() }

        root.addView(Ui.group(this, "Имя устройства"))
        root.addView(Ui.item(this, "Как звать этот узел",
            "Имя, под которым узел виден на сервере. Правка здесь меняет только телефон — на сервер она попадёт кнопкой ниже.",
            null))
        nameField = field(Naming.localName(store.name, android.os.Build.MODEL), singleLine = true)
        root.addView(nameField)
        root.addView(Ui.divider(this))

        root.addView(Ui.group(this, "Алиасы"))
        root.addView(Ui.item(this, "Другие имена",
            "Через запятую: по любому из них узел можно позвать. Имя устройства сервер добавит сам.",
            null))
        aliasField = field(Naming.formatAliases(Naming.withoutName(store.aliases, store.name ?: "")), singleLine = false)
        root.addView(aliasField)

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#9ca3af"))
            setPadding(pad, pad / 2, pad, 0)
        }
        root.addView(status)

        root.addView(Ui.group(this, "Обмен с сервером"))
        root.addView(Ui.button(this, "Скачать настройки с сервера") { download() })
        root.addView(Ui.button(this, "Отправить настройки на сервер") { upload() })

        root.addView(TextView(this).apply {
            text = "Скачивание заменит имя и алиасы здесь тем, что записано на сервере. " +
                "Отправка заменит ими то, что записано на сервере. Алиасы, занятые другими узлами, " +
                "сервер не примет и перечислит в ответе."
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#8b8b8b"))
            setPadding(pad, pad, pad, pad)
        })

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })

        // the fields write straight into the phone's copy: there is nothing to save, and no
        // button stands between a typed word and the thing it changes
        nameField.addTextChangedListener(watch { text ->
            store.name = text.trim().ifBlank { null }
        })
        aliasField.addTextChangedListener(watch { text ->
            store.aliases = Naming.withoutName(Naming.parseAliases(text), nameField.text.toString())
        })

        render("телефон: ${store.name ?: "—"}")
    }

    private fun field (initial: String, singleLine: Boolean): EditText {
        val pad = (20 * resources.displayMetrics.density).toInt()
        return EditText(this).apply {
            setText(initial)
            textSize = 16f
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.parseColor("#6b7280"))
            if (singleLine) {
                isSingleLine = true
                imeOptions = EditorInfo.IME_ACTION_DONE
            } else {
                maxLines = 4
                gravity = Gravity.TOP
            }
            setPadding(pad, pad / 2, pad, pad / 2)
        }
    }

    private fun watch (apply: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged (s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged (s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged (s: Editable?) {
            apply(s?.toString().orEmpty())
        }
    }

    /** The server's copy replaces the local one, field by field. */
    private fun download () {
        val (base, token) = connected() ?: return
        render("скачиваю с сервера…")
        Thread {
            val outcome = runCatching { NodeApi(base).naming(token) }
            runOnUiThread {
                outcome.onSuccess { answer ->
                    val name = answer.optString("name").trim()
                    val aliases = strings(answer, "aliases")
                    if (name.isNotEmpty()) store.name = name
                    store.aliases = Naming.withoutName(aliases, name)
                    show(name, store.aliases)
                    render("скачано с сервера: ${name.ifBlank { "—" }}")
                }.onFailure { error -> render(refusal(error)) }
            }
        }.start()
    }

    /** The local copy replaces the server's one. */
    private fun upload () {
        val (base, token) = connected() ?: return
        val name = Naming.localName(store.name, android.os.Build.MODEL)
        val aliases = Naming.parseAliases(aliasField.text.toString())
        render("отправляю на сервер…")
        Thread {
            val outcome = runCatching { NodeApi(base).setNaming(token, name, aliases) }
            runOnUiThread {
                outcome.onSuccess { answer ->
                    val rejected = strings(answer, "rejected")
                    render(
                        if (rejected.isEmpty()) "отправлено: ${answer.optString("name").ifBlank { name }}"
                        else "отправлено, но заняты другими узлами: ${rejected.joinToString(", ")}",
                    )
                }.onFailure { error -> render(refusal(error)) }
            }
        }.start()
    }

    private fun connected (): Pair<String, String>? {
        val base = store.base
        val token = store.token
        if (base == null || token == null) {
            render("узел не подключён: сначала нужна ссылка из админки")
            return null
        }
        return base to token
    }

    /** Put the server's copy into the fields without pretending it was typed. */
    private fun show (name: String, aliases: List<String>) {
        nameField.setText(name)
        aliasField.setText(Naming.formatAliases(Naming.withoutName(aliases, name)))
    }

    private fun strings (answer: JSONObject, key: String): List<String> {
        val array = answer.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
    }

    /** A refusal in the server's own words: it knows what exactly is wrong. */
    private fun refusal (error: Throwable): String = when (error) {
        is ApiException -> error.message
        else -> error.message ?: error.javaClass.simpleName
    }

    private fun render (text: String) {
        status.text = text
    }
}
