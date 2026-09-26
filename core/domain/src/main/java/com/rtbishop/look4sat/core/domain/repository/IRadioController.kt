package com.rtbishop.look4sat.core.domain.repository

interface IRadioController {

    val isConnected: Boolean

    suspend fun connect(): Boolean

    suspend fun disconnect()

    suspend fun setFrequency(frequencyHz: Long): Boolean

    suspend fun setMode(mode: String): Boolean

    suspend fun setCtcssMode(enabled: Boolean): Boolean

    suspend fun setCtcssTone(toneHz: Double): Boolean

    suspend fun readFrequencyAndMode(): Pair<Long, String>?

    suspend fun pttOn(): Boolean

    suspend fun pttOff(): Boolean

    // ── Extended operations (IC-705 / CI-V) ──────────────────────────────

    /**
     * Select the band matching [frequencyHz] via the band stacking register.
     * Must be called before [setFrequency] and [setMode] when first tracking.
     * Default: no-op (Yaesu radios auto-switch band via frequency).
     */
    suspend fun setBand(frequencyHz: Long): Boolean = false

    /**
     * Select the active VFO.
     * @param vfoA true → VFO-A (main/RX), false → VFO-B (sub/TX in split).
     */
    suspend fun setVfo(vfoA: Boolean): Boolean = false

    /**
     * Enable or disable SPLIT mode (TX on sub-VFO, RX on main VFO).
     * Default: not supported.
     */
    suspend fun setSplitMode(enabled: Boolean): Boolean = false

    /**
     * Set the frequency of the currently active VFO.
     * Default: delegates to [setFrequency].
     */
    suspend fun setWorkingFrequency(frequencyHz: Long): Boolean =
        setFrequency(frequencyHz)

    /**
     * Set the frequency of the inactive/TX VFO.
     * Default: delegates to [setWorkingFrequency].
     */
    suspend fun setTxVfoFrequency(frequencyHz: Long): Boolean =
        setWorkingFrequency(frequencyHz)

    /**
     * Read the frequency of the currently active VFO.
     */
    suspend fun readWorkingFrequency(): Long? =
        readFrequencyAndMode()?.first

    /**
     * Read the frequency of the inactive/TX VFO.
     */
    suspend fun readTxVfoFrequency(): Long? =
        readWorkingFrequency()
}
