package dev.plume

import android.content.SharedPreferences
import android.content.Intent
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RemapTest {
    /** Only what Remap uses: one string in, one string out. */
    private class FakePrefs : SharedPreferences {
        val strings = HashMap<String, String>()
        override fun getString(key: String?, defValue: String?) = strings[key] ?: defValue
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor = also { strings[key!!] = value!! }
            override fun apply() {}
            override fun commit() = true
            override fun putStringSet(key: String?, values: MutableSet<String>?) = throw UnsupportedOperationException()
            override fun putInt(key: String?, value: Int) = throw UnsupportedOperationException()
            override fun putLong(key: String?, value: Long) = throw UnsupportedOperationException()
            override fun putFloat(key: String?, value: Float) = throw UnsupportedOperationException()
            override fun putBoolean(key: String?, value: Boolean) = throw UnsupportedOperationException()
            override fun remove(key: String?) = throw UnsupportedOperationException()
            override fun clear() = throw UnsupportedOperationException()
        }
        override fun getAll() = throw UnsupportedOperationException()
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = throw UnsupportedOperationException()
        override fun getInt(key: String?, defValue: Int) = throw UnsupportedOperationException()
        override fun getLong(key: String?, defValue: Long) = throw UnsupportedOperationException()
        override fun getFloat(key: String?, defValue: Float) = throw UnsupportedOperationException()
        override fun getBoolean(key: String?, defValue: Boolean) = throw UnsupportedOperationException()
        override fun contains(key: String?) = throw UnsupportedOperationException()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    @Before fun fresh() = Remap.reset() // Remap keeps its keys in memory between calls

    @Test fun allAppsActionSurvivesSavingAndReloading() {
        val p = FakePrefs()
        assertFalse(Remap.kept(KeyEvent.KEYCODE_ALL_APPS, 580))
        Remap.put(p, "k284s580", "open:allapps", "")
        Remap.reset()
        assertEquals("open:allapps", Remap.lookup(Remap.all(p), KeyEvent.KEYCODE_ALL_APPS, 580)!!.short)
        assertEquals(Intent.ACTION_MAIN to R.string.act_all_apps, Remap.OPEN["allapps"])
    }

    @Test fun putThenAllGivesBackTheSameEntries() {
        val p = FakePrefs()
        Remap.put(p, "k183", "app:com.netflix.ninja", "sys:recents")
        Remap.put(p, "s4001", "media:playpause", "")
        fun check(read: Map<String, Remap.Entry>) {
            assertEquals(listOf("k183", "s4001"), read.keys.toList())
            assertEquals("app:com.netflix.ninja", read["k183"]!!.short)
            assertEquals("sys:recents", read["k183"]!!.long)
            assertEquals("media:playpause", read["s4001"]!!.short)
            assertEquals("", read["s4001"]!!.long)
        }
        check(Remap.all(p)) // from memory
        Remap.reset()
        check(Remap.all(p)) // from the saved string
    }

    @Test fun aKeyWithNoPressAndNoHoldIsForgotten() {
        val p = FakePrefs()
        Remap.put(p, "k183", "app:com.netflix.ninja", "")
        Remap.put(p, "k184", "none", "")
        Remap.put(p, "k183", "", "")
        assertEquals(listOf("k184"), Remap.all(p).keys.toList())
        Remap.reset()
        assertEquals(listOf("k184"), Remap.all(p).keys.toList()) // gone from the saved string too
    }

    @Test fun idUsesBothCodesWhenAvailable() {
        assertEquals("k183s398", Remap.id(KeyEvent.KEYCODE_PROG_RED, 398))
        assertEquals("k183", Remap.id(KeyEvent.KEYCODE_PROG_RED, 0))
        assertEquals("s398", Remap.id(KeyEvent.KEYCODE_UNKNOWN, 398))
    }

    @Test fun lookupDistinguishesButtonsAndKeepsOldMappings() {
        val p = FakePrefs()
        Remap.put(p, "k249s472", "app:rakuten", "")
        Remap.reset()
        val map = Remap.all(p)
        assertEquals("app:rakuten", Remap.lookup(map, 249, 472)?.short)
        assertEquals(null, Remap.lookup(map, 249, 186))
        Remap.put(p, "k249", "app:old", "")
        assertEquals("app:rakuten", Remap.lookup(map, 249, 472)?.short)
        assertEquals("app:old", Remap.lookup(map, 249, 186)?.short)
        assertEquals("app:old", Remap.lookup(map, 249, 0)?.short)
    }

    @Test fun keptRefusesKeysThatMustNotChange() {
        assertTrue(Remap.kept(KeyEvent.KEYCODE_BACK, 158))
        assertTrue(Remap.kept(KeyEvent.KEYCODE_POWER, 116))
        assertTrue(Remap.kept(KeyEvent.KEYCODE_VOLUME_UP, 115))
        assertTrue(Remap.kept(KeyEvent.KEYCODE_VOLUME_DOWN, 114))
        assertTrue(Remap.kept(KeyEvent.KEYCODE_VOLUME_MUTE, 113))
        assertTrue(Remap.kept(KeyEvent.KEYCODE_UNKNOWN, 0)) // no code at all: every such key would share one identity
        assertFalse(Remap.kept(KeyEvent.KEYCODE_UNKNOWN, 398)) // a scan code is enough to tell it apart
        assertFalse(Remap.kept(KeyEvent.KEYCODE_PROG_RED, 398))
    }
}
