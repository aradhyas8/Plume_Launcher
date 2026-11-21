package dev.plume

import org.junit.Assert.*
import org.junit.Test

class IconPacksTest {
    @Test fun normalizesBothComponentFormsWithoutChangingThePackage() {
        assertEquals("tv.example/tv.example.Main", iconComponent("ComponentInfo{tv.example/.Main}"))
        assertEquals("tv.example/tv.other.Main", iconComponent("tv.example/tv.other.Main"))
        for (bad in listOf(null, "", "tv.example", "bad/.Main", "tv.example/", "tv.example/.Main/other", "tv.example/../Main",
            "ComponentInfo{tv.example/.Main", "tv.example/.Main}", "tv.example/tv..Main"))
            assertNull(iconComponent(bad))
    }

    @Test fun unknownPacksResolveExactActivitiesAndOnlyUnambiguousPackageFallbacks() {
        val index = IconPackIndex(setOf("tv.example", "tv.other"))
        index.add("ComponentInfo{tv.example/.Main}", "original")
        index.add("tv.example/tv.example.Main", "alternative")
        index.add("tv.example/.Other", "other")
        index.add("tv.other/.Phone", "shared")
        index.add("tv.other/.Phone2", "shared")
        assertEquals("original", index.resolve("tv.example/.Main"))
        assertNull(index.resolve("tv.example/.Unknown"))
        assertEquals("shared", index.resolve("tv.other/.Tv"))
        assertNull(index.resolve("tv.missing/.Main"))
    }

    @Test fun discardsUninstalledPackagesAndInvalidResourceNames() {
        val index = IconPackIndex(setOf("tv.example"))
        index.add("tv.uninstalled/.Main", "icon")
        index.add("tv.example/.Main", "../icon")
        index.add("tv.example/.Main", "@other:drawable/icon")
        assertNull(index.resolve("tv.uninstalled/.Main"))
        assertNull(index.resolve("tv.example/.Main"))
        index.add("tv.example/.Main", "@drawable/good_icon")
        assertEquals("good_icon", index.resolve("tv.example/.Main"))
    }

    @Test fun settingsTransferAcceptsAnUnknownPackAndRejectsInvalidPackageNames() {
        assertEquals("tv.unknown.pack", SettingsTransfer.portable(mapOf("icon_pack" to "tv.unknown.pack"))["icon_pack"])
        for (bad in listOf("", "../pack", "tv.example/Activity", "a".repeat(256)))
            assertThrows(IllegalArgumentException::class.java) { SettingsTransfer.validate(mapOf("icon_pack" to bad)) }
    }
}
