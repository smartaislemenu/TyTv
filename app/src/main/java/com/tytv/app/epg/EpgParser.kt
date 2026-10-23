package com.tytv.app.epg

import android.util.Xml
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.xmlpull.v1.XmlPullParser

/**
 * Streaming XMLTV parser: reads programme by programme and never
 * holds the whole guide file in memory, so multi-MB guides are fine.
 * Times look like "20261023200000 +0000" (offset optional); a missing
 * offset is read as UTC.
 */
object EpgParser {

    private val withOffset = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    private val withoutOffset = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun parse(input: InputStream): EpgData {
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        val channelNames = mutableMapOf<String, String>()
        val programmes = mutableMapOf<String, MutableList<EpgProgramme>>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> {
                        val id = parser.getAttributeValue(null, "id") ?: ""
                        val displayName = readDisplayName(parser)
                        if (id.isNotEmpty() && displayName.isNotEmpty()) {
                            channelNames[id] = displayName
                        }
                    }
                    "programme" -> {
                        val channelId = parser.getAttributeValue(null, "channel") ?: ""
                        val start = parseTime(parser.getAttributeValue(null, "start"))
                        val stop = parseTime(parser.getAttributeValue(null, "stop"))
                        var title = ""
                        var description = ""
                        var inner = parser.next()
                        while (!(inner == XmlPullParser.END_TAG && parser.name == "programme")) {
                            if (inner == XmlPullParser.START_TAG) {
                                when (parser.name) {
                                    "title" -> title = parser.nextText()
                                    "desc" -> description = parser.nextText()
                                }
                            }
                            inner = parser.next()
                        }
                        if (channelId.isNotEmpty() && start > 0 && stop > start) {
                            programmes.getOrPut(channelId) { mutableListOf() } += EpgProgramme(
                                startMs = start,
                                endMs = stop,
                                title = title.ifEmpty { "No title" },
                                description = description
                            )
                        }
                    }
                }
            }
            event = parser.next()
        }
        return EpgData(
            channelNames = channelNames,
            programmes = programmes.mapValues { it.value.sortedBy { programme -> programme.startMs } }
        )
    }

    /** Reads the <display-name> inside the current <channel> element. */
    private fun readDisplayName(parser: XmlPullParser): String {
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "channel")) {
            if (event == XmlPullParser.START_TAG && parser.name == "display-name") {
                return parser.nextText()
            }
            event = parser.next()
        }
        return ""
    }

    private fun parseTime(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0
        val text = raw.trim()
        return try {
            withOffset.parse(text)?.time ?: 0
        } catch (e: Exception) {
            try {
                withoutOffset.parse(text.substringBefore(" "))?.time ?: 0
            } catch (e2: Exception) {
                0
            }
        }
    }
}
