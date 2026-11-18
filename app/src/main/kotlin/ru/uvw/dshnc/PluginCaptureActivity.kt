package ru.uvw.dshnc

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import ru.uvw.dshnc.logic.BundleJson
import ru.uvw.dshnc.store.TokenStore
import java.io.File

/**
 * Asks a plugin to configure one action, and keeps what it answers.
 *
 * The Locale/Tasker protocol works by starting an app's edit activity for result: the person
 * configures the action there, and the app returns a Bundle that a plugin host is supposed to
 * replay later. We have no plugin host — so this activity plays one for a single round: it starts
 * the app's screen, waits for the answer, writes the Bundle out as JSON and disappears.
 *
 * What it buys: the exact shape of a silent command, learned instead of guessed. Without it a
 * client like v2rayTun can only be toggled by its deep link, which brings its window forward.
 */
class PluginCaptureActivity : AppCompatActivity() {
    private var targetClass: String? = null
    private var file: String? = null

    override fun onCreate (savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetClass = intent.getStringExtra("targetClass")
        file = intent.getStringExtra("file")
        val packageName = intent.getStringExtra("targetPackage")
        val target = targetClass
        if (packageName.isNullOrBlank() || target.isNullOrBlank()) {
            finish()
            return
        }
        if (savedInstanceState != null) return
        val edit = Intent(TAG_EDIT_SETTING).apply {
            setClassName(packageName, target)
            // The protocol hands the plugin an empty Bundle and a blurb: that is how a host says
            // "configure this from scratch" rather than "edit something you already know".
            putExtra(TAG_BUNDLE, Bundle())
            putExtra(TAG_BLURB, "")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivityForResult(edit, REQUEST) }.onFailure { write(null, it.message ?: "не удалось открыть") }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult (requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST) return
        val bundle = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            data?.getBundleExtra(TAG_BUNDLE) ?: data?.extras
        } else {
            @Suppress("DEPRECATION")
            data?.getBundleExtra(TAG_BUNDLE) ?: data?.extras
        }
        val blurb = data?.getStringExtra(TAG_BLURB)
        write(bundle, blurb)
        finish()
    }

    /**
     * Put the answer where the host can read it.
     *
     * A file, not a payload: the node answers commands synchronously, and this result arrives
     * minutes later, after the person has tapped around in someone else's settings screen.
     */
    private fun write (bundle: Bundle?, note: String?) {
        val path = file ?: run {
            val roots = TokenStore(this).roots
            val root = roots.firstOrNull() ?: filesDir.absolutePath
            File(root, "plugin-capture.json").absolutePath
        }
        val payload = JSONObject()
            .put("result", JSONObject().put("note", note ?: ""))
            .put("blurb", note ?: "")
            .put("extras", BundleJson.of(bundle))
            .put("capturedAt", System.currentTimeMillis())
        runCatching { File(path).writeText(payload.toString(2)) }
    }

    private companion object {
        const val REQUEST = 4711
        const val TAG_EDIT_SETTING = "com.twofortyfouram.locale.intent.action.EDIT_SETTING"
        const val TAG_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"
        const val TAG_BLURB = "com.twofortyfouram.locale.intent.extra.BLURB"
    }
}
