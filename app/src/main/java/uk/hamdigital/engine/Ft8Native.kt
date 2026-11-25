// JNI bridge to ft8_lib (MIT) for FT8, FT4 and FT2 (cpp/ft8_jni.c). [mode]: FtMode.code (0 FT8, 1 FT4, 2 FT2).
package uk.hamdigital.engine

object Ft8Native {
    init { System.loadLibrary("hamdigital") }         // the decoders library

    /** Decode one slot of 12 kHz audio from its start: "snr\tdt\tfreq\ttext" per message. */
    @JvmStatic external fun decode(samples: ShortArray, n: Int, mode: Int): Array<String>

    /** Transmit audio for a message: 12 kHz, base tone [f0] Hz, peak [amplitude] 0..1; null if it cannot be sent. */
    @JvmStatic external fun encode(text: String, mode: Int, f0: Float, amplitude: Float): ShortArray?
}
