package com.rtbishop.look4sat.core.data.framework

import java.util.Locale
import kotlin.math.abs

object Ftx1CatProtocol {

    private fun ascii(command: String): ByteArray =
        command.toByteArray(Charsets.US_ASCII)

    // ── Frequency ───────────────────────────────────────────────────────────

    fun buildSetMainFrequencyCommand(frequencyHz: Long): ByteArray {
        return ascii(String.format(Locale.US, "FA%09d;", frequencyHz))
    }

    fun buildSetSubFrequencyCommand(frequencyHz: Long): ByteArray {
        return ascii(String.format(Locale.US, "FB%09d;", frequencyHz))
    }

    fun buildReadMainFrequencyCommand(): ByteArray =
        ascii("FA;")

    fun buildReadSubFrequencyCommand(): ByteArray =
        ascii("FB;")

    fun parseMainFrequency(response: String): Long? {
        val match = Regex("""FA(\d{9});""").find(response) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    fun parseSubFrequency(response: String): Long? {
        val match = Regex("""FB(\d{9});""").find(response) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    // ── Operating mode ──────────────────────────────────────────────────────

    fun buildSetMainModeCommand(mode: String): ByteArray? {
        val code = modeCode(mode) ?: return null
        return ascii("MD0$code;")
    }

    fun buildSetSubModeCommand(mode: String): ByteArray? {
        val code = modeCode(mode) ?: return null
        return ascii("MD1$code;")
    }

    fun buildReadMainModeCommand(): ByteArray =
        ascii("MD0;")

    fun buildReadSubModeCommand(): ByteArray =
        ascii("MD1;")

    private fun modeCode(mode: String): String? {
        return when (mode.trim().uppercase()) {
            "LSB" -> "1"
            "USB" -> "2"
            "CW", "CW-U", "CWU" -> "3"
            "FM" -> "4"
            "AM" -> "5"
            "RTTY", "RTTY-L", "RTTYL" -> "6"
            "CW-L", "CWL" -> "7"
            "DATA-L", "DATAL" -> "8"
            "RTTY-U", "RTTYU" -> "9"
            "DATA-FM", "DATAFM", "D-FM" -> "A"
            "FM-N", "FMN", "NFM" -> "B"
            "DATA-U", "DATAU" -> "C"
            "AM-N", "AMN" -> "D"
            "PSK" -> "E"
            "DATA-FM-N",
            "DATA-FMN",
            "DATAFMN",
            "D-FM-N",
            "D-FMN" -> "F"
            "C4FM-DN" -> "H"
            "C4FM-VW" -> "I"
            else -> null
        }
    }

    // ── MAIN / SUB TX-RX state ──────────────────────────────────────────────

    fun buildTxMainCommand(): ByteArray =
        ascii("FT0;")

    fun buildTxSubCommand(): ByteArray =
        ascii("FT1;")

    fun buildVfoMainTxRxCommand(): ByteArray =
        ascii("VS0;")

    fun buildVfoSubTxRxCommand(): ByteArray =
        ascii("VS1;")

    fun buildSplitModeCommand(enabled: Boolean): ByteArray =
        ascii(if (enabled) "ST1;" else "ST0;")

    // ── CTCSS ───────────────────────────────────────────────────────────────
    //
    // CN P1 P2 P3P3P3;
    //   P1 = 0 MAIN / 1 SUB
    //   P2 = 0 CTCSS / 1 DCS
    //   P3 = tone number 000..049
    //
    // CT P1 P2;
    //   P1 = 0 MAIN / 1 SUB
    //   P2 = 0 OFF / 1 encoder ON / 2 encoder+decoder ON
    //
    // For satellite uplink we use SUB-side CTCSS encoder only.

    private val ctcssTonesHz = listOf(
        67.0, 69.3, 71.9, 74.4, 77.0,
        79.7, 82.5, 85.4, 88.5, 91.5,
        94.8, 97.4, 100.0, 103.5, 107.2,
        110.9, 114.8, 118.8, 123.0, 127.3,
        131.8, 136.5, 141.3, 146.2, 151.4,
        156.7, 159.8, 162.2, 165.5, 167.9,
        171.3, 173.8, 177.3, 179.9, 183.5,
        186.2, 189.9, 192.8, 196.6, 199.5,
        203.5, 206.5, 210.7, 218.1, 225.7,
        229.1, 233.6, 241.8, 250.3, 254.1
    )

    private fun ctcssToneIndex(toneHz: Double): Int? {
        val index = ctcssTonesHz.indexOfFirst {
            abs(it - toneHz) < 0.05
        }
        return index.takeIf { it >= 0 }
    }

    fun buildSetSubCtcssToneCommand(toneHz: Double): ByteArray? {
        val index = ctcssToneIndex(toneHz) ?: return null

        return ascii(
            String.format(
                Locale.US,
                "CN10%03d;",
                index
            )
        )
    }

    fun buildSetSubCtcssModeCommand(enabled: Boolean): ByteArray =
        ascii(
            if (enabled) {
                "CT11;"
            } else {
                "CT10;"
            }
        )
}
