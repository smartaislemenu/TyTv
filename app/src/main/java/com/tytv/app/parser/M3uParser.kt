package com.tytv.app.parser

import com.tytv.app.model.Channel

/**
 * Whole-text M3U playlist parser (pure Kotlin, no Android dependency).
 * Delegates to [M3uLineParser] so it parses exactly like the streaming
 * loader; kept for tests and small playlists.
 */
object M3uParser {

    fun parse(content: String, providerName: String): List<Channel> {
        val parser = M3uLineParser(providerName)
        for (line in content.lineSequence()) parser.accept(line)
        return parser.channels
    }
}
