package com.tytv.app

import com.tytv.app.update.VersionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    private val validJson = """
        {
          "versionCode": 2,
          "versionName": "0.2.0",
          "apkUrl": "https://github.com/smartaislemenu/TyTv/releases/latest/download/tytv.apk",
          "notes": "Faster guide loading."
        }
    """.trimIndent()

    @Test
    fun newerRemoteCodeIsAnUpdate() {
        assertTrue(VersionInfo.isUpdateAvailable(2, 1))
        assertFalse(VersionInfo.isUpdateAvailable(1, 1))
        assertFalse(VersionInfo.isUpdateAvailable(1, 2))
    }

    @Test
    fun parsesValidDescriptor() {
        val info = VersionInfo.parseVersionJson(validJson)
        assertEquals(2, info?.versionCode)
        assertEquals("0.2.0", info?.versionName)
        assertEquals(
            "https://github.com/smartaislemenu/TyTv/releases/latest/download/tytv.apk",
            info?.apkUrl
        )
        assertEquals("Faster guide loading.", info?.notes)
    }

    @Test
    fun notesMayBeMissing() {
        val json = """{"versionCode": 3, "versionName": "0.3.0", "apkUrl": "https://example.com/t.apk"}"""
        assertEquals("", VersionInfo.parseVersionJson(json)?.notes)
    }

    @Test
    fun missingRequiredFieldsParseToNull() {
        assertNull(VersionInfo.parseVersionJson("""{"versionName": "0.2.0", "apkUrl": "https://example.com/t.apk"}"""))
        assertNull(VersionInfo.parseVersionJson("""{"versionCode": 2, "apkUrl": "https://example.com/t.apk"}"""))
        assertNull(VersionInfo.parseVersionJson("""{"versionCode": 2, "versionName": "0.2.0"}"""))
    }

    @Test
    fun malformedAndNonHttpDocumentsParseToNull() {
        assertNull(VersionInfo.parseVersionJson("not json at all"))
        assertNull(VersionInfo.parseVersionJson(""))
        assertNull(
            VersionInfo.parseVersionJson(
                """{"versionCode": "two", "versionName": "0.2.0", "apkUrl": "https://example.com/t.apk"}"""
            )
        )
        assertNull(
            VersionInfo.parseVersionJson(
                """{"versionCode": 2, "versionName": "0.2.0", "apkUrl": "ftp://example.com/t.apk"}"""
            )
        )
    }

    @Test
    fun escapedCharactersInNotesAreUnescaped() {
        val json = """{"versionCode": 2, "versionName": "0.2.0", "apkUrl": "https://example.com/t.apk", "notes": "Line one\nLine \"two\""}"""
        assertEquals("Line one\nLine \"two\"", VersionInfo.parseVersionJson(json)?.notes)
    }
}
