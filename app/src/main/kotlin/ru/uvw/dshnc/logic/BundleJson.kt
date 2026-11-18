package ru.uvw.dshnc.logic

import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/**
 * A Bundle as JSON, so that what a plugin handed back can be read and repeated.
 *
 * The Locale/Tasker plugin protocol carries its settings as one Bundle extra. To repeat that call
 * later, the node has to know what was inside — and the only way to learn it is to capture one
 * configured action and read it back. Values that JSON cannot express are written as their
 * string form and marked, rather than dropped silently: a key that disappears is worse than a key
 * that says "this was a Parcelable".
 */
object BundleJson {
    /**
     * @param bundle - what the plugin returned; null gives an empty object.
     * @returns a JSON object with the same keys.
     */
    fun of (bundle: Bundle?): JSONObject {
        val out = JSONObject()
        for (key in bundle?.keySet().orEmpty()) {
            @Suppress("DEPRECATION")
            when (val value = bundle?.get(key)) {
                null -> out.put(key, JSONObject.NULL)
                is String -> out.put(key, value)
                is Boolean -> out.put(key, value)
                is Int -> out.put(key, value)
                is Long -> out.put(key, value)
                is Double -> out.put(key, value)
                is Float -> out.put(key, value.toDouble())
                is Bundle -> out.put(key, of(value))
                is Array<*> -> out.put(key, JSONArray(value.map { it?.toString() ?: "" }))
                is Collection<*> -> out.put(key, JSONArray(value.map { it?.toString() ?: "" }))
                else -> {
                    out.put(key, JSONObject().put("unsupported", value.javaClass.simpleName))
                }
            }
        }
        return out
    }

    /**
     * The same shape back into a Bundle, for repeating a captured call.
     *
     * @param json - a value produced by {@link of}.
     * @returns a Bundle with the same keys; unknown kinds are skipped.
     */
    fun toBundle (json: JSONObject): Bundle {
        val bundle = Bundle()
        for (key in json.keys()) {
            when (val value = json.opt(key)) {
                is JSONObject -> if (!value.has("unsupported")) bundle.putBundle(key, toBundle(value))
                is JSONArray -> bundle.putStringArray(key, Array(value.length()) { value.optString(it) })
                is Boolean -> bundle.putBoolean(key, value)
                is Int -> bundle.putInt(key, value)
                is Long -> bundle.putLong(key, value)
                is Double -> bundle.putDouble(key, value)
                is String -> bundle.putString(key, value)
            }
        }
        return bundle
    }
}
