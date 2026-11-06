package com.mysound.relay

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import androidx.core.content.IntentCompat
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * Телефон-отправитель: захватывает системный звук и шлёт его по UDP маленькими пакетами (5 мс).
 * Никакой очереди и повторных отправок: опоздавший звук не нужен, поэтому задержка не копится.
 */
class SenderService : Service() {

    @Volatile private var running = false
    private var thread: Thread? = null
    private var projection: MediaProjection? = null
    private var locks: Locks? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == Relay.ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        val host = intent.getStringExtra(Relay.EXTRA_HOST)
        val code = intent.getIntExtra(Relay.EXTRA_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, Relay.EXTRA_DATA, Intent::class.java)
        if (host == null || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Сначала foreground, только потом getMediaProjection (требование Android 14)
        startForeground(
            1,
            Relay.notification(this, "Отправка звука → $host"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(Looper.getMainLooper()))
        projection = p

        locks = Locks(this).also { it.acquire() }
        Stats.sent = 0; Stats.sendErrors = 0
        running = true
        thread = Thread { streamLoop(p, host) }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun streamLoop(p: MediaProjection, host: String) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var rec: AudioRecord? = null
        var sock: DatagramSocket? = null
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(p)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(Relay.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                Relay.SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            rec = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf, Relay.CHUNK * 8))
                .setAudioPlaybackCaptureConfig(config)
                .build()

            val s = DatagramSocket()
            sock = s
            try { s.trafficClass = 0xB8 } catch (_: Exception) {}   // приоритет «голос» для Wi-Fi (WMM)
            val addr = InetAddress.getByName(host)

            val buf = ByteArray(Relay.HEADER + Relay.CHUNK)
            val bb = ByteBuffer.wrap(buf)
            val packet = DatagramPacket(buf, buf.size, addr, Relay.PORT)
            val session = Random.nextInt()
            var seq = 0

            rec.startRecording()
            while (running) {
                var got = 0
                while (got < Relay.CHUNK && running) {
                    val n = rec.read(buf, Relay.HEADER + got, Relay.CHUNK - got)
                    if (n < 0) throw IOException("record error $n")
                    got += n
                }
                if (!running) break
                bb.putInt(0, session)
                bb.putInt(4, seq++)
                try {
                    s.send(packet)
                    Stats.sent++
                } catch (e: IOException) {
                    Stats.sendErrors++      // сеть недоступна — просто пропускаем, звук живой
                }
            }
        } catch (e: Exception) {
            // не удалось начать захват / получить адрес / ошибка записи
        } finally {
            try { rec?.stop() } catch (_: Exception) {}
            try { rec?.release() } catch (_: Exception) {}
            try { sock?.close() } catch (_: Exception) {}
            if (running) stopSelf()
        }
    }

    override fun onDestroy() {
        running = false
        thread?.interrupt()
        projection?.stop()
        projection = null
        locks?.release()
        locks = null
        super.onDestroy()
    }
}
