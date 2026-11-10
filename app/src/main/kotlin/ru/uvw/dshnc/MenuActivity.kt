package ru.uvw.dshnc

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
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
        root.addView(Ui.item(this, getString(R.string.menu_auth), getString(R.string.menu_auth_sub)) { open("auth") })
        root.addView(Ui.divider(this))
        root.addView(Ui.item(this, getString(R.string.menu_net), getString(R.string.menu_net_sub)) { open("net") })

        root.addView(Ui.group(this, getString(R.string.menu_group_phone)))
        root.addView(Ui.item(this, getString(R.string.menu_android), getString(R.string.menu_android_sub)) { open("android") })

        setContentView(root)
    }

    private fun open (key: String) {
        startActivity(Intent(this, SectionActivity::class.java).putExtra("section", key))
    }
}
