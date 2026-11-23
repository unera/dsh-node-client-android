package ru.uvw.dshnc

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity

/**
 * Node menu: a list of sections, as in the system settings.
 *
 * Only the items live here; each one's content is a separate screen with a back arrow.
 */
class MenuActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = Ui.screen(this, getString(R.string.menu_title)) { finish() }

        root.addView(Ui.group(this, getString(R.string.menu_group_node)))
        root.addView(Ui.item(this, "Наименование", "Имя устройства и алиасы: скачать с сервера или отправить на него") {
            startActivity(Intent(this, NamingActivity::class.java))
        })
        root.addView(Ui.divider(this))
        root.addView(Ui.item(this, getString(R.string.menu_auth), getString(R.string.menu_auth_sub)) { open("auth") })
        root.addView(Ui.divider(this))
        root.addView(Ui.item(this, getString(R.string.menu_net), getString(R.string.menu_net_sub)) { open("net") })

        root.addView(Ui.group(this, getString(R.string.menu_group_phone)))
        root.addView(Ui.item(this, "Интеграция с андроид", "Разрешения системы: файлы, поверх приложений, батарея") { open("perm") })
        root.addView(Ui.divider(this))
        root.addView(Ui.item(this, "Разрешения", "Здесь можно ограничить перечень действий, доступных DSH по управлению данным устройством") { open("grants") })
        root.addView(Ui.divider(this))
        // the voice chat has enough choices of its own to deserve a screen
        root.addView(Ui.item(this, "Голосовой чат", "Режим записи, показ ответов, синтез") {
            startActivity(android.content.Intent(this, VoiceSettingsActivity::class.java))
        })
        root.addView(Ui.divider(this))
        root.addView(Ui.item(this, "Автоматическое обновление программы", updateSubtitle()) {
            open("update")
        })

        // Прокрутка обязательна: пунктов уже больше, чем помещается на экран, и без неё нижние
        // просто недостижимы — не «неудобно», а именно недоступны.
        setContentView(ScrollView(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#141414"))
            addView(root)
        })
    }

    /** Что показать в меню про обновления: расписание, и не ждёт ли установки скачанное. */
    private fun updateSubtitle (): String {
        val pending = ru.uvw.dshnc.store.TokenStore(this).pendingUpdateVersion
        if (pending != null) return "скачано $pending · ждёт установки"
        val interval = ru.uvw.dshnc.logic.Updater.Interval.of(
            ru.uvw.dshnc.store.TokenStore(this).updateInterval,
        )
        return if (interval == ru.uvw.dshnc.logic.Updater.Interval.NEVER) "проверка выключена"
        else "проверять ${interval.label}"
    }

    private fun open (key: String) {
        startActivity(Intent(this, SectionActivity::class.java).putExtra("section", key))
    }
}
