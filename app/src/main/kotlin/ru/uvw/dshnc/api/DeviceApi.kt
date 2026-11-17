package ru.uvw.dshnc.api

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Device alarms.
 *
 * What an ordinary app can do: find out the next alarm and ask the system clock to set
 * or dismiss an alarm. The person's full list of alarms cannot be read — no such public
 * API exists, and we will not pretend that it does.
 */
class DeviceApi (private val context: Context) {
    fun schema (): JSONObject = JSONObject()
        .put("name", "android")
        .put("version", NodeApi.VERSION)
        .put("methods", JSONArray()
            .put(method("alarm_next", "Next alarm set on the device (time and app)",
                JSONObject().put("type", "object").put("properties", JSONObject())))
            .put(method("alarm_set", "Ask the system clock to set an alarm",
                JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("hour", JSONObject().put("type", "integer"))
                        .put("minute", JSONObject().put("type", "integer"))
                        .put("label", JSONObject().put("type", "string"))
                        .put("skipUi", JSONObject().put("type", "boolean")))
                    .put("required", JSONArray().put("hour").put("minute"))))
            .put(method("device_status", "Battery, network and screen state right now",
                JSONObject().put("type", "object").put("properties", JSONObject())))
            .put(method("device_info", "Model, Android version, locale, screen",
                JSONObject().put("type", "object").put("properties", JSONObject())))
            .put(method("device_health", "Storage and memory: free and total",
                JSONObject().put("type", "object").put("properties", JSONObject())))
            .put(method("camera_snap", "Take a photo silently; returns the file path",
                JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("camera", JSONObject().put("type", "string"))
                        .put("dir", JSONObject().put("type", "string")))))
            .put(method("photos_latest", "Newest photos from DCIM (path, size, mtime)",
                JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("limit", JSONObject().put("type", "integer"))
                        .put("dir", JSONObject().put("type", "string")))))
            .put(method("apps", "Installed apps that can be launched (package and label)",
                JSONObject().put("type", "object").put("properties", JSONObject())))
            .put(method("alarm_dismiss", "Ask the system clock to dismiss the next alarm",
                JSONObject().put("type", "object").put("properties", JSONObject())))
            .put(method("intent_query", "Which apps would open this intent (package, label)",
                intentArgs(requireData = false)))
            .put(method("intent_start", "Open something by intent: action, data URI, extras",
                intentArgs(requireData = false)))
            .put(method("intent_open", "Open a URL or URI with whatever app handles it",
                intentArgs(requireData = true))))

    /** The argument shape every intent method shares. */
    private fun intentArgs (requireData: Boolean): JSONObject {
        val properties = JSONObject()
            .put("action", JSONObject().put("type", "string"))
            .put("data", JSONObject().put("type", "string"))
            .put("type", JSONObject().put("type", "string"))
            .put("package", JSONObject().put("type", "string"))
            .put("class", JSONObject().put("type", "string"))
            .put("extras", JSONObject().put("type", "object"))
        val schema = JSONObject().put("type", "object").put("properties", properties)
        if (requireData) schema.put("required", JSONArray().put("data"))
        return schema
    }

    private fun method (name: String, description: String, args: JSONObject): JSONObject = JSONObject()
        .put("method", name)
        .put("description", description)
        .put("args", args)
        .put("returns", "object")

    fun handle (method: String, args: JSONObject, commandSeconds: Double = 0.0): JSONObject = try {
        when (method) {
            "device_status" -> deviceStatus()
            "device_info" -> deviceInfo()
            "device_health" -> deviceHealth()
            "camera_snap" -> CameraApi(context).snap(args, commandSeconds)
            "photos_latest" -> photosLatest(args)
            "apps" -> apps()
            "alarm_next" -> alarmNext()
            "alarm_set" -> alarmSet(args)
            "alarm_dismiss" -> alarmDismiss()
            "intent_query" -> intentQuery(args)
            "intent_start" -> intentStart(args)
            "intent_open" -> intentStart(args)
            else -> error("unknown method $method")
        }
    } catch (error: Throwable) {
        JSONObject().put("status", "error").put("message", error.message ?: error.javaClass.simpleName)
    }

    /** The state right now: battery, charging, network, screen, uptime. */
    private fun deviceStatus (): JSONObject {
        val battery = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL
        val power = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = connectivity.activeNetwork
        val caps = network?.let { connectivity.getNetworkCapabilities(it) }
        val kind = when {
            caps == null -> "нет"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильная"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "другая"
        }
        return ok(JSONObject()
            .put("battery", if (level >= 0) level * 100 / scale else -1)
            .put("charging", charging)
            .put("network", kind)
            .put("screenOn", power.isInteractive)
            .put("uptimeSeconds", android.os.SystemClock.elapsedRealtime() / 1000))
    }

    /** Who this is: model, Android version, language, screen. */
    private fun deviceInfo (): JSONObject {
        val metrics = context.resources.displayMetrics
        return ok(JSONObject()
            .put("model", android.os.Build.MODEL)
            .put("manufacturer", android.os.Build.MANUFACTURER)
            .put("android", android.os.Build.VERSION.RELEASE)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("app", NodeApi.VERSION)
            .put("locale", java.util.Locale.getDefault().toString())
            .put("timezone", java.util.TimeZone.getDefault().id)
            .put("screen", "${metrics.widthPixels}x${metrics.heightPixels}")
            .put("density", metrics.densityDpi))
    }

    /** How much space and memory is left: without it "the device is lagging" cannot be explained. */
    private fun deviceHealth (): JSONObject {
        val data = android.os.Environment.getDataDirectory()
        val stat = android.os.StatFs(data.path)
        val memory = android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(memory)
        return ok(JSONObject()
            .put("storageFreeMb", stat.availableBytes / (1024 * 1024))
            .put("storageTotalMb", stat.totalBytes / (1024 * 1024))
            .put("memoryFreeMb", memory.availMem / (1024 * 1024))
            .put("memoryLow", memory.lowMemory))
    }

    /**
     * The latest photos from DCIM.
     *
     * A separate media library permission is not needed here: with "access to all files"
     * granted, DCIM is read as an ordinary directory. MediaStore would be needed only to
     * see other people's albums and cloud collections.
     */
    private fun photosLatest (args: JSONObject): JSONObject {
        val root = java.io.File(args.optString("dir").ifBlank { "/storage/emulated/0/DCIM" })
        if (!root.isDirectory) return error("каталога ${root.path} нет")
        val limit = args.optInt("limit", 10).coerceIn(1, 50)
        val found = mutableListOf<java.io.File>()
        val queue = ArrayDeque<java.io.File>().apply { add(root) }
        while (queue.isNotEmpty() && found.size < 5000) {
            val dir = queue.removeFirst()
            val entries = dir.listFiles() ?: continue
            for (entry in entries) {
                if (entry.isDirectory) queue.add(entry) else if (isImage(entry.name)) found.add(entry)
            }
        }
        val newest = found.sortedByDescending { it.lastModified() }.take(limit)
        val list = org.json.JSONArray()
        for (file in newest) {
            list.put(JSONObject()
                .put("path", file.absolutePath)
                .put("bytes", file.length())
                .put("mtime", file.lastModified()))
        }
        return ok(JSONObject().put("count", list.length()).put("photos", list))
    }

    private fun isImage (name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")
    }

    /** What is installed and can be launched: the package and a human-readable name. */
    private fun apps (): JSONObject {
        val manager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = org.json.JSONArray()
        for (info in manager.queryIntentActivities(intent, 0)) {
            list.put(JSONObject()
                .put("package", info.activityInfo.packageName)
                .put("label", info.loadLabel(manager).toString()))
        }
        return ok(JSONObject().put("count", list.length()).put("apps", list))
    }

    /** The next alarm: time, label and the app that set it. */
    private fun alarmNext (): JSONObject {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val clock = manager.nextAlarmClock
            ?: return ok(JSONObject().put("set", false))
        return ok(JSONObject()
            .put("set", true)
            .put("at", clock.triggerTime)
            .put("triggeredBy", if (clock.showIntent == null) "unknown" else "app"))
    }

    /** Set an alarm: without skipUi the system clock asks the person. */
    private fun alarmSet (args: JSONObject): JSONObject {
        // launching someone else's screen from the background is allowed only with this access,
        // otherwise the system silently opens nothing
        if (!android.provider.Settings.canDrawOverlays(context)) {
            error("нужен доступ «поверх других приложений»: без него Android гасит запуск часов из фона")
        }
        val hour = args.optInt("hour", -1)
        val minute = args.optInt("minute", -1)
        if (hour !in 0..23 || minute !in 0..59) error("нужны hour 0..23 и minute 0..59")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, args.optBoolean("skipUi", false))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        args.optString("label").takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        context.startActivity(intent)
        return ok(JSONObject().put("requested", true).put("hour", hour).put("minute", minute))
    }

    /** Dismiss the next alarm: also through the system clock. */
    private fun alarmDismiss (): JSONObject {
        val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM)
            .putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ok(JSONObject().put("requested", true))
    }

    /**
     * Turn one request into an Intent the system understands.
     *
     * The rules live in `IntentRequest` and are covered by unit tests; this is only the bridge to
     * Android. `FLAG_ACTIVITY_NEW_TASK` is not decoration: the node is a service, and starting an
     * activity without it throws from anything but an activity context.
     */
    private fun intentOf (args: JSONObject): Intent {
        val request = ru.uvw.dshnc.logic.IntentRequest.parse(plainMap(args))
        val intent = Intent(request.action)
        request.data?.let { intent.data = android.net.Uri.parse(it) }
        request.type?.let { intent.type = it }
        request.packageName?.let { intent.setPackage(it) }
        request.className?.let { intent.setClassName(request.packageName ?: context.packageName, it) }
        for ((key, value) in request.extras) {
            when (value) {
                is String -> intent.putExtra(key, value)
                is Boolean -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
                is Long -> intent.putExtra(key, value)
                is Double -> intent.putExtra(key, value)
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    /** The JSON arguments as the plain map the request parser takes. */
    private fun plainMap (args: JSONObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (key in args.keys()) {
            when (val value = args.opt(key)) {
                is JSONObject -> {
                    val nested = LinkedHashMap<String, Any?>()
                    for (inner in value.keys()) nested[inner] = value.opt(inner)
                    out[key] = nested
                }
                JSONObject.NULL -> out[key] = null
                else -> out[key] = value
            }
        }
        return out
    }

    /** Who would open this: the answer the person needs before a launch surprises them. */
    private fun intentQuery (args: JSONObject): JSONObject {
        val intent = intentOf(args)
        val manager = context.packageManager
        val found = org.json.JSONArray()
        for (info in manager.queryIntentActivities(intent, 0)) {
            found.put(JSONObject()
                .put("package", info.activityInfo.packageName)
                .put("activity", info.activityInfo.name)
                .put("label", info.loadLabel(manager).toString()))
        }
        val chosen = intent.resolveActivity(manager)
        return ok(JSONObject()
            .put("count", found.length())
            .put("apps", found)
            .put("default", chosen?.let { "${it.packageName}/${it.className}" } ?: JSONObject.NULL))
    }

    /**
     * Start it.
     *
     * The system decides what answers, and it decides silently: from the background an activity
     * start needs the "display over other apps" access, exactly like the alarm clock, so the
     * refusal is checked here and said in words instead of turning into an app that never opens.
     */
    private fun intentStart (args: JSONObject): JSONObject {
        val intent = intentOf(args)
        val manager = context.packageManager
        val chosen = manager.resolveActivity(intent, 0)
        if (chosen == null) {
            // an explicit package that is installed but declares nothing is a different story
            val packageName = intent.`package`
            val installed = packageName != null &&
                runCatching { manager.getPackageInfo(packageName, 0) }.isSuccess
            if (!installed) error("некому открыть: подходящего приложения нет")
        }
        // The whitelist from the "Разрешения" screen: empty means any app, filled means only
        // those. Checked after the system has named who would answer, because a package the
        // caller pinned is not necessarily the one that opens.
        val target = chosen?.activityInfo?.packageName ?: intent.`package`
        val allowed = ru.uvw.dshnc.store.TokenStore(context).apps
        if (allowed.isNotEmpty() && target != null && !allowed.contains(target)) {
            error("приложению $target не разрешено открываться по запросу: добавьте его в «Разрешения» → «Кому можно открываться»")
        }
        if (!android.provider.Settings.canDrawOverlays(context)) {
            error("нужен доступ «поверх других приложений»: без него Android гасит запуск из фона")
        }
        context.startActivity(intent)
        val label = chosen?.let {
            runCatching { it.loadLabel(manager).toString() }.getOrDefault(it.activityInfo.packageName)
        } ?: ""
        return ok(JSONObject()
            .put("started", true)
            .put("package", chosen?.activityInfo?.packageName ?: intent.`package` ?: JSONObject.NULL)
            .put("activity", chosen?.activityInfo?.name ?: JSONObject.NULL)
            .put("label", label))
    }

    private fun ok (payload: JSONObject) = JSONObject().put("status", "ok").put("payload", payload)
}
