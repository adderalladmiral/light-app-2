package com.ks03old.app

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(), BleController.Listener {

    private lateinit var ble: BleController

    private lateinit var btnScan: Button
    private lateinit var txtStatus: TextView
    private lateinit var listDevices: ListView
    private lateinit var txtLog: TextView

    private lateinit var seekRed: SeekBar
    private lateinit var seekGreen: SeekBar
    private lateinit var seekBlue: SeekBar
    private lateinit var lblRed: TextView
    private lateinit var lblGreen: TextView
    private lateinit var lblBlue: TextView

    private lateinit var seekBrightness: SeekBar
    private lateinit var lblBrightness: TextView
    private lateinit var seekSpeed: SeekBar
    private lateinit var lblSpeed: TextView

    private lateinit var seekMusicSpeed: SeekBar
    private lateinit var lblMusicSpeed: TextView

    private val foundDevices = mutableListOf<BluetoothDevice>()
    private lateinit var deviceAdapter: ArrayAdapter<String>

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            ble.startScan()
        } else {
            log("Bluetooth permissions denied — cannot scan.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ble = (application as Ks03App).ble
        ble.setListener(this)

        btnScan = findViewById(R.id.btnScan)
        txtStatus = findViewById(R.id.txtStatus)
        listDevices = findViewById(R.id.listDevices)
        txtLog = findViewById(R.id.txtLog)

        seekRed = findViewById(R.id.seekRed)
        seekGreen = findViewById(R.id.seekGreen)
        seekBlue = findViewById(R.id.seekBlue)
        lblRed = findViewById(R.id.lblRed)
        lblGreen = findViewById(R.id.lblGreen)
        lblBlue = findViewById(R.id.lblBlue)

        seekBrightness = findViewById(R.id.seekBrightness)
        lblBrightness = findViewById(R.id.lblBrightness)
        seekSpeed = findViewById(R.id.seekSpeed)
        lblSpeed = findViewById(R.id.lblSpeed)

        seekMusicSpeed = findViewById(R.id.seekMusicSpeed)
        lblMusicSpeed = findViewById(R.id.lblMusicSpeed)

        deviceAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        listDevices.adapter = deviceAdapter

        btnScan.setOnClickListener { requestPermissionsAndScan() }

        findViewById<Button>(R.id.btnStressTest).setOnClickListener {
            startActivity(android.content.Intent(this, StressTestActivity::class.java))
        }

        listDevices.setOnItemClickListener { _, _, position, _ ->
            val device = foundDevices[position]
            ble.connect(device)
        }

        findViewById<Button>(R.id.btnOn).setOnClickListener {
            ble.send(Ks03OldProtocol.switch(true))
        }
        findViewById<Button>(R.id.btnOff).setOnClickListener {
            ble.send(Ks03OldProtocol.switch(false))
        }

        seekRed.setOnSeekBarChangeListener(labelUpdater(lblRed, "Red"))
        seekGreen.setOnSeekBarChangeListener(labelUpdater(lblGreen, "Green"))
        seekBlue.setOnSeekBarChangeListener(labelUpdater(lblBlue, "Blue"))

        findViewById<Button>(R.id.btnApplyColor).setOnClickListener {
            ble.send(Ks03OldProtocol.rgb(seekRed.progress, seekGreen.progress, seekBlue.progress))
        }

        seekBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                lblBrightness.text = "Brightness: $value"
                if (fromUser) ble.send(Ks03OldProtocol.brightness(value))
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                lblSpeed.text = "Speed: $value"
                if (fromUser) ble.send(Ks03OldProtocol.speed(value))
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        findViewById<Button>(R.id.btnJump7).setOnClickListener { ble.send(Ks03OldProtocol.scene(Ks03OldProtocol.Scene.JUMP_7)) }
        findViewById<Button>(R.id.btnJump3).setOnClickListener { ble.send(Ks03OldProtocol.scene(Ks03OldProtocol.Scene.JUMP_3)) }
        findViewById<Button>(R.id.btnFade7).setOnClickListener { ble.send(Ks03OldProtocol.scene(Ks03OldProtocol.Scene.FADE_7)) }
        findViewById<Button>(R.id.btnFade3).setOnClickListener { ble.send(Ks03OldProtocol.scene(Ks03OldProtocol.Scene.FADE_3)) }
        findViewById<Button>(R.id.btnFlash).setOnClickListener { ble.send(Ks03OldProtocol.scene(Ks03OldProtocol.Scene.FLASH)) }
        findViewById<Button>(R.id.btnAuto).setOnClickListener { ble.send(Ks03OldProtocol.scene(Ks03OldProtocol.Scene.AUTO)) }

        // --- Experimental: music modes (untested upstream, see layout warning) ---
        seekMusicSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                lblMusicSpeed.text = "Music Speed: $value"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        findViewById<Button>(R.id.btnMusicFade7).setOnClickListener {
            ble.send(Ks03OldProtocol.musicModel(Ks03OldProtocol.MusicModel.FADE_7_FAST_ON_NOISE, seekMusicSpeed.progress))
        }
        findViewById<Button>(R.id.btnMusicTwoFade).setOnClickListener {
            ble.send(Ks03OldProtocol.musicModel(Ks03OldProtocol.MusicModel.TWO_FADE_FAST_ON_NOISE, seekMusicSpeed.progress))
        }
        findViewById<Button>(R.id.btnMusicJumpPause).setOnClickListener {
            ble.send(Ks03OldProtocol.musicModel(Ks03OldProtocol.MusicModel.JUMP_ON_NOISE_PAUSE_QUIET, seekMusicSpeed.progress))
        }
        findViewById<Button>(R.id.btnMusicJumpOff).setOnClickListener {
            ble.send(Ks03OldProtocol.musicModel(Ks03OldProtocol.MusicModel.JUMP_ON_NOISE_OFF_QUIET, seekMusicSpeed.progress))
        }
    }

    private fun labelUpdater(label: TextView, name: String) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
            label.text = "$name: $value"
        }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    private fun requestPermissionsAndScan() {
        if (!ble.isBluetoothEnabled()) {
            log("Bluetooth is off — turn it on first.")
            return
        }
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = needed.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            foundDevices.clear()
            deviceAdapter.clear()
            ble.startScan()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun log(message: String) {
        runOnUiThread {
            txtLog.append("\n$message")
        }
    }

    // BleController.Listener

    override fun onDeviceFound(device: BluetoothDevice, rssi: Int) {
        runOnUiThread {
            foundDevices.add(device)
            val label = "${device.name ?: "unknown"}  (${device.address})  rssi=$rssi"
            deviceAdapter.add(label)
        }
    }

    override fun onConnected(device: BluetoothDevice) {
        runOnUiThread {
            txtStatus.text = "Connected: ${device.name ?: device.address}"
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            txtStatus.text = "Not connected"
        }
    }

    override fun onReady() {
        runOnUiThread {
            txtStatus.text = "${txtStatus.text} — ready"
        }
    }

    override fun onLog(message: String) {
        log(message)
    }

    override fun onDestroy() {
        super.onDestroy()
        // BleController is app-scoped now (shared with StressTestActivity), so we
        // don't disconnect here — only when the whole process dies.
    }

    override fun onResume() {
        super.onResume()
        // Re-attach as listener in case StressTestActivity was in front and
        // grabbed listenership.
        ble.setListener(this)
        if (ble.isConnected()) {
            txtStatus.text = "Connected — ready"
        }
    }
}