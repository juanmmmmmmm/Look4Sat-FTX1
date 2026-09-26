package com.rtbishop.look4sat.core.data.framework

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.rtbishop.look4sat.core.domain.repository.IRadioController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class Ftx1Controller(
    context: Context
) : IRadioController {

    private val tag = "FTX1"

    private val appContext = context.applicationContext

    private val usbManager =
        appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private val ioMutex = Mutex()

    private var connection: UsbDeviceConnection? = null
    private var port: UsbSerialPort? = null

    override var isConnected: Boolean = false
        private set

    companion object {

        private const val YAESU_USB_VID = 0x10C4
        private const val FTX1_USB_PID = 0xEA70

        private const val BAUD_RATE = 38400

        private const val WRITE_TIMEOUT_MS = 1000
        private const val READ_SLICE_TIMEOUT_MS = 200
        private const val READ_TOTAL_TIMEOUT_MS = 1500

        private const val ACTION_USB_PERMISSION =
            "com.rtbishop.look4sat.FTX1_USB_PERMISSION"
    }

    override suspend fun connect(): Boolean =
        withContext(Dispatchers.IO) {

            if (isConnected) {
                return@withContext true
            }

            try {

                val drivers =
                    UsbSerialProber
                        .getDefaultProber()
                        .findAllDrivers(usbManager)

                val driver =
                    drivers.firstOrNull {
                        it.device.vendorId == YAESU_USB_VID &&
                            it.device.productId == FTX1_USB_PID
                    }

                if (driver == null) {
                    Log.e(tag, "FTX-1 CP2105 not found")
                    return@withContext false
                }

                val device = driver.device

                Log.i(
                    tag,
                    "FTX-1 found VID=${device.vendorId} PID=${device.productId}"
                )

                if (!usbManager.hasPermission(device)) {

                    val permissionIntent =
                        PendingIntent.getBroadcast(
                            appContext,
                            0,
                            Intent(ACTION_USB_PERMISSION)
                                .setPackage(appContext.packageName),
                            PendingIntent.FLAG_UPDATE_CURRENT or
                                PendingIntent.FLAG_IMMUTABLE
                        )

                    usbManager.requestPermission(
                        device,
                        permissionIntent
                    )

                    Log.i(
                        tag,
                        "USB permission requested"
                    )

                    return@withContext false
                }

                val usbConnection =
                    usbManager.openDevice(device)
                        ?: run {

                            Log.e(
                                tag,
                                "Could not open USB device"
                            )

                            return@withContext false
                        }

                /*
                 * CP2105 dual UART.
                 * Port 0 is used for CAT-1.
                 */
                val catPort =
                    driver.ports.getOrNull(0)
                        ?: run {

                            usbConnection.close()

                            Log.e(
                                tag,
                                "CAT-1 port not found"
                            )

                            return@withContext false
                        }

                catPort.open(
                    usbConnection
                )

                catPort.setParameters(
                    BAUD_RATE,
                    8,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE
                )

                /*
                 * Safety:
                 * never use RTS or DTR for PTT.
                 */
                try {
                    catPort.dtr = false
                } catch (_: Exception) {
                }

                try {
                    catPort.rts = false
                } catch (_: Exception) {
                }

                connection = usbConnection
                port = catPort
                isConnected = true

                delay(150)

                /*
                 * Harmless CAT test:
                 * read MAIN frequency.
                 */
                val response =
                    query(
                        Ftx1CatProtocol
                            .buildReadMainFrequencyCommand(),
                        "FA"
                    )

                val frequency =
                    response?.let {
                        Ftx1CatProtocol
                            .parseMainFrequency(it)
                    }

                if (frequency == null) {

                    Log.e(
                        tag,
                        "FTX-1 CAT test failed"
                    )

                    closeInternal()

                    return@withContext false
                }

                Log.i(
                    tag,
                    "FTX-1 connected MAIN=$frequency Hz"
                )

                true

            } catch (e: Exception) {

                Log.e(
                    tag,
                    "Connection error: ${e.message}"
                )

                closeInternal()

                false
            }
        }

    override suspend fun disconnect() =
        withContext(Dispatchers.IO) {

            ioMutex.withLock {
                closeInternal()
            }
        }

    private fun closeInternal() {

        try {
            port?.close()
        } catch (_: Exception) {
        }

        try {
            connection?.close()
        } catch (_: Exception) {
        }

        port = null
        connection = null
        isConnected = false

        Log.i(
            tag,
            "Disconnected"
        )
    }

    /*
     * MAIN = RX / downlink
     */

    override suspend fun setFrequency(
        frequencyHz: Long
    ): Boolean =
        setMainFrequency(frequencyHz)

    suspend fun setMainFrequency(
        frequencyHz: Long
    ): Boolean =
        send(
            Ftx1CatProtocol
                .buildSetMainFrequencyCommand(
                    frequencyHz
                )
        )

    override suspend fun setWorkingFrequency(
        frequencyHz: Long
    ): Boolean =
        setMainFrequency(frequencyHz)

    override suspend fun readWorkingFrequency(): Long? {

        val response =
            query(
                Ftx1CatProtocol
                    .buildReadMainFrequencyCommand(),
                "FA"
            ) ?: return null

        return Ftx1CatProtocol
            .parseMainFrequency(response)
    }

    /*
     * SUB = TX / uplink
     */

    suspend fun setSubFrequency(
        frequencyHz: Long
    ): Boolean =
        send(
            Ftx1CatProtocol
                .buildSetSubFrequencyCommand(
                    frequencyHz
                )
        )

    override suspend fun setTxVfoFrequency(
        frequencyHz: Long
    ): Boolean =
        setSubFrequency(frequencyHz)

    override suspend fun readTxVfoFrequency(): Long? {

        val response =
            query(
                Ftx1CatProtocol
                    .buildReadSubFrequencyCommand(),
                "FB"
            ) ?: return null

        return Ftx1CatProtocol
            .parseSubFrequency(response)
    }

    /*
     * Modes
     */

    override suspend fun setMode(
        mode: String
    ): Boolean =
        setMainMode(mode)

    suspend fun setMainMode(
        mode: String
    ): Boolean {

        val command =
            Ftx1CatProtocol
                .buildSetMainModeCommand(mode)
                ?: return false

        return send(command)
    }

    suspend fun setSubMode(
        mode: String
    ): Boolean {

        val command =
            Ftx1CatProtocol
                .buildSetSubModeCommand(mode)
                ?: return false

        return send(command)
    }

    /*
     * TX side selection.
     * These commands do NOT press PTT.
     */

    suspend fun selectSubForTx(): Boolean =
        send(
            Ftx1CatProtocol
                .buildTxSubCommand()
        )

    suspend fun selectMainForTx(): Boolean =
        send(
            Ftx1CatProtocol
                .buildTxMainCommand()
        )

    override suspend fun setSplitMode(
        enabled: Boolean
    ): Boolean =
        send(
            Ftx1CatProtocol
                .buildSplitModeCommand(enabled)
        )

    override suspend fun readFrequencyAndMode():
        Pair<Long, String>? {

        val frequency =
            readWorkingFrequency()
                ?: return null

        return Pair(
            frequency,
            ""
        )
    }

    override suspend fun setCtcssMode(
        enabled: Boolean
    ): Boolean =
        false

    override suspend fun setCtcssTone(
        toneHz: Double
    ): Boolean =
        false

    /*
     * PTT deliberately disabled.
     *
     * PTT must only be activated
     * from the physical microphone.
     */

    override suspend fun pttOn(): Boolean {

        Log.w(
            tag,
            "PTT ignored - software PTT disabled"
        )

        return false
    }

    override suspend fun pttOff(): Boolean =
        false

    private suspend fun send(
        bytes: ByteArray
    ): Boolean =
        withContext(Dispatchers.IO) {

            ioMutex.withLock {

                val serialPort =
                    port
                        ?: return@withLock false

                if (!isConnected) {
                    return@withLock false
                }

                try {

                    serialPort.write(
                        bytes,
                        WRITE_TIMEOUT_MS
                    )

                    true

                } catch (e: Exception) {

                    Log.e(
                        tag,
                        "CAT write error: ${e.message}"
                    )

                    isConnected = false

                    false
                }
            }
        }

    private suspend fun query(
        command: ByteArray,
        expectedPrefix: String
    ): String? =
        withContext(Dispatchers.IO) {

            ioMutex.withLock {

                val serialPort =
                    port
                        ?: return@withLock null

                try {

                    serialPort.write(
                        command,
                        WRITE_TIMEOUT_MS
                    )

                    val buffer =
                        ByteArray(64)

                    val response =
                        StringBuilder()

                    val deadline =
                        SystemClock.elapsedRealtime() +
                            READ_TOTAL_TIMEOUT_MS

                    while (
                        SystemClock.elapsedRealtime() <
                        deadline
                    ) {

                        val count =
                            try {

                                serialPort.read(
                                    buffer,
                                    READ_SLICE_TIMEOUT_MS
                                )

                            } catch (_: Exception) {

                                0
                            }

                        if (count > 0) {

                            response.append(
                                String(
                                    buffer,
                                    0,
                                    count,
                                    Charsets.US_ASCII
                                )
                            )

                            val text =
                                response.toString()

                            val start =
                                text.indexOf(
                                    expectedPrefix
                                )

                            if (start >= 0) {

                                val end =
                                    text.indexOf(
                                        ';',
                                        start
                                    )

                                if (end >= 0) {

                                    val result =
                                        text.substring(
                                            start,
                                            end + 1
                                        )

                                    Log.d(
                                        tag,
                                        "CAT RX: $result"
                                    )

                                    return@withLock result
                                }
                            }
                        }
                    }

                    Log.w(
                        tag,
                        "CAT response timeout for $expectedPrefix"
                    )

                    null

                } catch (e: Exception) {

                    Log.e(
                        tag,
                        "CAT query error: ${e.message}"
                    )

                    null
                }
            }
        }
}
