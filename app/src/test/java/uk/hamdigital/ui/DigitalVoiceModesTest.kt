package uk.hamdigital.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DigitalVoiceModesTest {
    @Test fun onlyDvUsesStandardCivModeSwitch() {
        assertTrue(DigitalVoiceModes.appCanSelect("DV"))
        assertFalse(DigitalVoiceModes.appCanSelect("DMR*"))
        assertFalse(DigitalVoiceModes.appCanSelect("C4FM*"))
        assertFalse(DigitalVoiceModes.appCanSelect("P25*"))
    }
}
