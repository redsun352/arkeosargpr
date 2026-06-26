package com.arkeosar.groundscan.ui

import android.os.Bundle
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.arkeosar.groundscan.data.ArkeoSarFile
import com.arkeosar.groundscan.data.ScanGrid
import com.arkeosar.groundscan.databinding.ActivityBscanBinding
import java.io.File

/**
 * Displays a saved scan as a GPR-style B-Scan cross-section (see
 * [BScanView] for what this is and, importantly, is not). Opened from
 * [FileExplorerActivity] for a previously-saved `.asgs` file, or from
 * [ScanActivity] for the scan currently in memory.
 */
class BScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "filePath"
    }

    private lateinit var binding: ActivityBscanBinding
    private lateinit var grid: ScanGrid
    private var alongRow = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBscanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        grid = if (filePath != null) {
            ArkeoSarFile.load(File(filePath))
        } else {
            // No scan to show; fall back to an empty grid rather than crashing.
            ScanGrid(columns = 6, rows = 6)
        }

        val maxLineIndex = if (alongRow) grid.rows - 1 else grid.columns - 1
        binding.seekLine.max = maxLineIndex.coerceAtLeast(0)
        binding.seekLine.progress = maxLineIndex / 2

        binding.switchAxis.setOnCheckedChangeListener { _, isChecked ->
            alongRow = !isChecked // unchecked = "Satır boyunca" (row), checked = "Sütun boyunca" (column)
            binding.switchAxis.text = if (isChecked) {
                getString(com.arkeosar.groundscan.R.string.bscan_axis_column)
            } else {
                getString(com.arkeosar.groundscan.R.string.bscan_axis_row)
            }
            val newMax = if (alongRow) grid.rows - 1 else grid.columns - 1
            binding.seekLine.max = newMax.coerceAtLeast(0)
            binding.seekLine.progress = (newMax / 2).coerceAtLeast(0)
            refreshLine()
        }

        binding.seekLine.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                binding.lineIndexText.text = value.toString()
                refreshLine()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        refreshLine()
    }

    private fun refreshLine() {
        val lineIndex = binding.seekLine.progress
        binding.bScanView.showLine(grid, lineIndex, alongRow)
    }
}
