package com.github.digitallyrefined.androidipcamera.helpers

/**
 * Definitive wiring of the ADAS3 4-mic acoustic array as soldered to the ESP32.
 *
 * Single 3V3 rail feeds Mic1..Mic4 in parallel, common GND shared with the
 * PC817 optocouplers of the YT2000 remote control. Channel selection is done
 * locally with the SEL pin (no GPIO):
 *   - Mic1 SEL -> GND  : LEFT  channel, pair A
 *   - Mic2 SEL -> 3V3  : RIGHT channel, pair A
 *   - Mic3 SEL -> GND  : LEFT  channel, pair B
 *   - Mic4 SEL -> 3V3  : RIGHT channel, pair B
 *
 * Two independent I2S buses (one per pair) so the ESP32 can sample both pairs
 * concurrently; beamforming / GCC-PHAT is done locally on the ESP32 and the
 * Android client just relays the JSONL results.
 *
 * The PC817 remote control inputs are kept here so the same status payload
 * documents the complete wiring exposed to the server.
 */
object MicArrayWiring {

    const val MIC_COUNT = 4
    const val PAIR_COUNT = 2
    const val POWER_RAIL = "3V3"
    const val GROUND = "GND"

    data class MicSpec(
        val index: Int,           // 1..4
        val pair: String,         // "A" or "B"
        val channel: String,      // "LEFT" or "RIGHT"
        val selTo: String         // "GND" or "3V3"
    )

    data class I2sPairSpec(
        val pair: String,         // "A" or "B"
        val bclkGpio: Int,
        val lrclGpio: Int,
        val doutGpio: Int,
        val leftMic: Int,         // mic index providing the LEFT slot
        val rightMic: Int         // mic index providing the RIGHT slot
    )

    data class RemoteControlSpec(
        val up: Int,
        val down: Int,
        val left: Int,
        val right: Int,
        val optocoupler: String = "PC817",
        val remote: String = "YT2000"
    )

    val MICS: List<MicSpec> = listOf(
        MicSpec(index = 1, pair = "A", channel = "LEFT",  selTo = "GND"),
        MicSpec(index = 2, pair = "A", channel = "RIGHT", selTo = "3V3"),
        MicSpec(index = 3, pair = "B", channel = "LEFT",  selTo = "GND"),
        MicSpec(index = 4, pair = "B", channel = "RIGHT", selTo = "3V3")
    )

    val PAIR_A = I2sPairSpec(
        pair = "A",
        bclkGpio = 14,
        lrclGpio = 13,
        doutGpio = 34,
        leftMic = 1,
        rightMic = 2
    )

    val PAIR_B = I2sPairSpec(
        pair = "B",
        bclkGpio = 22,
        lrclGpio = 21,
        doutGpio = 35,
        leftMic = 3,
        rightMic = 4
    )

    val I2S_PAIRS: List<I2sPairSpec> = listOf(PAIR_A, PAIR_B)

    val REMOTE_CONTROL = RemoteControlSpec(
        up = 26,
        down = 27,
        left = 32,
        right = 33
    )

    /**
     * Wiring as a JSON string (no trailing newline). Used to enrich payloads
     * forwarded to the server when the firmware does not include wiring info,
     * and to power the /adas3/mic-array/status endpoint.
     */
    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"mic_count\":").append(MIC_COUNT)
        sb.append(",\"pair_count\":").append(PAIR_COUNT)
        sb.append(",\"power\":\"").append(POWER_RAIL).append("\"")
        sb.append(",\"ground\":\"").append(GROUND).append("\"")
        sb.append(",\"mics\":[")
        MICS.forEachIndexed { i, m ->
            if (i > 0) sb.append(",")
            sb.append("{\"index\":").append(m.index)
            sb.append(",\"pair\":\"").append(m.pair).append("\"")
            sb.append(",\"channel\":\"").append(m.channel).append("\"")
            sb.append(",\"sel_to\":\"").append(m.selTo).append("\"}")
        }
        sb.append("],\"i2s\":[")
        I2S_PAIRS.forEachIndexed { i, p ->
            if (i > 0) sb.append(",")
            sb.append("{\"pair\":\"").append(p.pair).append("\"")
            sb.append(",\"bclk_gpio\":").append(p.bclkGpio)
            sb.append(",\"lrcl_gpio\":").append(p.lrclGpio)
            sb.append(",\"dout_gpio\":").append(p.doutGpio)
            sb.append(",\"left_mic\":").append(p.leftMic)
            sb.append(",\"right_mic\":").append(p.rightMic).append("}")
        }
        sb.append("],\"remote_control\":{")
        sb.append("\"remote\":\"").append(REMOTE_CONTROL.remote).append("\"")
        sb.append(",\"optocoupler\":\"").append(REMOTE_CONTROL.optocoupler).append("\"")
        sb.append(",\"up_gpio\":").append(REMOTE_CONTROL.up)
        sb.append(",\"down_gpio\":").append(REMOTE_CONTROL.down)
        sb.append(",\"left_gpio\":").append(REMOTE_CONTROL.left)
        sb.append(",\"right_gpio\":").append(REMOTE_CONTROL.right)
        sb.append("}}")
        return sb.toString()
    }
}
