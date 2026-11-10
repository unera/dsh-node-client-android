package ru.uvw.dshnc

import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ru.uvw.dshnc.store.TokenStore

/**
 * Node settings: what keeps the channel alive, how often to poll the queue, and what is
 * shared.
 *
 * Ping here is about detecting a break, not about delivery: commands arrive in the
 * `state` frame. That is why "polling" is off by default — it is kept only as a
 * fallback in case the socket does not come up.
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var store: TokenStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = TokenStore(this)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply { text = "Настройки"; textSize = 20f })

        val ping = field(root, "пинг, с (0 — не пинговать)", store.pingInterval.toString())
        val factor = field(root, "растяжение без работы, раз", store.pingFactor.toString())
        val poll = field(root, "запасной опрос, с (0 — выключен)", store.pollFallback.toString())

        root.addView(TextView(this).apply {
            text = "\nРазрешённые каталоги:"
            setPadding(0, pad, 0, 0)
        })
        val rootsView = TextView(this).apply { text = store.roots.joinToString("\n") }
        root.addView(rootsView)

        val addPath = EditText(this).apply {
            hint = "/storage/emulated/0/DCIM"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(addPath)
        root.addView(Button(this).apply {
            text = "добавить каталог"
            setOnClickListener {
                val path = addPath.text.toString().trim()
                if (path.isNotEmpty()) {
                    store.roots = (store.roots + path).distinct()
                    rootsView.text = store.roots.joinToString("\n")
                    addPath.setText("")
                }
            }
        })
        root.addView(Button(this).apply {
            text = "убрать последний"
            setOnClickListener {
                store.roots = store.roots.dropLast(1)
                rootsView.text = store.roots.joinToString("\n")
            }
        })

        val debug = android.widget.CheckBox(this).apply {
            text = "разрешить session.debug_info"
            isChecked = store.debugInfo
            setPadding(0, pad, 0, 0)
        }
        root.addView(debug)

        root.addView(Button(this).apply {
            text = "сохранить и перезапустить узел"
            setOnClickListener {
                store.pingInterval = ping.text.toString().toIntOrNull() ?: 15
                store.pingFactor = factor.text.toString().toIntOrNull() ?: 10
                store.pollFallback = poll.text.toString().toIntOrNull() ?: 0
                store.debugInfo = debug.isChecked
                ru.uvw.dshnc.service.NodeService.stop(this@SettingsActivity)
                ru.uvw.dshnc.service.NodeService.start(this@SettingsActivity)
                finish()
            }
        })
        setContentView(root)
    }

    private fun field(root: LinearLayout, label: String, value: String): EditText {
        root.addView(TextView(this).apply {
            text = label
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
        })
        val input = EditText(this).apply {
            setText(value)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(input)
        return input
    }
}
