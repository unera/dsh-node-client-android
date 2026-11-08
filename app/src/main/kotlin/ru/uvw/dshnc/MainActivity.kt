package ru.uvw.dshnc

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Экран узла: ссылка подключения, состояние, кнопка отключения.
 *
 * Пока это только каркас: транспорт появится следующим шагом, и тогда же
 * состояние начнёт меняться само.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.title)
            textSize = 20f
        })
        val link = EditText(this).apply {
            hint = getString(R.string.link_hint)
            setSingleLine()
        }
        root.addView(link)
        root.addView(Button(this).apply {
            text = getString(R.string.connect)
            setOnClickListener { status.text = getString(R.string.status_disconnected) }
        })
        status = TextView(this).apply { text = getString(R.string.status_disconnected) }
        root.addView(status)
        setContentView(root)
    }
}
