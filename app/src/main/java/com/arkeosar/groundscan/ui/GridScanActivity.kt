package com.arkeosar.groundscan.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.arkeosar.groundscan.bluetooth.BluetoothDataSource
import com.arkeosar.groundscan.data.ScanAxis
import com.arkeosar.groundscan.data.ScanDataSource
import com.arkeosar.groundscan.data.ScanGrid
import com.arkeosar.groundscan.data.ScanReading
import com.arkeosar.groundscan.data.ScanSourceState
import com.arkeosar.groundscan.data.ScanSourceType
import com.arkeosar.groundscan.data.ScanTriggerMode
import com.arkeosar.groundscan.databinding.ActivityGridScanBinding
import com.arkeosar.groundscan.sensors.InternalSensorSource
import java.io.File

/**
 * 2D detector grid screen: the first step of a ground scan, modeled on
 * ArkeoMag / Thuban Lodestar's cell-by-cell scan view. The scanner walks
 * a column-major zigzag pattern (see [ScanAxis.COLUMN_MAJOR]); each cell
 * is colored as it's measured, with a live-interpolated preview for
 * unmeasured cells so the grid never looks blank mid-scan.
 *
 * Supports two trigger modes (see [ScanTriggerMode], chosen from
 * [MainActivity]'s scan-mode dialog before this activity launches):
 * - AUTOMATIC: every sensor reading is written to the current cell as
 *   it arrives (the original continuous-walking-survey behavior).
 * - MANUAL: readings keep streaming in for the live value display, but
 *   nothing is committed to the grid until the person taps "Ölç" - each
 *   tap starts a brief [com.arkeosar.groundscan.data.StackingAccumulator]
 *   capture (averaging several samples to reduce noise, a standard GPR/
 *   geophysics technique - see that class's docs), then commits the
 *   averaged result into the active cell and advances to the next one.
 *   This is for point-by-point surveys where the person positions the
 *   device, then explicitly captures a reading.
 *
 * When every cell has been measured, this hands off automatically to
 * [ScanActivity] for the 3D view, opened directly on the **3D Hacimsel**
 * tab (per the requested manual-scan workflow: measure -> 2D grid fills
 * in -> hand off straight into the volumetric view for analysis).
 */
class GridScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TRIGGER_MODE = "triggerMode"
        const val EXTRA_GPR_MODE = "gprMode"
        const val EXTRA_WIDTH_METERS = "widthMeters"
        const val EXTRA_LENGTH_METERS = "lengthMeters"
        const val EXTRA_START_FROM_RIGHT = "startFromRight"

        private const val DEFAULT_SCAN_METERS = 5
        private const val BLUETOOTH_CONNECT_TIMEOUT_MS = 6000L
        private const val TRANSITION_DELAY_MS = 900L // lets the "tamamlandı" moment register before handoff
        private const val MANUAL_STACK_SAMPLE_COUNT = 8 // averaged samples per manual "Ölç" tap
        private const val MAX_GRID_DIMENSION = 12 // caps grid size for a usable cell size on screen
    }

    private lateinit var binding: ActivityGridScanBinding
    private lateinit var grid: ScanGrid
    private lateinit var triggerMode: ScanTriggerMode

    private var activeSource: ScanDataSource? = null
    private var fallbackHandler: Handler? = null
    private var fallbackTriggered = false
    private var handedOff = false

    /** Most recent reading from the active source, used by the manual "Ölç" button. */
    private var latestReading: ScanReading? = null

    /** Non-null while a manual stacking capture is in progress (see [captureManualReading]). */
    private var stackingAccumulator: com.arkeosar.groundscan.data.StackingAccumulator? = null

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startBluetoothWithFallback()
        } else {
            startInternalSensor()
        }
    }

    private var isGprMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGridScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        triggerMode = intent.getStringExtra(EXTRA_TRIGGER_MODE)
            ?.let { runCatching { ScanTriggerMode.valueOf(it) }.getOrNull() }
            ?: ScanTriggerMode.AUTOMATIC
        isGprMode = intent.getBooleanExtra(EXTRA_GPR_MODE, false)
        if (isGprMode) {
            binding.gprDisclaimerText.visibility = android.view.View.VISIBLE
        }

        val widthMeters = intent.getIntExtra(EXTRA_WIDTH_METERS, DEFAULT_SCAN_METERS)
        val lengthMeters = intent.getIntExtra(EXTRA_LENGTH_METERS, DEFAULT_SCAN_METERS)
        val startFromRight = intent.getBooleanExtra(EXTRA_START_FROM_RIGHT, false)

        // Grid shape follows the physical area's aspect ratio - a wide,
        // short area produces a wide, short grid (more columns than
        // rows) and vice versa, rather than always forcing a square.
        val columns = ScanGrid.resolutionForMeters(widthMeters, samplesPerMeter = 1).coerceIn(2, MAX_GRID_DIMENSION)
        val rows = ScanGrid.resolutionForMeters(lengthMeters, samplesPerMeter = 1).coerceIn(2, MAX_GRID_DIMENSION)
        grid = ScanGrid(
            columns = columns,
            rows = rows,
            zigzag = true,
            scanAxis = ScanAxis.COLUMN_MAJOR,
            startFromOpposite = startFromRight
        )
        binding.gridScanView.grid = grid

        if (triggerMode == ScanTriggerMode.MANUAL) {
            binding.btnMeasure.visibility = android.view.View.VISIBLE
            binding.btnMeasure.setOnClickListener { captureManualReading() }
        }

        ensurePermissionsThenConnect()
    }

    private fun ensurePermissionsThenConnect() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_CONNECT
            needed += Manifest.permission.BLUETOOTH_SCAN
        }
        needed += Manifest.permission.ACCESS_FINE_LOCATION

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startBluetoothWithFallback()
        } else {
            requestPermissions.launch(missing.toTypedArray())
        }
    }

    private fun startBluetoothWithFallback() {
        fallbackTriggered = false
        val bluetoothSource = BluetoothDataSource(this)
        activeSource = bluetoothSource

        val handler = Handler(mainLooper)
        fallbackHandler = handler
        val timeoutRunnable = Runnable {
            if (!fallbackTriggered) triggerFallbackToInternalSensor()
        }
        handler.postDelayed(timeoutRunnable, BLUETOOTH_CONNECT_TIMEOUT_MS)

        bluetoothSource.start(
            onStateChanged = { state ->
                runOnUiThread {
                    updateStatusText(ScanSourceType.BLUETOOTH_PROBE, state)
                    when (state) {
                        ScanSourceState.ACTIVE -> handler.removeCallbacks(timeoutRunnable)
                        ScanSourceState.UNAVAILABLE, ScanSourceState.ERROR -> {
                            handler.removeCallbacks(timeoutRunnable)
                            triggerFallbackToInternalSensor()
                        }
                        else -> {}
                    }
                }
            },
            onReading = { reading -> runOnUiThread { onSourceReading(reading) } }
        )
    }

    private fun triggerFallbackToInternalSensor() {
        if (fallbackTriggered) return
        fallbackTriggered = true
        activeSource?.stop()
        startInternalSensor()
    }

    private fun startInternalSensor() {
        val sensorSource = InternalSensorSource(this)
        activeSource = sensorSource
        sensorSource.start(
            onStateChanged = { state -> runOnUiThread { updateStatusText(ScanSourceType.INTERNAL_SENSOR, state) } },
            onReading = { reading -> runOnUiThread { onSourceReading(reading) } }
        )
    }

    private fun updateStatusText(source: ScanSourceType, state: ScanSourceState) {
        val sourceLabel = when (source) {
            ScanSourceType.BLUETOOTH_PROBE -> getString(com.arkeosar.groundscan.R.string.source_bluetooth_probe)
            ScanSourceType.INTERNAL_SENSOR -> getString(com.arkeosar.groundscan.R.string.source_internal_sensor)
        }
        val stateLabel = when (state) {
            ScanSourceState.IDLE -> getString(com.arkeosar.groundscan.R.string.scan_disconnected)
            ScanSourceState.CONNECTING -> getString(com.arkeosar.groundscan.R.string.scan_connect)
            ScanSourceState.ACTIVE -> getString(com.arkeosar.groundscan.R.string.scan_connected)
            ScanSourceState.UNAVAILABLE -> getString(com.arkeosar.groundscan.R.string.scan_no_device)
            ScanSourceState.ERROR -> getString(com.arkeosar.groundscan.R.string.scan_disconnected)
        }
        val modeLabel = if (triggerMode == ScanTriggerMode.MANUAL) {
            " · " + getString(com.arkeosar.groundscan.R.string.grid_mode_manual)
        } else ""
        val gprPrefix = if (isGprMode) getString(com.arkeosar.groundscan.R.string.menu_gpr) + " · " else ""
        binding.statusText.text = "$gprPrefix$sourceLabel — $stateLabel$modeLabel"
    }

    /**
     * In AUTOMATIC mode, every reading is committed immediately. In
     * MANUAL mode, readings either feed the live value display (when
     * not actively stacking) or get accumulated into [stackingAccumulator]
     * (while a "Ölç" capture is in progress) - see [captureManualReading].
     */
    private fun onSourceReading(reading: ScanReading) {
        latestReading = reading

        val accumulator = stackingAccumulator
        if (accumulator != null) {
            accumulator.addSample(reading)
            binding.btnMeasure.text = getString(com.arkeosar.groundscan.R.string.grid_stacking_progress, accumulator.progress, accumulator.target)
            if (accumulator.isComplete) {
                val averaged = accumulator.result()
                stackingAccumulator = null
                binding.btnMeasure.text = getString(com.arkeosar.groundscan.R.string.grid_measure_button)
                binding.btnMeasure.isEnabled = true
                if (averaged != null) commitReading(averaged)
            }
        } else {
            binding.liveValueText.text = String.format("%.1f", reading.value)
            if (triggerMode == ScanTriggerMode.AUTOMATIC) {
                commitReading(reading)
            }
        }
    }

    /**
     * Starts a stacking capture: collects [StackingAccumulator]'s target
     * number of samples (a brief burst, arriving as fast as the active
     * source delivers them) and averages them into one lower-noise
     * reading before committing it to the active grid cell - see
     * [StackingAccumulator]'s documentation for why averaging multiple
     * samples reduces noise.
     */
    private fun captureManualReading() {
        if (handedOff || grid.isComplete || stackingAccumulator != null) return
        binding.btnMeasure.isEnabled = false
        stackingAccumulator = com.arkeosar.groundscan.data.StackingAccumulator(sampleCount = MANUAL_STACK_SAMPLE_COUNT)
    }

    private fun commitReading(reading: ScanReading) {
        if (handedOff || grid.isComplete) return

        val point = grid.addValue(
            value = reading.value,
            latitude = reading.latitude,
            longitude = reading.longitude,
            altitude = reading.altitude,
            rawX = reading.rawX,
            rawY = reading.rawY,
            rawZ = reading.rawZ
        )
        if (point != null) {
            binding.progressText.text = "${grid.filledPoints} / ${grid.totalPoints}"
            binding.gridScanView.refresh()

            if (grid.isComplete) {
                handOffToVolumetricView()
            }
        }
    }

    /**
     * Saves the completed grid to a temporary file and launches
     * [ScanActivity] directly on the 3D Hacimsel (volumetric) tab, then
     * finishes this activity - the requested "measure -> grid fills in
     * 2D -> straight into volumetric analysis" handoff.
     */
    private fun handOffToVolumetricView() {
        if (handedOff) return
        handedOff = true
        activeSource?.stop()

        binding.transitionOverlay.visibility = android.view.View.VISIBLE

        Handler(mainLooper).postDelayed({
            val dir = File(filesDir, "scans").also { it.mkdirs() }
            val file = File(dir, "handoff_${System.currentTimeMillis()}.asgs")
            com.arkeosar.groundscan.data.ArkeoSarFile.save(grid, file, metadata = mapOf("source" to "GridScanActivity"))

            startActivity(
                Intent(this, ScanActivity::class.java)
                    .putExtra("openFilePath", file.absolutePath)
                    .putExtra(ScanActivity.EXTRA_INITIAL_VIEW_MODE, com.arkeosar.groundscan.render.ViewMode.VOLUMETRIC_3D.name)
                    .putExtra(ScanActivity.EXTRA_GPR_MODE, isGprMode)
            )
            finish()
        }, TRANSITION_DELAY_MS)
    }

    override fun onDestroy() {
        super.onDestroy()
        fallbackHandler?.removeCallbacksAndMessages(null)
        if (!handedOff) {
            activeSource?.stop()
        }
    }
}
