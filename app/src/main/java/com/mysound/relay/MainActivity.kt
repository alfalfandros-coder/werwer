package com.mysound.relay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.net.Inet4Address

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var stats: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            stats.text = "Приём: буфер ${Stats.depthMs} мс, пакетов ${Stats.received}, " +
                "потеряно ${Stats.lost}, сброшено ${Stats.dropped}, опустошений ${Stats.underruns}, опоздало ${Stats.late}\n" +
                "Отправка: пакетов ${Stats.sent}, ошибок ${Stats.sendErrors}"
            ui.postDelayed(this, 500)
        }
    }
    private lateinit var ipField: EditText
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (granted(Manifest.permission.RECORD_AUDIO)) askProjection()
        else toast("Нужно разрешение на запись звука (иначе захват не работает)")
    }

    private val projLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) {
            val host = ipField.text.toString().trim()
            val i = Intent(this, SenderService::class.java)
                .putExtra(Relay.EXTRA_HOST, host)
                .putExtra(Relay.EXTRA_CODE, r.resultCode)
                .putExtra(Relay.EXTRA_DATA, data)
            ContextCompat.startForegroundService(this, i)
            status.text = "Отправка звука на $host"
        } else {
            toast("Захват звука не разрешён")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        fun label(t: String) = TextView(this).apply { text = t; textSize = 18f; setPadding(0, pad, 0, pad / 2) }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { onClick() }
        }

        status = TextView(this).apply { text = "Выбери роль этого телефона"; gravity = Gravity.START }
        ipField = EditText(this).apply {
            hint = "IP приёмника, например 192.168.1.50"
            inputType = InputType.TYPE_CLASS_PHONE
        }

        root.addView(label("Телефон 2 — ПРИЁМНИК (подключён к колонке)"))
        root.addView(button("Включить приёмник") { startReceiver() })
        root.addView(button("Выключить приёмник") { stopSvc(ReceiverService::class.java) })

        // Синхронизация колонок: колонка 1 звучит раньше -> уменьшаем буфер приёмника
        val prefs = getSharedPreferences(Relay.PREFS, MODE_PRIVATE)
        val bufLabel = TextView(this).apply { setPadding(0, pad / 2, 0, 0) }
        fun showBuf(ms: Int) {
            bufLabel.text = "Буфер приёмника: $ms мс\n(меньше — колонка 2 звучит раньше; если появились провалы — увеличь)"
        }
        val startMs = Relay.bufferMs(this)
        showBuf(startMs)
        val bufBar = SeekBar(this).apply {
            max = (Relay.MAX_BUFFER_MS - Relay.MIN_BUFFER_MS) / Relay.STEP_MS
            progress = (startMs - Relay.MIN_BUFFER_MS) / Relay.STEP_MS
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val ms = Relay.MIN_BUFFER_MS + p * Relay.STEP_MS
                    showBuf(ms)
                    if (fromUser) prefs.edit().putInt(Relay.KEY_BUFFER_MS, ms).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        root.addView(bufLabel)
        root.addView(bufBar)

        root.addView(label("Телефон 1 — ОТПРАВИТЕЛЬ (играет музыку)"))
        root.addView(ipField)
        root.addView(button("Найти приёмник в сети") { discover() })
        root.addView(button("Начать отправку") { startSender() })
        root.addView(button("Остановить отправку") { stopSvc(SenderService::class.java) })

        root.addView(label("Статус"))
        root.addView(status)
        stats = TextView(this).apply { textSize = 12f; setPadding(0, pad / 2, 0, 0) }
        root.addView(stats)
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    private fun startReceiver() {
        ContextCompat.startForegroundService(this, Intent(this, ReceiverService::class.java))
        status.text = "Приёмник включён. IP этого телефона: ${localIp() ?: "не определён"}"
    }

    private fun startSender() {
        if (ipField.text.isNullOrBlank()) { toast("Укажи IP приёмника или нажми «Найти»"); return }
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(perms.toTypedArray())
    }

    private fun askProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun stopSvc(cls: Class<*>) {
        startService(Intent(this, cls).setAction(Relay.ACTION_STOP))
        status.text = "Остановлено"
    }

    private fun discover() {
        stopDiscovery()
        val m = getSystemService(NSD_SERVICE) as NsdManager
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) { runOnUiThread { status.text = "Ищу приёмник…" } }
            override fun onServiceFound(info: NsdServiceInfo) {
                m.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, e: Int) {}
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        val host = i.host?.hostAddress ?: return
                        runOnUiThread {
                            ipField.setText(host)
                            status.text = "Найден приёмник: $host"
                        }
                        stopDiscovery()
                    }
                })
            }
            override fun onServiceLost(i: NsdServiceInfo) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
        }
        nsd = m
        discovery = l
        m.discoverServices(Relay.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    private fun stopDiscovery() {
        try { discovery?.let { nsd?.stopServiceDiscovery(it) } } catch (_: Exception) {}
        discovery = null
    }

    private fun localIp(): String? {
        val cm = getSystemService(ConnectivityManager::class.java)
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address }?.hostAddress
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        stopDiscovery()
        super.onDestroy()
    }
}
