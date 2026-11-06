package com.mysound.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

object Relay {
    const val PORT = 50505                  // UDP
    const val SAMPLE_RATE = 48000           // Гц, стерео, 16 бит (родная частота большинства телефонов — без ресэмплинга)
    const val FRAME_MS = 5                  // длина одного пакета
    const val CHUNK = SAMPLE_RATE * 4 * FRAME_MS / 1000   // 960 байт PCM = 240 стерео-фреймов
    const val HEADER = 8                    // [id сессии: 4 байта][номер пакета: 4 байта]
    const val IDLE_MS = 1500L               // нет пакетов столько времени — считаем, что поток закончился
    const val SERVICE_TYPE = "_mysoundrelay._tcp."

    const val ACTION_STOP = "com.mysound.relay.STOP"
    const val EXTRA_HOST = "host"
    const val EXTRA_CODE = "code"
    const val EXTRA_DATA = "data"

    // Джиттер-буфер приёмника (мс): меньше = раньше звук, но выше риск пропаданий
    const val PREFS = "relay"
    const val KEY_BUFFER_MS = "buffer_ms"
    const val MIN_BUFFER_MS = 10
    const val MAX_BUFFER_MS = 500
    const val STEP_MS = 5
    const val DEFAULT_BUFFER_MS = 60

    fun bufferMs(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_BUFFER_MS, DEFAULT_BUFFER_MS)
            .coerceIn(MIN_BUFFER_MS, MAX_BUFFER_MS)

    fun packets(ms: Int) = (ms + FRAME_MS - 1) / FRAME_MS

    private const val CHANNEL = "relay"

    fun notification(ctx: Context, text: String): Notification {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "MySound Relay", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("MySound Relay")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }
}

/** Счётчики для экрана диагностики (каждое поле пишет один поток). */
object Stats {
    @Volatile var sent = 0L
    @Volatile var sendErrors = 0L
    @Volatile var received = 0L
    @Volatile var lost = 0L        // пакет не пришёл вовремя, заменён тишиной
    @Volatile var late = 0L        // пакет пришёл после своего времени
    @Volatile var dropped = 0L     // сброшено, чтобы не копилась задержка
    @Volatile var underruns = 0L   // буфер опустел, пауза для накопления
    @Volatile var depthMs = 0
}

/** Не даёт Wi-Fi уйти в энергосбережение (главный источник скачков задержки) и CPU — уснуть. */
@Suppress("DEPRECATION")
class Locks(ctx: Context) {
    private val wifiLow: WifiManager.WifiLock
    private val wifiPerf: WifiManager.WifiLock
    private val cpu: PowerManager.WakeLock

    init {
        val app = ctx.applicationContext
        val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLow = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "MySoundRelay:low")
            .apply { setReferenceCounted(false) }
        wifiPerf = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MySoundRelay:perf")
            .apply { setReferenceCounted(false) }
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        cpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MySoundRelay:cpu")
            .apply { setReferenceCounted(false) }
    }

    fun acquire() {
        try { wifiLow.acquire() } catch (_: Exception) {}
        try { wifiPerf.acquire() } catch (_: Exception) {}
        try { cpu.acquire() } catch (_: Exception) {}
    }

    fun release() {
        try { if (wifiLow.isHeld) wifiLow.release() } catch (_: Exception) {}
        try { if (wifiPerf.isHeld) wifiPerf.release() } catch (_: Exception) {}
        try { if (cpu.isHeld) cpu.release() } catch (_: Exception) {}
    }
}
