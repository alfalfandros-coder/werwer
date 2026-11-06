package com.mysound.relay

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer

/**
 * Телефон-приёмник: ловит UDP-пакеты в джиттер-буфер, а отдельный поток ровно проигрывает их
 * в текущий аудиовыход (динамик, наушники или Bluetooth-колонка).
 */
class ReceiverService : Service() {

    @Volatile private var running = false
    @Volatile private var lastPacketMs = 0L
    private var socket: DatagramSocket? = null
    private var netThread: Thread? = null
    private var playThread: Thread? = null
    private var nsd: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var locks: Locks? = null
    private val jb = JitterBuffer()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Relay.ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(
            2,
            Relay.notification(this, "Приёмник включён, жду звук"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        if (running) return START_NOT_STICKY
        running = true
        locks = Locks(this).also { it.acquire() }
        Stats.received = 0; Stats.lost = 0; Stats.late = 0
        Stats.dropped = 0; Stats.underruns = 0; Stats.depthMs = 0
        netThread = Thread { netLoop() }.also { it.start() }
        playThread = Thread { playLoop() }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun recent() = SystemClock.elapsedRealtime() - lastPacketMs < Relay.IDLE_MS

    // ---------- приём ----------

    private fun netLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val s = try { DatagramSocket(Relay.PORT) } catch (e: Exception) { stopSelf(); return }
        try { s.receiveBufferSize = 256 * 1024 } catch (_: Exception) {}
        socket = s
        register(Relay.PORT)

        val buf = ByteArray(2048)
        val pkt = DatagramPacket(buf, buf.size)
        var session = 0
        var haveSession = false
        while (running) {
            try {
                pkt.length = buf.size
                s.receive(pkt)
            } catch (e: IOException) {
                if (!running) break else continue
            }
            if (pkt.length != Relay.HEADER + Relay.CHUNK) continue
            val bb = ByteBuffer.wrap(buf)
            val sess = bb.getInt(0)
            val seq = bb.getInt(4).toLong() and 0xFFFFFFFFL
            if (!haveSession || sess != session) {      // отправитель перезапустился
                jb.reset()
                session = sess
                haveSession = true
            }
            jb.put(seq, buf.copyOfRange(Relay.HEADER, pkt.length))
            lastPacketMs = SystemClock.elapsedRealtime()
            Stats.received++
        }
    }

    // ---------- воспроизведение ----------

    private fun playLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        while (running) {
            val target = Relay.packets(Relay.bufferMs(this))
            if (!recent() || jb.depth() < target) {     // ждём начала потока и первоначального накопления
                try { Thread.sleep(10) } catch (_: InterruptedException) {}
                continue
            }
            playStream()
            if (!recent()) { jb.reset(); Stats.depthMs = 0 }
        }
    }

    private fun playStream() {
        val minBuf = AudioTrack.getMinBufferSize(
            Relay.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(Relay.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)       // небольшой постоянный запас; «плавает» только джиттер-буфер
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val silence = ByteArray(Relay.CHUNK)
        var buffering = false
        track.play()
        try {
            while (running && recent()) {
                val target = Relay.packets(Relay.bufferMs(this))
                Stats.depthMs = jb.depth() * Relay.FRAME_MS

                if (buffering) {
                    if (jb.depth() >= target) buffering = false
                    else {                               // копим; запись тишины задаёт темп
                        if (track.write(silence, 0, silence.size) < 0) break
                        continue
                    }
                }
                // Накопилось больше нужного (задержка растёт) — сбрасываем лишнее, возвращаемся к цели
                if (jb.depth() > target + maxOf(4, target / 2)) jb.trimTo(target)

                val pk = jb.poll()
                val w = when {
                    pk == null -> { Stats.underruns++; buffering = true; track.write(silence, 0, silence.size) }
                    pk === JitterBuffer.LOST -> { Stats.lost++; track.write(silence, 0, silence.size) }
                    else -> track.write(pk, 0, pk.size)
                }
                if (w < 0) break                         // например, сменился выход — пересоздадим трек
            }
        } finally {
            try { track.pause(); track.flush() } catch (_: Exception) {}
            track.release()
        }
    }

    // ---------- обнаружение в сети ----------

    private fun register(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "MySoundRelay"
            serviceType = Relay.SERVICE_TYPE
            setPort(port)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        val m = getSystemService(NSD_SERVICE) as NsdManager
        m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        nsd = m
        regListener = l
    }

    override fun onDestroy() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        try { regListener?.let { nsd?.unregisterService(it) } } catch (_: Exception) {}
        netThread?.interrupt()
        playThread?.interrupt()
        locks?.release()
        locks = null
        super.onDestroy()
    }
}
