package com.rtbishop.look4sat.core.data.framework

import java.util.Locale

object Ftx1CatProtocol {

    private fun ascii(command: String): ByteArray =
        command.toByteArray(Charsets.US_ASCII)

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
        return when (mode.uppercase()) {
            "LSB" -> "1"
            "USB" -> "2"
            "CW", "CW-U", "CWU" -> "3"
            "FM" -> "4"
            "AM" -> "5"
            "RTTY-L", "RTTY" -> "6"
            "CW-L", "CWL" -> "7"
            "DATA-L" -> "8"
            "RTTY-U" -> "9"
            "DATA-FM" -> "A"
            "FM-N", "NFM" -> "B"
            "DATA-U" -> "C"
            "AM-N" -> "D"
            "PSK" -> "E"
            "DATA-FM-N" -> "F"
            "C4FM-DN" -> "H"
            "C4FM-VW" -> "I"
            else -> null
        }
    }

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
}
