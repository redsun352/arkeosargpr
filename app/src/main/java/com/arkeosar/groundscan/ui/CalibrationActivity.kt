package com.arkeosar.groundscan.ui

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.arkeosar.groundscan.databinding.ActivityCalibrationBinding
import com.arkeosar.groundscan.sensors.MagnetometerCalibration

/**
 * Magnetometer hard-iron calibration screen, modeled on the reference
 * app's "Kalibrasyon Modu": three live scatter plots (XY, YZ, XZ) of
 * raw magnetometer samples while the person rotates the phone, a
 * swipe-to-start control, and a finish/save step that computes and
 * persists the bias offset via [MagnetometerCalibration].
 *
 * Calibration here only corrects constant ("hard-iron") bias - see
 * [MagnetometerCalibration]'s documentation for what that does and
 * doesn't cover.
 */
class CalibrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalibrationBinding
    private lateinit var calibration: MagnetometerCalibration

    private var sensorManager: SensorManager? = null
    private var magnetometer: Sensor? = null
    private var listener: SensorEventListener? = null

    private var session: MagnetometerCalibration.Session? = null
    private var isRunning = false

    private var refreshHandler: Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        calibration = MagnetometerCalibration(this)
        sensorManager = ContextCompat.getSystemService(this, SensorManager::class.java)
        magnetometer = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        binding.graphXY.setPointColor(0xFFE0483B.toInt())
        binding.graphYZ.setPointColor(0xFF2DBE6C.toInt())
        binding.graphXZ.setPointColor(0xFF1C5FA8.toInt())

        setupSwipeToStart()

        binding.btnFinishSave.setOnClickListener { finishAndSave() }

        if (calibration.hasCalibration) {
            binding.sampleCountText.text = getString(com.arkeosar.groundscan.R.string.calibration_saved)
        }
    }

    private fun setupSwipeToStart() {
        binding.startStopHandle.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_MOVE -> {
                    val parent = view.parent as android.view.ViewGroup
                    val maxX = (parent.width - view.width).toFloat()
                    val targetX = (view.x + event.x - view.width / 2f).coerceIn(0f, maxX)
                    view.x = targetX
                    if (!isRunning && targetX > maxX * 0.85f) {
                        startSession()
                    } else if (isRunning && targetX < maxX * 0.15f) {
                        // dragged back to the start while running: treat as stop
                        stopSession()
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun startSession() {
        val manager = sensorManager
        val sensor = magnetometer
        if (manager == null || sensor == null) {
            Toast.makeText(this, com.arkeosar.groundscan.R.string.scan_no_device, Toast.LENGTH_SHORT).show()
            return
        }

        session = MagnetometerCalibration.Session()
        isRunning = true
        binding.startStopLabel.text = getString(com.arkeosar.groundscan.R.string.calibration_stop)

        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_MAGNETIC_FIELD) return
                session?.addSample(event.values[0], event.values[1], event.values[2])
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        listener = sensorListener
        manager.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_UI)

        val handler = Handler(mainLooper)
        refreshHandler = handler
        val refresh = object : Runnable {
            override fun run() {
                val s = session
                if (s != null) {
                    binding.graphXY.updatePoints(s.pointsXY())
                    binding.graphYZ.updatePoints(s.pointsYZ())
                    binding.graphXZ.updatePoints(s.pointsXZ())
                    binding.sampleCountText.text = "${s.sampleCount} örnek toplandı"
                    binding.btnFinishSave.isEnabled = s.sampleCount >= 10
                }
                if (isRunning) handler.postDelayed(this, 150L)
            }
        }
        handler.post(refresh)
    }

    private fun stopSession() {
        isRunning = false
        binding.startStopLabel.text = getString(com.arkeosar.groundscan.R.string.calibration_start)
        listener?.let { sensorManager?.unregisterListener(it) }
        listener = null
        refreshHandler?.removeCallbacksAndMessages(null)
    }

    private fun finishAndSave() {
        val currentSession = session
        if (currentSession == null || currentSession.sampleCount < 10) {
            Toast.makeText(this, com.arkeosar.groundscan.R.string.calibration_need_more_samples, Toast.LENGTH_SHORT).show()
            return
        }
        stopSession()
        val saved = calibration.finishSession(currentSession)
        if (saved) {
            Toast.makeText(this, com.arkeosar.groundscan.R.string.calibration_saved, Toast.LENGTH_SHORT).show()
            finish()
        } else {
            Toast.makeText(this, com.arkeosar.groundscan.R.string.calibration_need_more_samples, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSession()
    }
}
