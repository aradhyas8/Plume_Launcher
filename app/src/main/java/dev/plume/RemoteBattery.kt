package dev.plume

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * The battery of the Bluetooth remotes (and other Bluetooth controllers) that are connected, for the top bar. Android
 * has no one call for it, so each device is asked in turn, the cheapest way first:
 *  1. Bluetooth's own level, when the system tracks it for that device (headsets, some gamepads and remotes);
 *  2. the input device's battery state (Android 12 and newer: gamepads, some HID remotes);
 *  3. the remote itself: a Bluetooth LE remote that is a HID device and offers the standard Battery Service.
 * A remote that offers none of these (an infrared remote, a radio remote with a USB dongle, a remote with a battery
 * service of its own making) has no icon level: the icon shows a dash. The GATT connection is short, only while the
 * icon is on the bar, at most once in ten minutes, and nothing is read without the permission.
 */
internal object RemoteBattery {
    private val HID = UUID.fromString("00001812-0000-1000-8000-00805f9b34fb")
    private val BATTERY = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    private val LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    /** Below this the icon turns red. */
    const val LOW = 20

    /** How long a reading stays good before the home screen asks again. */
    const val FRESH_MS = 10 * 60_000L

    private const val TIMEOUT_MS = 12_000L
    private val main by lazy { Handler(Looper.getMainLooper()) } // lazy: the unit tests use the rules below with no Android

    @Volatile private var busy = false

    fun label(level: Int?) = if (level == null) "–" else "$level%"

    fun isLow(level: Int?) = level != null && level <= LOW

    fun allowed(c: Context) = c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Whether a reading made at [at] is still good at [now]. */
    fun fresh(at: Long, now: Long) = at > 0 && now - at in 0 until FRESH_MS

    /** One remote and its level. */
    class Reading(val name: String, val level: Int)

    /** The lowest level of [readings], which is the one worth a warning; null when there is none. */
    fun lowest(readings: List<Reading>) = readings.minOfOrNull { it.level }

    /** "Name 69%, Other 40%" for the message shown when the icon is selected. */
    fun describe(readings: List<Reading>) = readings.joinToString(", ") { "${it.name} ${it.level}%" }

    /**
     * Reads every connected remote or controller and calls [done] on the main thread with what could be read (empty when
     * nothing answered). Devices are tried one after the other.
     */
    @SuppressLint("MissingPermission")
    fun read(c: Context, done: (List<Reading>) -> Unit) {
        if (busy) return
        if (!allowed(c)) return done(emptyList())
        val adapter = (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val devices = try {
            adapter?.bondedDevices.orEmpty().filter { isRemote(it) && connected(it) }
        } catch (e: SecurityException) {
            emptyList()
        }
        if (devices.isEmpty()) return done(emptyList())
        busy = true
        val out = ArrayList<Reading>()
        fun finish() {
            busy = false
            main.post { done(out.toList()) }
        }
        fun next(i: Int) {
            if (i >= devices.size) return finish()
            val d = devices[i]
            val name = try { d.name ?: d.address } catch (e: SecurityException) { d.address }
            fun got(level: Int?) {
                if (level != null) out += Reading(name, level)
                next(i + 1)
            }
            quickLevel(c, d, name)?.let { return got(it) }
            if (d.type == BluetoothDevice.DEVICE_TYPE_LE || d.type == BluetoothDevice.DEVICE_TYPE_DUAL) readOne(c, d, ::got) else got(null)
        }
        next(0)
    }

    /** A remote, a keyboard, a gamepad: a Bluetooth input device (headsets and speakers have their own battery display). */
    @SuppressLint("MissingPermission")
    private fun isRemote(d: BluetoothDevice) =
        d.bluetoothClass?.majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.PERIPHERAL ||
            (d.bluetoothClass == null && d.type == BluetoothDevice.DEVICE_TYPE_LE)

    /** Whether the device is connected now: Bluetooth's own call, which apps reach by reflection. A device that cannot be asked counts as connected. */
    private fun connected(d: BluetoothDevice) = try {
        BluetoothDevice::class.java.getMethod("isConnected").invoke(d) as? Boolean ?: true
    } catch (e: Exception) {
        true
    }

    /** The two answers that need no connection: Bluetooth's level (-1 when it does not know), then the input device's battery. */
    @SuppressLint("MissingPermission")
    private fun quickLevel(c: Context, d: BluetoothDevice, name: String): Int? {
        try {
            val level = BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(d) as? Int
            if (level != null && level in 0..100) return level
        } catch (e: Exception) {
            // hidden on this Android: go on
        }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            for (id in android.view.InputDevice.getDeviceIds()) {
                val input = android.view.InputDevice.getDevice(id) ?: continue
                if (!input.name.startsWith(name)) continue
                val state = input.batteryState
                if (state.isPresent && !state.capacity.isNaN()) return (state.capacity * 100).toInt().coerceIn(0, 100)
            }
        }
        return null
    }

    /** One device: connect, check that it is a HID remote with a Battery Service, read it. [done] gets the level or null. */
    @SuppressLint("MissingPermission")
    private fun readOne(c: Context, d: BluetoothDevice, done: (Int?) -> Unit) {
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        var gatt: BluetoothGatt? = null
        fun end(level: Int?) {
            if (!finished.compareAndSet(false, true)) return
            try {
                gatt?.close()
            } catch (e: Exception) {
                // already closed
            }
            done(level)
        }
        val timeout = Runnable { end(null) }
        main.postDelayed(timeout, TIMEOUT_MS)
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices() else if (newState == BluetoothProfile.STATE_DISCONNECTED) end(null)
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val level = g.getService(BATTERY)?.getCharacteristic(LEVEL)
                if (g.getService(HID) == null || level == null || !g.readCharacteristic(level)) end(null)
            }

            @Deprecated("Android 12 and older call this one")
            override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                @Suppress("DEPRECATION")
                got(status, ch.value)
            }

            override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) = got(status, value)

            private fun got(status: Int, value: ByteArray?) {
                main.removeCallbacks(timeout)
                end(if (status == BluetoothGatt.GATT_SUCCESS) value?.firstOrNull()?.toInt()?.and(255)?.takeIf { it in 0..100 } else null)
            }
        }
        try {
            gatt = d.connectGatt(c, false, cb, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            main.removeCallbacks(timeout)
            end(null)
        }
    }
}

/**
 * The top-bar glyph: a small battery filled to [level] with the number beside it (a dash when it is not known), red
 * from [RemoteBattery.LOW] down. It takes the bar's ink as a colour filter like the other icons.
 */
internal class RemoteBatteryIcon(private val density: Float, private val level: Int?) : Drawable() {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() }
    private fun dp(v: Float) = v * density

    override fun getIntrinsicWidth() = dp(46f).toInt()
    override fun getIntrinsicHeight() = dp(20f).toInt()

    override fun draw(c: Canvas) {
        val b = bounds
        val w = dp(20f)
        val h = dp(11f)
        val left = b.left.toFloat()
        val top = b.exactCenterY() - h / 2
        ink.style = Paint.Style.STROKE
        ink.strokeWidth = dp(1.5f)
        val body = RectF(left + ink.strokeWidth / 2, top, left + w, top + h)
        c.drawRoundRect(body, dp(2.5f), dp(2.5f), ink)
        ink.style = Paint.Style.FILL
        c.drawRect(left + w, top + dp(3.5f), left + w + dp(2f), top + h - dp(3.5f), ink) // the nub
        if (level != null && level > 0) {
            val inner = RectF(body.left + dp(2.5f), body.top + dp(2.5f), body.right - dp(2.5f), body.bottom - dp(2.5f))
            inner.right = inner.left + inner.width() * level.coerceIn(0, 100) / 100f
            c.drawRoundRect(inner, dp(1f), dp(1f), if (RemoteBattery.isLow(level)) red else ink)
        }
        ink.typeface = Typeface.DEFAULT_BOLD
        ink.textSize = dp(11f)
        c.drawText(RemoteBattery.label(level), left + w + dp(5f), b.exactCenterY() + ink.textSize * 0.36f, ink)
    }

    override fun setAlpha(a: Int) {
        ink.alpha = a
    }

    override fun setColorFilter(f: ColorFilter?) {
        ink.colorFilter = f
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    // The "Add icon" menu draws its own copy, so tinting that one leaves the bar's alone.
    override fun getConstantState() = object : ConstantState() {
        override fun newDrawable(): Drawable = RemoteBatteryIcon(density, level)
        override fun getChangingConfigurations() = 0
    }
}
