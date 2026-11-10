package ru.uvw.dshnc.api

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import ru.uvw.dshnc.logic.Timeouts

/**
 * A camera snapshot on command.
 *
 * It works quietly, without a screen: the camera is opened directly through Camera2 and
 * the frame is written to a file. It needs the CAMERA permission and a service of type
 * `camera` — without that Android 14+ denies access from the background.
 *
 * The path and the size are returned: the file itself is fetched through the file API,
 * we do not start a separate channel for pictures.
 */
class CameraApi (private val context: Context) {
    fun snap (args: JSONObject, commandSeconds: Double = 0.0): JSONObject {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return error("нужно разрешение на камеру: выдай его в приложении")
        }
        val dir = File(args.optString("dir").ifBlank { "/storage/emulated/0/DSH" })
        if (!dir.exists() && !dir.mkdirs()) return error("не смог создать каталог ${dir.path}")
        val target = File(dir, args.optString("name").ifBlank { "camera-${System.currentTimeMillis()}" } + ".jpg")
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val wanted = args.optString("camera").ifBlank { "back" }.lowercase()
        val id = pickCamera(manager, wanted) ?: return error("не нашёл камеру «$wanted»")
        val size = pickSize(manager, id)
        val thread = HandlerThread("camera-snap").apply { start() }
        val handler = Handler(thread.looper)
        val done = CountDownLatch(1)
        var failure: String? = null

        try {
            val reader = ImageReader.newInstance(size.first, size.second, ImageFormat.JPEG, 1)
            reader.setOnImageAvailableListener({ source ->
                val image = source.acquireNextImage() ?: return@setOnImageAvailableListener
                try {
                    image.planes[0].buffer.let { buffer ->
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        target.writeBytes(bytes)
                    }
                } catch (error: Throwable) {
                    failure = error.message
                } finally {
                    image.close()
                    reader.close()
                    done.countDown()
                }
            }, handler)

            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened (device: CameraDevice) {
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(reader.surface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.JPEG_ORIENTATION, args.optInt("rotate", -1).takeIf { it >= 0 } ?: orientation(manager, id, wanted))
                    }
                    @Suppress("DEPRECATION")
                    device.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured (session: CameraCaptureSession) {
                            session.capture(request.build(), null, handler)
                            // we close the camera right after the frame: there is no point in holding it
                            handler.postDelayed({ runCatching { device.close() } }, 1500)
                        }

                        override fun onConfigureFailed (session: CameraCaptureSession) {
                            failure = "не смог настроить сессию"; done.countDown()
                        }
                    }, handler)
                }

                override fun onDisconnected (device: CameraDevice) {
                    failure = "камера занята"; done.countDown()
                }

                override fun onError (device: CameraDevice, code: Int) {
                    failure = errorText(code); done.countDown()
                }
            }, handler)
        } catch (error: Throwable) {
            failure = error.message ?: error.javaClass.simpleName
        }

        // how long to wait for the frame: the arguments win, then the command's own timeout
        val wait = (Timeouts.resolveMs(args, commandSeconds) ?: SNAP_TIMEOUT_MS)
            .coerceIn(MIN_SNAP_TIMEOUT_MS, MAX_SNAP_TIMEOUT_MS)
        val finished = done.await(wait.toLong(), TimeUnit.MILLISECONDS)
        thread.quitSafely()
        if (!finished) return error("камера не ответила за ${wait / 1000} секунд")
        failure?.let { return error(it) }
        if (!target.exists() || target.length() == 0L) return error("кадр не записался")
        return JSONObject().put("status", "ok").put("payload", JSONObject()
            .put("path", target.absolutePath)
            .put("bytes", target.length()))
    }

    /**
     * How to rotate the frame so that the top is the top.
     *
     * The sensor sits rotated inside the phone, so the matrix returns the picture on its
     * side. For the back camera the rotation is subtracted from the screen rotation, for
     * the front one it is added; this is the standard Camera2 formula, and without it
     * the snapshots come out "wrong top and bottom".
     */
    private fun orientation (manager: CameraManager, id: String, wanted: String): Int {
        val characteristics = manager.getCameraCharacteristics(id)
        val sensor = characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val facing = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
        val device = deviceRotationDegrees()
        val degrees = if (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) {
            (sensor + device) % 360
        } else {
            (sensor - device + 360) % 360
        }
        return degrees
    }

    /**
     * How the phone is rotated right now — by the accelerometer.
     *
     * The screen rotation is no good here: when the screen is off it does not update, and
     * the frame keeps the previous orientation however much the phone is turned.
     */
    private fun deviceRotationDegrees (): Int {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val sensor = manager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) return displayRotation()
        val value = arrayOfNulls<FloatArray>(1)
        val latch = java.util.concurrent.CountDownLatch(1)
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged (event: android.hardware.SensorEvent) {
                value[0] = event.values.copyOf()
                latch.countDown()
            }

            override fun onAccuracyChanged (sensor: android.hardware.Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
        latch.await(300, java.util.concurrent.TimeUnit.MILLISECONDS)
        manager.unregisterListener(listener)
        val v = value[0] ?: return displayRotation()
        val x = v[0]
        val y = v[1]
        // the phone is lying flat — the side cannot be told from the accelerometer
        if (kotlin.math.abs(x) < 3f && kotlin.math.abs(y) < 3f) return displayRotation()
        return when {
            kotlin.math.abs(x) > kotlin.math.abs(y) -> if (x > 0) 90 else 270
            else -> if (y > 0) 180 else 0
        }
    }

    /** The fallback: the screen rotation, if there is no accelerometer. */
    private fun displayRotation (): Int {
        val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.rotation
        return when (rotation) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    /** "front", "back" or a number: we look it up by the lens direction. */
    private fun pickCamera (manager: CameraManager, wanted: String): String? {
        val ids = manager.cameraIdList
        if (wanted in ids) return wanted
        val facing = when (wanted) {
            "front", "selfie", "передняя", "фронтальная" -> android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
            else -> android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        }
        return ids.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == facing
        } ?: ids.firstOrNull()
    }

    /** A frame size out of those the camera can hand out as JPEG. */
    private fun pickSize (manager: CameraManager, id: String): Pair<Int, Int> {
        val map = manager.getCameraCharacteristics(id)
            .get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return 1600 to 1200
        val sizes = map.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        if (sizes.isEmpty()) return 1600 to 1200
        // we take the one closest to 1600x1200: enough to make things out, and not heavy
        val best = sizes.minBy { kotlin.math.abs(it.width * it.height - 1600 * 1200) }
        return best.width to best.height
    }

    private fun errorText (code: Int): String = when (code) {
        android.hardware.camera2.CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "камера занята другим приложением"
        android.hardware.camera2.CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "слишком много камер открыто"
        android.hardware.camera2.CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "камера запрещена системой"
        android.hardware.camera2.CameraDevice.StateCallback.ERROR_CAMERA_DEVICE ->
            "камера недоступна: устройство заблокировано или экран погашен"
        android.hardware.camera2.CameraDevice.StateCallback.ERROR_CAMERA_SERVICE ->
            "служба камеры отказала"
        else -> "ошибка камеры $code"
    }

    private fun error (message: String) = JSONObject().put("status", "error").put("message", message)

    companion object {
        /** The pause of a snapshot when neither the arguments nor the server named one. */
        private const val SNAP_TIMEOUT_MS = 15_000
        private const val MIN_SNAP_TIMEOUT_MS = 1_000
        private const val MAX_SNAP_TIMEOUT_MS = 120_000
    }
}
