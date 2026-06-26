package com.arkeosar.groundscan.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.arkeosar.groundscan.data.ScanReading
import com.arkeosar.groundscan.data.ScanSourceState
import com.arkeosar.groundscan.data.ScanSourceType
import com.arkeosar.groundscan.databinding.ActivityDetectorBinding
import com.arkeosar.groundscan.render.DisplayFunction
import com.arkeosar.groundscan.sensors.InternalSensorSource
import com.arkeosar.groundscan.sensors.MagnetometerCalibration
import com.arkeosar.groundscan.sensors.MagnetometerSignalProcessor
import com.arkeosar.groundscan.sensors.SimpleFft
import android.widget.ArrayAdapter

/**
 * Live single-point magnetometer detector screen.
 *
 * Unlike [ScanActivity] (which logs a full spatial grid for a ground
 * scan), this screen is a fast diagnostic / pinpointing tool: it shows
 * the live signal as a scrolling line graph, lets the person boost a
 * weak anomaly with a logarithmic gain control, and offers a one-tap
 * "measure noise" step that sets a sensible detection threshold from
 * the ambient signal instead of making the person guess one.
 *
 * The signal chain (noise floor -> gain -> deadzone) is implemented in
 * [MagnetometerSignalProcessor]; this activity only wires it to the UI
 * and to the live sensor feed.
 *
 * Currently uses the phone's internal magnetometer only - wiring this
 * to [com.arkeosar.groundscan.bluetooth.BluetoothDataSource] as well
 * would just mean swapping the source the same way [ScanActivity] does.
 */
class DetectorActivity : AppCompatActivity() {

    companion object {
        /** Matches the reference app's fixed sliding-window size for noise estimation. */
        private const val NOISE_WINDOW_SIZE = 16

        /** Power-of-two window the FFT runs on; same size as the noise window by design. */
        private const val FFT_WINDOW_SIZE = 16

        /** Gain slider range: 0 .. 4 means amplification from 1x up to 10,000x. */
        private const val GAIN_SLIDER_MAX = 4f

        /** How many recent (boosted) samples the line graph keeps on screen. */
        private const val GRAPH_HISTORY_SIZE = 80
    }

    private lateinit var binding: ActivityDetectorBinding
    private lateinit var calibration: MagnetometerCalibration
    private lateinit var sensorSource: InternalSensorSource

    private val noiseEstimator = MagnetometerSignalProcessor.NoiseFloorEstimator(NOISE_WINDOW_SIZE)
    private val history = ArrayDeque<Float>()
    private val fftWindow = ArrayDeque<Float>()

    /** Detection threshold currently in effect (0 = no deadzone applied yet). */
    private var noiseThreshold: Float = 0f

    /** Which axis combination drives the value shown - mirrors the reference app's function picker. */
    private var currentFunction: DisplayFunction = DisplayFunction.XYZ

    /** True while accumulating samples for the "measure noise" action. */
    private var measuringNoise: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetectorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        calibration = MagnetometerCalibration(this)
        sensorSource = InternalSensorSource(this)

        setupFunctionPicker()

        binding.gainSlider.valueFrom = 0f
        binding.gainSlider.valueTo = GAIN_SLIDER_MAX
        binding.gainSlider.value = 0f
        binding.gainSlider.addOnChangeListener { _, _, _ -> /* read live in onReading */ }

        binding.preserveSignSwitch.isChecked = true

        binding.buttonMeasureNoise.setOnClickListener { confirmMeasureNoise() }

        binding.thresholdSlider.valueFrom = 0f
        binding.thresholdSlider.valueTo = 50f // µT-scale headroom; noise measurement can override this directly
        binding.thresholdSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) noiseThreshold = value
        }
    }

    override fun onResume() {
        super.onResume()
        if (!sensorSource.isAvailable()) {
            Toast.makeText(this, getString(com.arkeosar.groundscan.R.string.scan_no_device), Toast.LENGTH_LONG).show()
        }
        sensorSource.start(
            onStateChanged = { state -> runOnUiThread { updateStatus(state) } },
            onReading = { newReading -> runOnUiThread { onReading(newReading) } }
        )
    }

    override fun onPause() {
        super.onPause()
        sensorSource.stop()
    }

    private fun updateStatus(state: ScanSourceState) {
        val label = when (state) {
            ScanSourceState.ACTIVE -> getString(com.arkeosar.groundscan.R.string.scan_connected)
            ScanSourceState.UNAVAILABLE, ScanSourceState.ERROR -> getString(com.arkeosar.groundscan.R.string.scan_no_device)
            ScanSourceState.CONNECTING, ScanSourceState.IDLE -> getString(com.arkeosar.groundscan.R.string.scan_connect)
        }
        binding.statusText.text = "${getString(com.arkeosar.groundscan.R.string.source_internal_sensor)} — $label"
    }

    /**
     * Mirrors [ScanActivity]'s function picker: the same [DisplayFunction]
     * enum drives both screens, so the seven X/Y/Z/XY/YZ/XZ/XYZ options
     * and their labels stay consistent across the app.
     */
    private fun setupFunctionPicker() {
        val labels = DisplayFunction.entries.map { it.label }
        binding.spinnerFunction.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.spinnerFunction.setSelection(DisplayFunction.entries.indexOf(currentFunction))
        binding.spinnerFunction.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                currentFunction = DisplayFunction.entries[position]
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun onReading(reading: ScanReading) {
        // Recompute the scalar from raw X/Y/Z using whichever function is
        // selected, instead of always using reading.value (which is fixed
        // to XYZ magnitude). Falls back to reading.value if a source
        // doesn't expose raw axes (e.g. a Bluetooth probe).
        val functionValue = if (reading.rawX != null && reading.rawY != null && reading.rawZ != null) {
            currentFunction.apply(reading.rawX, reading.rawY, reading.rawZ)
        } else {
            reading.value
        }

        noiseEstimator.addSample(functionValue)

        if (measuringNoise && noiseEstimator.isFull) {
            finishMeasureNoise()
        }

        val gainExponent = binding.gainSlider.value
        val preserveSign = binding.preserveSignSwitch.isChecked

        val boosted = MagnetometerSignalProcessor.process(
            rawValue = functionValue,
            gainExponent = gainExponent,
            noiseThreshold = noiseThreshold,
            preserveSign = preserveSign
        )

        pushHistory(boosted)
        binding.lineGraphView.updateValues(history.toList())
        binding.columnGraphView.setValue(boosted)
        binding.heatmapView.setValue(boosted)
        binding.liveValueText.text = String.format("%.3f", boosted)

        updateFrequencyView(boosted)
    }

    /**
     * Feeds the same boosted signal into a separate fixed-size window
     * and, once full, runs [SimpleFft] over it - mirroring the reference
     * app's pattern of running its FFT on a 16-sample sliding window of
     * recent readings. Shown as a second strip-chart so the person can
     * compare the raw time-domain trace against its low-frequency
     * content (e.g. a slow walking-speed sweep over a buried anomaly
     * shows up as energy in the first couple of bins).
     */
    private fun updateFrequencyView(boostedValue: Float) {
        fftWindow.addLast(boostedValue)
        if (fftWindow.size > FFT_WINDOW_SIZE) fftWindow.removeFirst()
        if (fftWindow.size < FFT_WINDOW_SIZE) return

        val magnitudes = SimpleFft.lowFrequencyMagnitudes(fftWindow.toList())
        binding.frequencyGraphView.updateValues(magnitudes)
        binding.spectrumBarView.updateMagnitudes(magnitudes)
    }

    private fun pushHistory(value: Float) {
        history.addLast(value)
        if (history.size > GRAPH_HISTORY_SIZE) history.removeFirst()
    }

    /**
     * Mirrors the reference app's flow: warn the person to keep the
     * phone still and away from new magnetic interference before
     * sampling, since the result becomes the detection threshold.
     */
    private fun confirmMeasureNoise() {
        AlertDialog.Builder(this)
            .setTitle(com.arkeosar.groundscan.R.string.noise_measurement_warning_title)
            .setMessage(com.arkeosar.groundscan.R.string.noise_measurement_warning_message)
            .setPositiveButton(com.arkeosar.groundscan.R.string.noise_measurement_start_button) { _, _ ->
                startMeasureNoise()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startMeasureNoise() {
        noiseEstimator.reset()
        fftWindow.clear()
        measuringNoise = true
        binding.thresholdSlider.value = binding.thresholdSlider.valueFrom
    }

    private fun finishMeasureNoise() {
        measuringNoise = false
        val measured = noiseEstimator.noiseFloor()
        noiseThreshold = measured.coerceIn(binding.thresholdSlider.valueFrom, binding.thresholdSlider.valueTo)
        binding.thresholdSlider.value = noiseThreshold
        Toast.makeText(
            this,
            getString(com.arkeosar.groundscan.R.string.noise_measurement_result, measured),
            Toast.LENGTH_SHORT
        ).show()
    }
}
