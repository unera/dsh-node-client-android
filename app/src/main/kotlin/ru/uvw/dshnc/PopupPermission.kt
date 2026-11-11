package ru.uvw.dshnc

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import ru.uvw.dshnc.logic.Popups

/**
 * The Android side of the full screen question: the platform says whether it is allowed and
 * knows the screen where the person changes it. The rule itself lives in [Popups].
 */
object PopupPermission {
    /** The decision, asked the same way by the settings row and the notification builder. */
    fun allowed (context: Context): Boolean {
        // before Android 14 nobody is asked: the plain "yes" would need a manager lookup
        // on a platform that does not have that method at all
        val granted = if (Build.VERSION.SDK_INT < Popups.FIRST_SDK) {
            true
        } else {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.canUseFullScreenIntent()
        }
        return Popups.allowed(Build.VERSION.SDK_INT, granted)
    }

    /** The system screen that grants it: there is no screen of our own to build for this. */
    fun open (context: Context) {
        if (Build.VERSION.SDK_INT < Popups.FIRST_SDK) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")),
            )
        }
    }
}
