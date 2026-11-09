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

/**
 * Снимок с камеры по команде.
 *
 * Работает тихо, без экрана: камера открывается напрямую через Camera2, кадр
 * пишется в файл. Требует разрешения CAMERA и службы с типом `camera` — без
 * этого Android 14+ отказывает в доступе из фона.
 *
 * Возвращается путь и размер: сам файл забирают через файловый API, отдельного
 * канала для картинок не заводим.
 */
class CameraApi (private val context: Context) {
    fun snap (args: JSONObject): JSONObject {
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
                            // закрываем камеру сразу после кадра: держать её незачем
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

        val finished = done.await(15, TimeUnit.SECONDS)
        thread.quitSafely()
        if (!finished) return error("камера не ответила за 15 секунд")
        failure?.let { return error(it) }
        if (!target.exists() || target.length() == 0L) return error("кадр не записался")
        return JSONObject().put("status", "ok").put("payload", JSONObject()
            .put("path", target.absolutePath)
            .put("bytes", target.length()))
    }

    /**
     * Как повернуть кадр, чтобы верх был верхом.
     *
     * Сенсор стоит в телефоне повёрнутым, поэтому матрица отдаёт картинку
     * боком. Для задней камеры поворот вычитается из поворота экрана, для
     * фронтальной — складывается; это стандартная формула Camera2, и без неё
     * снимки приходят «неверный верх-низ».
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
     * Как сейчас повёрнут телефон — по акселерометру.
     *
     * Поворот экрана тут не годится: когда экран погашен, он не обновляется, и
     * кадр остаётся в прежней ориентации, сколько телефон ни крути.
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
        // телефон лежит плашмя — по акселерометру сторону не определить
        if (kotlin.math.abs(x) < 3f && kotlin.math.abs(y) < 3f) return displayRotation()
        return when {
            kotlin.math.abs(x) > kotlin.math.abs(y) -> if (x > 0) 90 else 270
            else -> if (y > 0) 180 else 0
        }
    }

    /** Запасной путь: поворот экрана, если акселерометра нет. */
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

    /** «front», «back» или номер: ищем по направлению объектива. */
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

    /** Размер кадра из тех, что камера умеет отдавать в JPEG. */
    private fun pickSize (manager: CameraManager, id: String): Pair<Int, Int> {
        val map = manager.getCameraCharacteristics(id)
            .get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return 1600 to 1200
        val sizes = map.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        if (sizes.isEmpty()) return 1600 to 1200
        // берём ближайший к 1600x1200: хватает для разглядеть, и не тяжело
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
}
