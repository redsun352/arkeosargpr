package com.arkeosar.groundscan.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.arkeosar.groundscan.data.ScanGrid
import com.arkeosar.groundscan.data.ScanTriggerMode
import com.arkeosar.groundscan.databinding.ActivityScanAreaSetupBinding

/**
 * Lets the person specify the physical scan area (width x length, in
 * meters) and which side the sweep should start from, before launching
 * [GridScanActivity]. The grid's row/column counts are derived from
 * width/length independently (not forced square), so a wide-and-short
 * area produces a wide-and-short grid and vice versa - matching the
 * "kare alanın uzunluğuna bağlı olarak yatay veya dikey şekilde kare
 * oluşturulması" request.
 */
class ScanAreaSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanAreaSetupBinding
    private var triggerMode: ScanTriggerMode = ScanTriggerMode.AUTOMATIC
    private var gprMode: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanAreaSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        triggerMode = intent.getStringExtra(GridScanActivity.EXTRA_TRIGGER_MODE)
            ?.let { runCatching { ScanTriggerMode.valueOf(it) }.getOrNull() }
            ?: ScanTriggerMode.AUTOMATIC
        gprMode = intent.getBooleanExtra(GridScanActivity.EXTRA_GPR_MODE, false)

        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { updatePreview() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        binding.inputWidth.addTextChangedListener(watcher)
        binding.inputLength.addTextChangedListener(watcher)
        updatePreview()

        binding.btnStartScan.setOnClickListener { startScan() }
    }

    private fun parseMeters(text: String): Int? {
        val value = text.trim().replace(',', '.').toFloatOrNull() ?: return null
        if (value <= 0f) return null
        return value.toInt().coerceAtLeast(1)
    }

    private fun updatePreview() {
        val width = parseMeters(binding.inputWidth.text.toString())
        val length = parseMeters(binding.inputLength.text.toString())
        if (width == null || length == null) {
            binding.gridPreviewText.text = ""
            return
        }
        val cols = ScanGrid.resolutionForMeters(width, samplesPerMeter = 1).coerceIn(2, 12)
        val rows = ScanGrid.resolutionForMeters(length, samplesPerMeter = 1).coerceIn(2, 12)
        binding.gridPreviewText.text = getString(com.arkeosar.groundscan.R.string.scan_area_grid_preview, cols, rows)
    }

    private fun startScan() {
        val width = parseMeters(binding.inputWidth.text.toString())
        val length = parseMeters(binding.inputLength.text.toString())
        if (width == null || length == null) {
            Toast.makeText(this, com.arkeosar.groundscan.R.string.scan_area_invalid_input, Toast.LENGTH_SHORT).show()
            return
        }

        val startFromRight = binding.radioStartRight.isChecked

        startActivity(
            Intent(this, GridScanActivity::class.java)
                .putExtra(GridScanActivity.EXTRA_TRIGGER_MODE, triggerMode.name)
                .putExtra(GridScanActivity.EXTRA_GPR_MODE, gprMode)
                .putExtra(GridScanActivity.EXTRA_WIDTH_METERS, width)
                .putExtra(GridScanActivity.EXTRA_LENGTH_METERS, length)
                .putExtra(GridScanActivity.EXTRA_START_FROM_RIGHT, startFromRight)
        )
        finish()
    }
}
