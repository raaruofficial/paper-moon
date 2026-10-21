package com.hindsight.app.money

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The native purchase sheet's UI seam (AC-04).
 *
 * The purchase coordinator runs off the UI thread and calls
 * [confirm] (the [SheetConfirmer] interface); this controller
 * publishes the sheet data to the Compose UI (GameScreen renders it
 * as a native dialog with exactly the five AC-04 fields) and blocks
 * the calling thread until the user decides or the sheet times out.
 * A timeout is a cancellation — never a confirmation.
 *
 * The game page is not involved at any point: the sheet is native
 * shell UI, fed from shell-side values only.
 */
class PurchaseSheetController : SheetConfirmer {
    private val _pendingSheet = MutableStateFlow<PurchaseSheetData?>(null)
    val pendingSheet: StateFlow<PurchaseSheetData?> = _pendingSheet.asStateFlow()

    @Volatile
    private var latch: CountDownLatch? = null

    @Volatile
    private var decision: Boolean = false

    override fun confirm(sheet: PurchaseSheetData): Boolean {
        val thisLatch = CountDownLatch(1)
        synchronized(this) {
            if (latch != null) return false // a sheet is already up; never stack
            latch = thisLatch
            decision = false
        }
        _pendingSheet.value = sheet
        val answered = thisLatch.await(SHEET_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        synchronized(this) { latch = null }
        _pendingSheet.value = null
        return answered && decision
    }

    /** Called by the native sheet UI. */
    fun decide(confirmed: Boolean) {
        decision = confirmed
        latch?.countDown()
    }

    private companion object {
        const val SHEET_TIMEOUT_SECONDS: Long = 120L
    }
}
