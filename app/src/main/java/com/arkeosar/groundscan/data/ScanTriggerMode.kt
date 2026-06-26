package com.arkeosar.groundscan.data

/**
 * How a measurement gets committed to the current grid cell in
 * [com.arkeosar.groundscan.ui.GridScanActivity], matching the reference
 * app's "Manuel" vs automatic walking-survey scan modes:
 *
 * - AUTOMATIC: every reading from the active [ScanDataSource] is
 *   written to the current cell as soon as it arrives, advancing
 *   through the grid on a timer/continuous basis.
 * - MANUAL: readings keep streaming in (so the live value display stays
 *   current), but nothing is written to the grid until the person taps
 *   the "Ölç" (measure) button - each tap commits the *current* live
 *   reading to the active cell and advances to the next one. This is
 *   for surveys where the person wants to physically position the
 *   device over a marked spot before capturing each point, rather than
 *   walking continuously.
 */
enum class ScanTriggerMode {
    AUTOMATIC,
    MANUAL
}
