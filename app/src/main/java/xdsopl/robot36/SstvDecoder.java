// HF Digital Modes (GPL v3): the app's way in to Robot36's SSTV decoder. Robot36's own files in this folder are
// copied unchanged (0BSD, LICENSE here); this one is not part of Robot36. It sits in Robot36's package because the
// Decoder's constructor and current mode are package-private, and it holds the two pixel buffers as Robot36's
// MainActivity does: the "scope" - every scan line as it is decoded, a picture or not - and the picture being received
// (sized by the mode once its VIS code is heard).
package xdsopl.robot36;

public class SstvDecoder {
    /** Every scan line decoded, as Robot36's scope: a ring 1280 lines deep, kept twice over so a window never wraps. */
    public final PixelBuffer scope = new PixelBuffer(640, 2 * 1280);
    /** The picture being received: its width and height are the mode's; line is how far it has got (-1 none, height = done). */
    public final PixelBuffer image = new PixelBuffer(800, 616);
    private final Decoder decoder;                    // Robot36's decoder

    /** A decoder for audio at [sampleRate] Hz (any rate: Robot36 sizes its filters by it). */
    public SstvDecoder(int sampleRate) {
        decoder = new Decoder(scope, image, "Raw", sampleRate);
        decoder.currentMode = new RawDecoder("Raw", sampleRate); // start in "Raw" (Robot36 starts in Robot 36): until the line timing or a VIS code names a mode, modeName says none has been found
    }

    /** One block of mono audio, -1..1 (the decoder works in place on it). True if new lines were decoded. */
    public boolean process(float[] samples) { return decoder.process(samples, 0); }

    /** The mode heard (or expected) now, e.g. "Robot 36 Color", "Martin 1". */
    public String modeName() { return decoder.currentMode.getName(); }

    /** The picture in progress (or just finished) was found from its line timing, not started by its VIS code. */
    public boolean foundByTiming() { return decoder.startedByTiming; }

    /** The share (0..1) of that picture's scan lines placed by their own sync pulse (the rest were carried on from the
     *  line timing - a picture caught only at its end is mostly those, and noise). */
    public float syncedShare() { return decoder.picLines > 0 ? Math.min(1f, (float) decoder.syncedLines / decoder.picLines) : 0f; }

    /** Decode only [name] (a mode's name, as modeName gives), or null to follow the VIS code (automatic). */
    public void setMode(String name) { decoder.setMode(name); }
}
