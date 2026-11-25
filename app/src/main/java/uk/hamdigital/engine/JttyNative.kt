// JNI bridge to JTTY (cpp/jtty_jni.c; cpp/jtty is a C port of WSJT-X Improved 3.2.1's lib/jtty, GPL v3).
package uk.hamdigital.engine

object JttyNative {
    init { System.loadLibrary("hamdigital") }         // the decoders library

    /** Exchange profiles for packing "599 ..." text: unknown (generic), Field Day, RTTY Roundup (serials, states). */
    const val UNKNOWN = 0; const val FIELD_DAY = 1; const val RTTY = 2

    /** Start receiving afresh (forget the audio and the messages in progress). */
    @JvmStatic external fun reset()

    /** 12 kHz audio in; decodes each whole quarter frame (0.472 s). [f0] / [ftol]: receive frequency and search width
     *  (Hz); [nfa] / [nfb]: the band searched beside it. */
    @JvmStatic external fun feed(samples: ShortArray, n: Int, f0: Float, ftol: Float, nfa: Int, nfb: Int)

    /** Messages started, grown or completed since the last call: "id\tfreq\tstart\tcomplete\tsnr\ttext" (start: seconds
     *  from the reset; complete 0 / 1). */
    @JvmStatic external fun updates(): Array<String>

    /** How [text] goes out: "frames\tnormalized text" (frames 0: nothing, -1: cannot be sent). */
    @JvmStatic external fun pack(text: String, profile: Int): String

    /** The transmit audio for [text]: 12 kHz, lowest tone [f0] Hz, peak [amplitude] 0..1; null if it cannot be sent. */
    @JvmStatic external fun encode(text: String, profile: Int, f0: Float, amplitude: Float): ShortArray?
}
