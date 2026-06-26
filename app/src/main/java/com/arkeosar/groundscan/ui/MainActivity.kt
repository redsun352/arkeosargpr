package com.arkeosar.groundscan.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.arkeosar.groundscan.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCalibration.setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }

        binding.btnMagnetometer.setOnClickListener {
            startActivity(
                Intent(this, ScanActivity::class.java)
                    .putExtra(ScanActivity.EXTRA_MODE, ScanActivity.MODE_MAGNETOMETER)
            )
        }

        binding.btnGroundScan.setOnClickListener {
            showScanModeDialog(gprMode = false)
        }

        binding.btnGpr.setOnClickListener {
            showScanModeDialog(gprMode = true)
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnFiles.setOnClickListener {
            startActivity(Intent(this, FileExplorerActivity::class.java))
        }
    }

    /**
     * Lets the person choose between automatic (continuous, walking-survey
     * style) and manual (tap-to-measure, one reading per button press)
     * scanning before launching [GridScanActivity] - matching the
     * reference app's "Manuel" vs automatic scan mode choice.
     *
     * @param gprMode when true, the resulting scan -> grid -> handoff
     *   flow is launched and labeled as the "GPR" flow (see
     *   [GridScanActivity.EXTRA_GPR_MODE]) - same underlying scan and
     *   analysis machinery, surfaced under the GPR-style framing and
     *   analysis-tools-first presentation described in
     *   [ScanActivity.EXTRA_GPR_MODE]'s usage.
     */
    private fun showScanModeDialog(gprMode: Boolean) {
        val options = arrayOf(
            getString(com.arkeosar.groundscan.R.string.grid_mode_automatic),
            getString(com.arkeosar.groundscan.R.string.grid_mode_manual)
        )
        val titleRes = if (gprMode) com.arkeosar.groundscan.R.string.menu_gpr
        else com.arkeosar.groundscan.R.string.menu_ground_scan
        android.app.AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setItems(options) { _, index ->
                val mode = if (index == 0) com.arkeosar.groundscan.data.ScanTriggerMode.AUTOMATIC
                else com.arkeosar.groundscan.data.ScanTriggerMode.MANUAL
                startActivity(
                    Intent(this, ScanAreaSetupActivity::class.java)
                        .putExtra(GridScanActivity.EXTRA_TRIGGER_MODE, mode.name)
                        .putExtra(GridScanActivity.EXTRA_GPR_MODE, gprMode)
                )
            }
            .show()
    }
}
