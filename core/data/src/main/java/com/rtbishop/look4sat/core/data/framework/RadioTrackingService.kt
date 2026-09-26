/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.data.framework

import android.bluetooth.BluetoothManager
import android.util.Log
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.predict.SPEED_OF_LIGHT
import com.rtbishop.look4sat.core.domain.repository.IRadioController
import com.rtbishop.look4sat.core.domain.repository.IRadioTrackingService
import com.rtbishop.look4sat.core.domain.repository.ISatelliteRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.repository.RadioTrackingState
import com.rtbishop.look4sat.core.domain.utility.TransponderMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RadioTrackingService(
    private val appScope: CoroutineScope,
    private val bluetoothManager: BluetoothManager,
    private val satelliteRepo: ISatelliteRepo,
    private val settingsRepo: ISettingsRepo,
    private val ftx1Controller: IRadioController
) : IRadioTrackingService {

    private val tag = "RadioTracking"

    /** Delay between each step of the split-mode init sequence (ms). */
    private val INIT_STEP_DELAY_MS = 200L

    private val _state =
        MutableStateFlow(RadioTrackingState())

    override val state: StateFlow<RadioTrackingState> =
        _state

    private var txController: IRadioController? = null
    private var rxController: IRadioController? = null
    private var trackingJob: Job? = null

    // ── Connection ──────────────────────────────────────────────────────────

    override suspend fun connectRadios() {

        txController?.disconnect()

        if (rxController !== txController) {
            rxController?.disconnect()
        }

        txController = null
        rxController = null

        val rcSettings =
            settingsRepo.radioControlSettings.value

        val txAddr =
            rcSettings.txRadioAddress

        val rxAddr =
            rcSettings.rxRadioAddress

        val isIcom =
            rcSettings.radioModel ==
                RadioControlSettings.MODEL_ICOM_IC705

        val isFtx1 =
            rcSettings.radioModel ==
                RadioControlSettings.MODEL_YAESU_FTX1

        val isSplit =
            isIcom && rcSettings.splitMode

        Log.i(
            tag,
            "connectRadios model=${rcSettings.radioModel} split=$isSplit TX=$txAddr RX=$rxAddr"
        )

        /*
         * Yaesu FTX-1
         *
         * One physical transceiver:
         * MAIN = RX
         * SUB  = TX
         *
         * Connection is direct USB CAT-1.
         */
        if (isFtx1) {

            txController = ftx1Controller
            rxController = null

            _state.update {
                it.copy(
                    errorMessage = null
                )
            }

            val ok =
                ftx1Controller.connect()

            _state.update {
                it.copy(
                    txConnected = ok,
                    rxConnected = false,
                    errorMessage =
                        if (ok) {
                            null
                        } else {
                            "Could not connect to Yaesu FTX-1 via USB"
                        }
                )
            }

            Log.i(
                tag,
                "FTX-1 USB connected=$ok"
            )

            return
        }

        /*
         * IC-705 single-radio split mode
         */
        if (isSplit) {

            if (txAddr.isBlank()) {

                _state.update {
                    it.copy(
                        errorMessage =
                            "No radio address configured in Settings"
                    )
                }

                return
            }

            val tx =
                makeController(
                    isIcom,
                    txAddr
                )

            txController = tx
            rxController = null

            _state.update {
                it.copy(
                    errorMessage = null
                )
            }

            val txOk =
                tx.connect()

            _state.update {
                it.copy(
                    txConnected = txOk,
                    rxConnected = false,
                    errorMessage =
                        if (!txOk) {
                            "Could not connect to radio ($txAddr)"
                        } else {
                            null
                        }
                )
            }

            Log.i(
                tag,
                "IC-705 split mode connected: txOk=$txOk"
            )

            return
        }

        /*
         * Traditional two-radio mode
         */
        if (
            txAddr.isBlank() &&
            rxAddr.isBlank()
        ) {

            _state.update {
                it.copy(
                    errorMessage =
                        "No radio addresses configured in Settings"
                )
            }

            return
        }

        val tx =
            makeController(
                isIcom,
                txAddr
            )

        val rx =
            makeController(
                isIcom,
                rxAddr
            )

        txController = tx
        rxController = rx

        _state.update {
            it.copy(
                errorMessage = null
            )
        }

        val txOk =
            if (txAddr.isNotBlank()) {
                tx.connect()
            } else {
                false
            }

        val rxOk =
            if (rxAddr.isNotBlank()) {
                rx.connect()
            } else {
                false
            }

        _state.update {
            it.copy(
                txConnected = txOk,
                rxConnected = rxOk,
                errorMessage =
                    when {
                        !txOk && !rxOk ->
                            "Could not connect to TX and RX radios"

                        !txOk ->
                            "Could not connect to TX radio ($txAddr)"

                        !rxOk ->
                            "Could not connect to RX radio ($rxAddr)"

                        else ->
                            null
                    }
            )
        }

        Log.i(
            tag,
            "Dual-radio connected: txOk=$txOk rxOk=$rxOk"
        )
    }

    private fun makeController(
        isIcom: Boolean,
        address: String
    ): IRadioController =
        if (isIcom) {
            Ic705Controller(
                bluetoothManager,
                address
            )
        } else {
            Ft817Controller(
                bluetoothManager,
                address
            )
        }

    override suspend fun disconnectRadios() {

        stopTracking()

        txController?.disconnect()

        if (rxController !== txController) {
            rxController?.disconnect()
        }

        txController = null
        rxController = null

        _state.update {
            it.copy(
                txConnected = false,
                rxConnected = false,
                isActive = false
            )
        }
    }

    // ── Tracking ────────────────────────────────────────────────────────────

    override fun startTracking(
        pass: OrbitalPass,
        transponder: SatRadio,
        txBaseFreqHz: Long?
    ) {

        _state.update {
            it.copy(
                isActive = true,
                currentPass = pass,
                selectedTransponder = transponder,
                txBaseFrequencyHz = txBaseFreqHz
            )
        }

        trackingJob?.cancel()

        val rcSettings =
            settingsRepo.radioControlSettings.value

        val isIcom =
            rcSettings.radioModel ==
                RadioControlSettings.MODEL_ICOM_IC705

        val isSplit =
            isIcom &&
                rcSettings.splitMode

        if (isSplit) {

            trackingJob =
                appScope.launch {
                    runSplitTracking(
                        transponder,
                        txBaseFreqHz
                    )
                }

        } else {

            trackingJob =
                appScope.launch {
                    runDualRadioTracking(
                        transponder,
                        txBaseFreqHz
                    )
                }
        }
    }

    // ── Dual-radio tracking ─────────────────────────────────────────────────

    private suspend fun runDualRadioTracking(
        transponder: SatRadio,
        initialTxBaseFreqHz: Long?
    ) {

        val tx = txController
        val rx = rxController

        val txMode =
            transponder.uplinkMode

        val rxMode =
            transponder.downlinkMode
                ?: transponder.uplinkMode?.let {
                    TransponderMapper
                        .mapUplinkModeToDownlinkMode(
                            it,
                            transponder.isInverted
                        )
                }

        Log.i(
            tag,
            "DualRadio start: txMode=$txMode rxMode=$rxMode"
        )

        if (
            tx != null &&
            tx.isConnected &&
            txMode != null
        ) {

            Log.d(
                tag,
                "Setting TX mode: $txMode"
            )

            tx.setMode(txMode)
        }

        if (
            rx != null &&
            rx.isConnected &&
            rxMode != null
        ) {

            Log.d(
                tag,
                "Setting RX mode: $rxMode"
            )

            rx.setMode(rxMode)
        }

        if (
            txMode?.uppercase() == "FM"
        ) {

            _state.value
                .ctcssTone
                ?.let { tone ->

                    Log.d(
                        tag,
                        "Setting CTCSS: ${tone}Hz"
                    )

                    tx?.setCtcssTone(tone)
                    tx?.setCtcssMode(true)
                }
        }

        _state.update {
            it.copy(
                txMode = txMode,
                rxMode = rxMode
            )
        }

        var lastSetTxFreq = 0.0
        var lastSetRxFreq = 0.0
        var tuningRadio = ""
        var lastReadFreq = 0L
        var stableCount = 0

        while (
            currentCoroutineContext().isActive
        ) {

            val currentState =
                _state.value

            if (!currentState.isActive) {
                break
            }

            val satPass =
                currentState.currentPass
                    ?: break

            val xpdr =
                currentState.selectedTransponder
                    ?: break

            var txBaseFreq =
                currentState.txBaseFrequencyHz

            val stationPos =
                settingsRepo.stationPosition.value

            val pos =
                satelliteRepo.getPosition(
                    satPass.orbitalObject,
                    stationPos,
                    System.currentTimeMillis()
                )

            val txNow =
                txController

            val rxNow =
                rxController

            val v =
                pos.distanceRate * 1000.0

            if (
                tuningRadio.isNotEmpty()
            ) {

                val radio =
                    if (tuningRadio == "tx") {
                        txNow
                    } else {
                        rxNow
                    }

                if (
                    radio != null &&
                    radio.isConnected
                ) {

                    val read =
                        radio.readFrequencyAndMode()

                    if (read != null) {

                        val (freq, _) =
                            read

                        if (
                            kotlin.math.abs(
                                freq - lastReadFreq
                            ) <= 20
                        ) {
                            stableCount++
                        } else {
                            stableCount = 0
                            lastReadFreq = freq
                        }

                        if (
                            stableCount >= 2
                        ) {

                            if (
                                tuningRadio == "tx" &&
                                txBaseFreq != null
                            ) {

                                val newBase =
                                    (
                                        freq.toDouble() *
                                            SPEED_OF_LIGHT /
                                            (SPEED_OF_LIGHT + v)
                                        ).toLong()

                                if (newBase > 0) {

                                    txBaseFreq =
                                        newBase

                                    _state.update {
                                        it.copy(
                                            txBaseFrequencyHz =
                                                newBase
                                        )
                                    }

                                    Log.i(
                                        tag,
                                        "TX tuning done → base=$newBase"
                                    )
                                }

                            } else if (
                                tuningRadio == "rx"
                            ) {

                                val rxNominal =
                                    (
                                        freq.toDouble() *
                                            SPEED_OF_LIGHT /
                                            (SPEED_OF_LIGHT - v)
                                        ).toLong()

                                val newTxBase =
                                    TransponderMapper
                                        .mapDownlinkToUplink(
                                            rxNominal,
                                            xpdr
                                        )

                                if (
                                    newTxBase != null &&
                                    newTxBase > 0
                                ) {

                                    txBaseFreq =
                                        newTxBase

                                    _state.update {
                                        it.copy(
                                            txBaseFrequencyHz =
                                                newTxBase
                                        )
                                    }

                                    Log.i(
                                        tag,
                                        "RX tuning done → txBase=$newTxBase"
                                    )
                                }
                            }

                            tuningRadio = ""
                            stableCount = 0
                            lastSetTxFreq = 0.0
                            lastSetRxFreq = 0.0
                        }
                    }
                }

            } else {

                if (
                    txBaseFreq != null &&
                    txNow != null &&
                    txNow.isConnected &&
                    lastSetTxFreq > 0.0
                ) {

                    val read =
                        txNow.readFrequencyAndMode()

                    if (
                        read != null &&
                        kotlin.math.abs(
                            read.first -
                                lastSetTxFreq
                        ) >= 20.0
                    ) {

                        tuningRadio = "tx"
                        lastReadFreq = read.first
                        stableCount = 0

                        Log.i(
                            tag,
                            "TX tuning detected (read=${read.first}, lastSet=$lastSetTxFreq)"
                        )
                    }
                }

                if (
                    tuningRadio.isEmpty() &&
                    rxNow != null &&
                    rxNow.isConnected &&
                    lastSetRxFreq > 0.0
                ) {

                    val read =
                        rxNow.readFrequencyAndMode()

                    if (
                        read != null &&
                        kotlin.math.abs(
                            read.first -
                                lastSetRxFreq
                        ) >= 20.0
                    ) {

                        tuningRadio = "rx"
                        lastReadFreq = read.first
                        stableCount = 0

                        Log.i(
                            tag,
                            "RX tuning detected (read=${read.first}, lastSet=$lastSetRxFreq)"
                        )
                    }
                }
            }

            val txRadioFreq =
                txBaseFreq?.let {
                    pos.getUplinkFreq(it)
                }

            val rxBaseFreq =
                if (txBaseFreq != null) {

                    TransponderMapper
                        .mapUplinkToDownlink(
                            txBaseFreq,
                            xpdr
                        )

                } else {

                    xpdr.downlinkLow
                }

            val rxRadioFreq =
                rxBaseFreq?.let {
                    pos.getDownlinkFreq(it)
                }

            if (
                tuningRadio.isEmpty()
            ) {

                if (
                    txNow != null &&
                    txNow.isConnected &&
                    txRadioFreq != null
                ) {

                    txNow.setFrequency(
                        txRadioFreq
                    )

                    lastSetTxFreq =
                        txRadioFreq.toDouble()
                }

                if (
                    rxNow != null &&
                    rxNow.isConnected &&
                    rxRadioFreq != null
                ) {

                    rxNow.setFrequency(
                        rxRadioFreq
                    )

                    lastSetRxFreq =
                        rxRadioFreq.toDouble()
                }
            }

            _state.update {
                it.copy(
                    txConnected =
                        txNow?.isConnected
                            ?: false,

                    rxConnected =
                        rxNow?.isConnected
                            ?: false,

                    txFrequencyHz =
                        txRadioFreq,

                    rxFrequencyHz =
                        rxRadioFreq,

                    azimuth =
                        Math.toDegrees(
                            pos.azimuth
                        ),

                    elevation =
                        Math.toDegrees(
                            pos.elevation
                        ),

                    distance =
                        pos.distance
                )
            }

            delay(1000)
        }
    }

    // ── IC-705 split-radio tracking ─────────────────────────────────────────

    private suspend fun runSplitTracking(
        transponder: SatRadio,
        initialTxBaseFreqHz: Long?
    ) {

        val radio =
            txController
                ?: return

        if (!radio.isConnected) {
            return
        }

        val txMode =
            transponder.uplinkMode

        val rxMode =
            transponder.downlinkMode
                ?: transponder.uplinkMode?.let {
                    TransponderMapper
                        .mapUplinkModeToDownlinkMode(
                            it,
                            transponder.isInverted
                        )
                }

        val txCenter =
            when {
                transponder.uplinkLow != null &&
                    transponder.uplinkHigh != null ->

                    (
                        transponder.uplinkLow!! +
                            transponder.uplinkHigh!!
                        ) / 2

                transponder.uplinkLow != null ->
                    transponder.uplinkLow!!

                else ->
                    null
            }

        val rxNominal =
            if (txCenter != null) {

                TransponderMapper
                    .mapUplinkToDownlink(
                        txCenter,
                        transponder
                    )

            } else {

                transponder.downlinkLow
            }

        val txBase =
            initialTxBaseFreqHz
                ?: txCenter

        Log.i(
            tag,
            "IC-705 split setup: txBase=${txBase}Hz rxNominal=${rxNominal}Hz txMode=$txMode rxMode=$rxMode"
        )

        // VFO-A = RX

        radio.setVfo(
            vfoA = true
        )

        if (rxNominal != null) {

            radio.setBand(
                rxNominal
            )

            radio.setFrequency(
                rxNominal
            )
        }

        if (rxMode != null) {
            radio.setMode(
                rxMode
            )
        }

        // VFO-B = TX

        radio.setVfo(
            vfoA = false
        )

        if (txBase != null) {

            radio.setBand(
                txBase
            )

            radio.setFrequency(
                txBase
            )
        }

        if (txMode != null) {
            radio.setMode(
                txMode
            )
        }

        if (
            txMode?.uppercase() == "FM"
        ) {

            val tone =
                _state.value.ctcssTone

            if (tone != null) {

                radio.setCtcssTone(
                    tone
                )

                radio.setCtcssMode(
                    true
                )

            } else {

                radio.setCtcssMode(
                    false
                )
            }
        }

        radio.setVfo(
            vfoA = true
        )

        radio.setSplitMode(
            enabled = true
        )

        _state.update {
            it.copy(
                txMode = txMode,
                rxMode = rxMode,
                txBaseFrequencyHz = txBase
            )
        }

        Log.i(
            tag,
            "IC-705 split init done — entering tracking loop"
        )

        var lastSetTxFreq = 0.0
        var lastSetRxFreq = 0.0
        var tuningRadio = ""
        var lastReadFreq = 0L
        var stableCount = 0

        while (
            currentCoroutineContext().isActive
        ) {

            val currentState =
                _state.value

            if (!currentState.isActive) {
                break
            }

            val satPass =
                currentState.currentPass
                    ?: break

            val xpdr =
                currentState.selectedTransponder
                    ?: break

            var txBaseFreq =
                currentState.txBaseFrequencyHz

            val stationPos =
                settingsRepo.stationPosition.value

            val pos =
                satelliteRepo.getPosition(
                    satPass.orbitalObject,
                    stationPos,
                    System.currentTimeMillis()
                )

            val v =
                pos.distanceRate * 1000.0

            if (
                tuningRadio.isNotEmpty()
            ) {

                val readFreq =
                    if (
                        tuningRadio == "tx"
                    ) {

                        radio.readTxVfoFrequency()

                    } else {

                        radio.readWorkingFrequency()
                    }

                if (readFreq != null) {

                    if (
                        kotlin.math.abs(
                            readFreq -
                                lastReadFreq
                        ) <= 20
                    ) {

                        stableCount++

                    } else {

                        stableCount = 0
                        lastReadFreq =
                            readFreq
                    }

                    if (
                        stableCount >= 2
                    ) {

                        if (
                            tuningRadio == "tx" &&
                            txBaseFreq != null
                        ) {

                            val newBase =
                                (
                                    readFreq.toDouble() *
                                        SPEED_OF_LIGHT /
                                        (SPEED_OF_LIGHT + v)
                                    ).toLong()

                            if (newBase > 0) {

                                txBaseFreq =
                                    newBase

                                _state.update {
                                    it.copy(
                                        txBaseFrequencyHz =
                                            newBase
                                    )
                                }

                                Log.i(
                                    tag,
                                    "Split TX tuning done → base=$newBase"
                                )
                            }

                        } else if (
                            tuningRadio == "rx"
                        ) {

                            val rxNominalNow =
                                (
                                    readFreq.toDouble() *
                                        SPEED_OF_LIGHT /
                                        (SPEED_OF_LIGHT - v)
                                    ).toLong()

                            val newTxBase =
                                TransponderMapper
                                    .mapDownlinkToUplink(
                                        rxNominalNow,
                                        xpdr
                                    )

                            if (
                                newTxBase != null &&
                                newTxBase > 0
                            ) {

                                txBaseFreq =
                                    newTxBase

                                _state.update {
                                    it.copy(
                                        txBaseFrequencyHz =
                                            newTxBase
                                    )
                                }

                                Log.i(
                                    tag,
                                    "Split RX tuning done → txBase=$newTxBase"
                                )
                            }
                        }

                        tuningRadio = ""
                        stableCount = 0
                        lastSetTxFreq = 0.0
                        lastSetRxFreq = 0.0
                    }
                }

            } else {

                if (
                    txBaseFreq != null &&
                    lastSetTxFreq > 0.0
                ) {

                    val readTx =
                        radio.readTxVfoFrequency()

                    if (
                        readTx != null &&
                        kotlin.math.abs(
                            readTx -
                                lastSetTxFreq
                        ) >= 20.0
                    ) {

                        tuningRadio = "tx"
                        lastReadFreq = readTx
                        stableCount = 0

                        Log.i(
                            tag,
                            "Split TX tuning detected (read=$readTx, lastSet=$lastSetTxFreq)"
                        )
                    }
                }

                if (
                    tuningRadio.isEmpty() &&
                    lastSetRxFreq > 0.0
                ) {

                    val readRx =
                        radio.readWorkingFrequency()

                    if (
                        readRx != null &&
                        kotlin.math.abs(
                            readRx -
                                lastSetRxFreq
                        ) >= 20.0
                    ) {

                        tuningRadio = "rx"
                        lastReadFreq = readRx
                        stableCount = 0

                        Log.i(
                            tag,
                            "Split RX tuning detected (read=$readRx, lastSet=$lastSetRxFreq)"
                        )
                    }
                }
            }

            val txRadioFreq =
                txBaseFreq?.let {
                    pos.getUplinkFreq(it)
                }

            val rxBaseCalc =
                if (txBaseFreq != null) {

                    TransponderMapper
                        .mapUplinkToDownlink(
                            txBaseFreq,
                            xpdr
                        )

                } else {

                    xpdr.downlinkLow
                }

            val rxRadioFreq =
                rxBaseCalc?.let {
                    pos.getDownlinkFreq(it)
                }

            if (
                radio.isConnected &&
                tuningRadio.isEmpty()
            ) {

                if (rxRadioFreq != null) {

                    radio.setWorkingFrequency(
                        rxRadioFreq
                    )

                    lastSetRxFreq =
                        rxRadioFreq.toDouble()
                }

                if (txRadioFreq != null) {

                    radio.setTxVfoFrequency(
                        txRadioFreq
                    )

                    lastSetTxFreq =
                        txRadioFreq.toDouble()
                }
            }

            _state.update {
                it.copy(
                    txConnected =
                        radio.isConnected,

                    rxConnected =
                        false,

                    txFrequencyHz =
                        txRadioFreq,

                    rxFrequencyHz =
                        rxRadioFreq,

                    azimuth =
                        Math.toDegrees(
                            pos.azimuth
                        ),

                    elevation =
                        Math.toDegrees(
                            pos.elevation
                        ),

                    distance =
                        pos.distance
                )
            }

            delay(1000)
        }
    }

    // ── Other IRadioTrackingService methods ─────────────────────────────────

    override fun stopTracking() {

        trackingJob?.cancel()
        trackingJob = null

        _state.update {
            it.copy(
                isActive = false
            )
        }
    }

    override fun setTransponder(
        transponder: SatRadio
    ) {

        appScope.launch {

            val tx =
                txController

            val rx =
                rxController

            transponder.uplinkMode
                ?.let {
                    tx?.setMode(it)
                }

            val rxMode =
                transponder.downlinkMode
                    ?: transponder.uplinkMode?.let {

                        TransponderMapper
                            .mapUplinkModeToDownlinkMode(
                                it,
                                transponder.isInverted
                            )
                    }

            rxMode?.let {
                rx?.setMode(it)
            }

            if (
                transponder.uplinkMode
                    ?.uppercase() == "FM"
            ) {

                _state.value
                    .ctcssTone
                    ?.let { tone ->

                        tx?.setCtcssTone(
                            tone
                        )

                        tx?.setCtcssMode(
                            true
                        )
                    }
            }
        }

        val txCenter =
            when {

                transponder.uplinkLow != null &&
                    transponder.uplinkHigh != null ->

                    (
                        transponder.uplinkLow!! +
                            transponder.uplinkHigh!!
                        ) / 2

                transponder.uplinkLow != null ->
                    transponder.uplinkLow!!

                else ->
                    null
            }

        val rxNominal =
            if (txCenter != null) {

                TransponderMapper
                    .mapUplinkToDownlink(
                        txCenter,
                        transponder
                    )

            } else {

                transponder.downlinkLow
            }

        _state.update {
            it.copy(
                selectedTransponder =
                    transponder,

                txBaseFrequencyHz =
                    txCenter,

                txFrequencyHz =
                    txCenter,

                rxFrequencyHz =
                    rxNominal,

                txMode =
                    transponder.uplinkMode,

                rxMode =
                    transponder.downlinkMode
                        ?: transponder.uplinkMode?.let { m ->

                            TransponderMapper
                                .mapUplinkModeToDownlinkMode(
                                    m,
                                    transponder.isInverted
                                )
                        }
            )
        }
    }

    override fun setTxBaseFrequency(
        frequencyHz: Long
    ) {

        _state.update {
            it.copy(
                txBaseFrequencyHz =
                    frequencyHz
            )
        }
    }

    override fun adjustTxBaseFrequency(
        deltaHz: Long
    ) {

        val current =
            _state.value
                .txBaseFrequencyHz
                ?: return

        _state.update {
            it.copy(
                txBaseFrequencyHz =
                    current + deltaHz
            )
        }
    }

    override fun setCtcssTone(
        toneHz: Double?
    ) {

        _state.update {
            it.copy(
                ctcssTone =
                    toneHz
            )
        }

        appScope.launch {

            val tx =
                txController

            if (toneHz != null) {

                tx?.setCtcssTone(
                    toneHz
                )

                tx?.setCtcssMode(
                    true
                )

            } else {

                tx?.setCtcssMode(
                    false
                )
            }
        }
    }

    override fun setMode(
        txMode: String,
        rxMode: String
    ) {

        appScope.launch {

            txController
                ?.setMode(
                    txMode
                )

            rxController
                ?.setMode(
                    rxMode
                )
        }

        _state.update {
            it.copy(
                txMode = txMode,
                rxMode = rxMode
            )
        }
    }
}
