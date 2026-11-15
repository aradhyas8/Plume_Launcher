package dev.plume

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice

/**
 * Bluetooth devices coming and going (headphones, remotes, controllers) and game controllers running low, as short
 * island items. Off unless the user turns on "isl_bluetooth", because most people pair a device once and never need
 * to be told again. The home screen calls [start] while it is shown and [stop] when it is not.
 */
internal object IslandBluetooth : IslandSource {
    private const val BATTERY_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    private const val EXTRA_BATTERY = "android.bluetooth.device.extra.BATTERY_LEVEL"

    private val shown = LinkedHashMap<String, IslandItem>()
    private val battery = HashMap<String, Int>()
    private var app: Context? = null
    private var input: InputManager? = null

    @Synchronized override fun items(now: Long): List<IslandItem> {
        shown.values.removeAll { now >= it.until }
        return shown.values.toList()
    }

    @Synchronized fun start(ctx: Context) {
        if (app != null) return
        val c = ctx.applicationContext
        if (!c.getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean("isl_bluetooth", false)) return
        // Without BLUETOOTH_CONNECT a device's name throws; the settings screen asks for it, so until then stay quiet.
        if (Build.VERSION.SDK_INT >= 31 && c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BATTERY_CHANGED)
        }
        // These are system broadcasts, so on Android 13 and later the receiver has to say it takes them from outside.
        if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED) else c.registerReceiver(receiver, filter)
        app = c
        // A controller's battery is only readable on Android 12 and later.
        if (Build.VERSION.SDK_INT >= 31) {
            input = c.getSystemService(InputManager::class.java)?.also {
                it.registerInputDeviceListener(pads, Handler(Looper.getMainLooper()))
                checkPads()
            }
        }
    }

    @Synchronized fun stop(ctx: Context) {
        val c = app ?: return
        try { c.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
        input?.unregisterInputDeviceListener(pads)
        input = null
        app = null
        battery.clear()
        if (shown.isNotEmpty()) { shown.clear(); Island.changed() }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> show(context, device, connected = true)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> show(context, device, connected = false)
                BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(EXTRA_BATTERY, -1)
                    synchronized(this@IslandBluetooth) {
                        if (level >= 0) battery[device.address] = level else battery.remove(device.address)
                        // A battery level usually lands a moment after the connect, so the connected item picks it up.
                        val key = "bt:${device.address}"
                        val item = shown[key]
                        if (item != null && item.peekUntil > 0 && level >= 0) {
                            shown[key] = item.copy(sub = context.getString(R.string.isl_bt_battery, level))
                            Island.changed()
                        }
                    }
                    // Bluetooth controllers report their charge through the same broadcast, so look at them again.
                    if (Build.VERSION.SDK_INT >= 31) checkPads()
                }
            }
        }
    }

    private fun show(ctx: Context, device: BluetoothDevice, connected: Boolean) {
        val name = try { device.name } catch (_: SecurityException) { null } ?: ctx.getString(R.string.isl_bt_device)
        val major = try { device.bluetoothClass?.majorDeviceClass } catch (_: SecurityException) { null }
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (!connected) battery.remove(device.address)
            val level = battery[device.address]
            shown["bt:${device.address}"] = IslandItem(
                key = "bt:${device.address}",
                kind = IslandKind.BLUETOOTH,
                title = ctx.getString(if (connected) R.string.isl_bt_connected else R.string.isl_bt_disconnected, name),
                sub = level?.let { ctx.getString(R.string.isl_bt_battery, it) },
                icon = islandBluetoothIcon(major),
                since = now,
                until = now + if (connected) 6_000L else 4_000L,
                peekUntil = if (connected) now + 4_000L else 0L,
            )
        }
        Island.changed()
        if (!connected && Build.VERSION.SDK_INT >= 31) checkPads()
    }

    private val pads = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = checkPads()
        override fun onInputDeviceRemoved(deviceId: Int) = checkPads()
        override fun onInputDeviceChanged(deviceId: Int) = checkPads()
    }

    /** One lasting item per controller below 15%, gone once it charges or disconnects; it peeks once, when it first shows. */
    @Synchronized private fun checkPads() {
        if (Build.VERSION.SDK_INT < 31) return
        val c = app ?: return
        val now = System.currentTimeMillis()
        val low = HashMap<String, Int>()
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            if (d.isVirtual || d.sources and InputDevice.SOURCE_GAMEPAD != InputDevice.SOURCE_GAMEPAD) continue
            val state = d.batteryState
            if (state.isPresent && islandPadLow(state.capacity)) low["pad:${d.descriptor}"] = Math.round(state.capacity * 100)
        }
        var changed = shown.keys.removeAll { it.startsWith("pad:") && it !in low }
        for ((key, percent) in low) {
            val old = shown[key]
            val sub = c.getString(R.string.isl_bt_percent, percent)
            if (old != null && old.sub == sub) continue
            shown[key] = old?.copy(sub = sub) ?: IslandItem(
                key = key,
                kind = IslandKind.BLUETOOTH,
                title = c.getString(R.string.isl_bt_pad_low),
                sub = sub,
                icon = R.drawable.ic_isl_gamepad,
                since = now,
                peekUntil = now + 5_000L,
            )
            changed = true
        }
        if (changed) Island.changed()
    }
}

/** A controller counts as low below 15%. An unknown charge (NaN) is never low. */
internal fun islandPadLow(capacity: Float) = capacity < 0.15f

/** The device's picture from its Bluetooth major class: headphones and speakers, controllers, else the Bluetooth mark. */
internal fun islandBluetoothIcon(major: Int?) = when (major) {
    BluetoothClass.Device.Major.AUDIO_VIDEO -> R.drawable.ic_isl_headphones
    BluetoothClass.Device.Major.PERIPHERAL -> R.drawable.ic_isl_gamepad
    else -> R.drawable.ic_isl_bluetooth
}
