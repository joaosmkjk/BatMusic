package com.batmusic.app.audio

/**
 * Allocation-free linear gain ramp for parameter changes on the PCM thread.
 * A new target is reached over the requested number of frames, avoiding abrupt
 * preamp steps while keeping steady-state gain exact.
 */
internal class LinearGainRamp(initialGain: Float = 1f) {
    var currentGain: Float = initialGain.coerceIn(0f, 32f)
        private set
    private var targetGain: Float = currentGain
    private var step: Float = 0f
    private var remainingFrames: Int = 0

    fun reset(gain: Float = 1f) {
        currentGain = gain.takeIf { it.isFinite() }?.coerceIn(0f, 32f) ?: 1f
        targetGain = currentGain
        step = 0f
        remainingFrames = 0
    }

    fun setTarget(gain: Float, rampFrames: Int) {
        val safeTarget = gain.takeIf { it.isFinite() }?.coerceIn(0f, 32f) ?: 1f
        if (safeTarget == targetGain) return
        targetGain = safeTarget
        remainingFrames = rampFrames.coerceAtLeast(1)
        step = (targetGain - currentGain) / remainingFrames
    }

    fun next(): Float {
        if (remainingFrames > 0) {
            currentGain += step
            remainingFrames--
            if (remainingFrames == 0) currentGain = targetGain
        }
        return currentGain
    }
}
