package uk.hamdigital.ui

internal object DigitalVoiceModes {
    val options = listOf("DV", "DMR*", "C4FM*", "P25*")
    fun name(label: String): String = label.trimEnd('*')
    fun appCanSelect(label: String): Boolean = name(label) == "DV"
}
