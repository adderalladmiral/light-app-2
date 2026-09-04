package com.ks03old.app

import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Separate, self-contained screen for finding the real send-rate ceiling of
 * the connected KS03 Old device. Not mixed into the main controls screen.
 *
 * Method: pushes alternating rgb() frames at a target rate, and every
 * rampIntervalMillis bumps the target rate up by rampStep writes/sec.
 * Tracks how many sends the OS BLE stack accepted vs. rejected, and stops
 * automatically (reporting the rate at which it happened) if:
 *   - the OS starts rejecting writes (internal buffer full), or
 *   - the BLE connection itself drops.
 *
 * This only measures what Android's BLE stack will accept from this app —
 * it cannot confirm the light actually rendered every frame, since
 * WRITE_TYPE_NO_RESPONSE has no acknowledgement from the peripheral.
 */
class StressTestActivity : AppCompatActivity(), BleController.Listener {

    private lateinit var ble: BleController
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var txtConnState: TextView
    private lateinit var lblStartRate: TextView
    private lateinit var seekStartRate: SeekBar
    private lateinit var lblRampStep: TextView
    private lateinit var seekRampStep: SeekBar
    private lateinit var btnStartStop: Button
    private lateinit var txtCurrentRate: TextView
    private lateinit var txtStats: TextView
    private lateinit var txtResult: TextView
    private lateinit var txtLog2: TextView

    private var running = false
    private var currentRateHz = 5
    private var rampStep = 5
    private val rampIntervalMillis = 2000L

    private var sentCount = 0L
    private var acceptedCount = 0L
    private var rejectedCount = 0L
    private var toggle = false // alternate between two colors so changes are visible

    private var sendRunnable: Runnable? = null
    private var rampRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stress_test)

        ble = (application as Ks03App).ble
        ble.setListener(this)

        txtConnState = findViewById(R.id.txtConnState)
        lblStartRate = findViewById(R.id.lblStartRate)
        seekStartRate = findViewById(R.id.seekStartRate)
        lblRampStep = findViewById(R.id.lblRampStep)
        seekRampStep = findViewById(R.id.seekRampStep)
        btnStartStop = findViewById(R.id.btnStartStop)
        txtCurrentRate = findViewById(R.id.txtCurrentRate)
        txtStats = findViewById(R.id.txtStats)
        txtResult = findViewById(R.id.txtResult)
        txtLog2 = findViewById(R.id.txtLog2)

        updateConnState()

        // Start rate: 5-100 writes/sec (progress 0-95 maps to 5-100)
        seekStartRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                lblStartRate.text = "Start rate: ${5 + value} writes/sec"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Ramp step: 1-46 writes/sec added per interval
        seekRampStep.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                lblRampStep.text = "Ramp step: +${1 + value} writes/sec every 2s"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        btnStartStop.setOnClickListener {
            if (running) stopTest("Stopped by user.") else startTest()
        }
    }

    private fun updateConnState() {
        val connected = ble.isConnected()
        txtConnState.text = if (connected) "Connected — ready to test" else "Not connected — go back and connect first"
        btnStartStop.isEnabled = connected || running
    }

    private fun startTest() {
        if (!ble.isConnected()) {
            txtConnState.text = "Not connected — go back and connect first"
            return
        }
        running = true
        sentCount = 0
        acceptedCount = 0
        rejectedCount = 0
        currentRateHz = 5 + seekStartRate.progress
        rampStep = 1 + seekRampStep.progress
        txtResult.text = ""
        btnStartStop.text = "Stop Stress Test"
        seekStartRate.isEnabled = false
        seekRampStep.isEnabled = false

        scheduleSendLoop()
        scheduleRamp()
    }

    private fun scheduleSendLoop() {
        val intervalMillis = (1000.0 / currentRateHz).toLong().coerceAtLeast(1)
        val r = object : Runnable {
            override fun run() {
                if (!running) return
                toggle = !toggle
                val frame = if (toggle)
                    Ks03OldProtocol.rgb(100, 0, 0)
                else
                    Ks03OldProtocol.rgb(0, 0, 100)

                sentCount++
                val accepted = ble.send(frame)
                if (accepted) acceptedCount++ else rejectedCount++

                updateStats()

                if (!ble.isConnected()) {
                    stopTest("Connection dropped at ~$currentRateHz writes/sec (sent=$sentCount).")
                    return
                }

                // If the OS starts consistently rejecting, call that the ceiling.
                if (rejectedCount > 20 && rejectedCount > acceptedCount / 4) {
                    stopTest("OS BLE stack started rejecting writes at ~$currentRateHz writes/sec (accepted=$acceptedCount, rejected=$rejectedCount).")
                    return
                }

                handler.postDelayed(this, intervalMillis)
            }
        }
        sendRunnable = r
        handler.post(r)
    }

    private fun scheduleRamp() {
        val r = object : Runnable {
            override fun run() {
                if (!running) return
                currentRateHz += rampStep
                txtCurrentRate.text = "Current target rate: $currentRateHz writes/sec"
                // Restart the send loop at the new rate.
                sendRunnable?.let { handler.removeCallbacks(it) }
                scheduleSendLoop()
                handler.postDelayed(this, rampIntervalMillis)
            }
        }
        rampRunnable = r
        txtCurrentRate.text = "Current target rate: $currentRateHz writes/sec"
        handler.postDelayed(r, rampIntervalMillis)
    }

    private fun updateStats() {
        txtStats.text = "Sent: $sentCount   Accepted: $acceptedCount   Rejected: $rejectedCount"
    }

    private fun stopTest(resultMessage: String) {
        running = false
        sendRunnable?.let { handler.removeCallbacks(it) }
        rampRunnable?.let { handler.removeCallbacks(it) }
        sendRunnable = null
        rampRunnable = null
        btnStartStop.text = "Start Stress Test"
        seekStartRate.isEnabled = true
        seekRampStep.isEnabled = true
        txtResult.text = resultMessage
        updateConnState()
    }

    // BleController.Listener — StressTestActivity mainly cares about disconnect
    // detection mid-test; other callbacks are no-ops here since scanning/
    // connecting happens on the main screen.

    override fun onDeviceFound(device: BluetoothDevice, rssi: Int) {}

    override fun onConnected(device: BluetoothDevice) {
        runOnUiThread { updateConnState() }
    }

    override fun onDisconnected() {
        runOnUiThread {
            updateConnState()
            if (running) {
                stopTest("Connection dropped at ~$currentRateHz writes/sec (sent=$sentCount).")
            }
        }
    }

    override fun onReady() {
        runOnUiThread { updateConnState() }
    }

    override fun onLog(message: String) {
        runOnUiThread { txtLog2.text = message }
    }

    override fun onResume() {
        super.onResume()
        // Re-attach as listener in case MainActivity's connect flow finished
        // while this screen wasn't in front.
        ble.setListener(this)
        updateConnState()
    }

    override fun onPause() {
        super.onPause()
        if (running) stopTest("Stopped — left the screen mid-test.")
    }
}
