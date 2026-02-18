package com.github.digitallyrefined.androidipcamera.helpers

import org.json.JSONException
import org.json.JSONObject

object TinySACommandParser {
    fun parseAction(commandBody: String): String? {
        return try {
            JSONObject(commandBody).optString("action").takeIf { it.isNotBlank() }
        } catch (_: JSONException) {
            null
        }
    }

    fun parseSequence(commandBody: String): List<TinySAHelper.ScanConfig> {
        return try {
            val root = JSONObject(commandBody)
            val sequenceArray = root.optJSONArray("sequence") ?: return emptyList()
            val configs = mutableListOf<TinySAHelper.ScanConfig>()
            for (index in 0 until sequenceArray.length()) {
                val item = sequenceArray.optJSONObject(index) ?: continue
                val start = item.optLong("start", -1L)
                val stop = item.optLong("stop", -1L)
                if (start < 0 || stop < 0) {
                    continue
                }

                val points = item.optInt("points", 290)
                val sweeps = item.optInt("sweeps", 5)
                val label = item.optString("label", "")
                configs.add(TinySAHelper.ScanConfig(start, stop, points, sweeps, label))
            }
            configs
        } catch (_: JSONException) {
            emptyList()
        }
    }
}
