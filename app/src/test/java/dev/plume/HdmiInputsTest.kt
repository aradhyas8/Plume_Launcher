package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HdmiInputsTest {
    @Test fun icons() {
        assertEquals("playstation", hdmiIcon("PlayStation 4", true))
        assertEquals("xbox", hdmiIcon("Xbox Series X", true))
        assertEquals("nintendoswitch", hdmiIcon("NintendoSwitch", true))
        assertEquals("appletv", hdmiIcon("Apple TV", true))
        assertEquals("nvidia", hdmiIcon("SHIELD", true))
        assertEquals("chromecast", hdmiIcon("Chromecast", true))
        assertEquals("chromecast", hdmiIcon("Google TV Streamer", true))
        assertEquals("settop", hdmiIcon("Sky Q", true))
        assertEquals("playback", hdmiIcon("BD Player", true))
        assertEquals("hdmi", hdmiIcon("HDMI 2", false))
        assertEquals("xbox", hdmiIcon("xbox", false)) // a port renamed in TV settings
        assertEquals("settop", hdmiIcon("Cable box", false))
        assertTrue(isAudioDevice("Sonos Arc"))
        assertFalse(isAudioDevice("PlayStation 5"))
    }

    @Test fun fireOsPortIsTheHardwareNumber() {
        assertEquals(2, Remap.hardwareNumber("com.mediatek.tvinput/.hdmi.HDMIInputService/HW2"))
        assertEquals(10, Remap.hardwareNumber("com.mediatek.tvinput/.hdmi.HDMIInputService/HW10"))
        assertEquals(null, Remap.hardwareNumber("com.example.tuner/.TunerService"))
        assertEquals(null, Remap.hardwareNumber("com.example/.Service/HW2/HDMI2050")) // a CEC device's id, not its port's
    }

    @Test fun row() {
        assertTrue(inRow("com.app", emptySet()))
        assertFalse(inRow("com.app", setOf("com.app")))
    }

    @Test fun theUpdateKeepsTheInputsThatWereInTheRow() {
        val inputs = listOf("hdmi:1" to false, "hdmi:2" to true, "hdmi:3" to true, "hdmi:4" to false)
        // 1 has a signal; 2 has none; 3 has none but was pinned; 4 has a signal but was taken out of the row
        assertEquals(setOf("hdmi:1", "hdmi:3"), pinsAfterSourcesUpdate(inputs, setOf("hdmi:4"), setOf("hdmi:3")))
    }
}
