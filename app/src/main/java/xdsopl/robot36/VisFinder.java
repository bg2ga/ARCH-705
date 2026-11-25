/*
HF Digital Modes (GPL v3, as the app) - not part of Robot36.

A second SSTV VIS (start code) search for Robot36's Decoder, for weak HF signals. Robot36 reads the header through its
FM demodulator, which follows the strongest thing in 1000-2800 Hz: once off-air noise is within a few dB of the tones
it reads garbage, worst at the low header tones (1100-1300 Hz, near the band edge), and the 10 ms break and start bit it
needs are lost. This search tracks the strongest single tone instead (a 30 ms FFT every 1 ms - narrowband, so far less
hurt by noise), and needs neither the break nor the start bit: a steady 1900 Hz leader, 8 code bits (1100 Hz = 1,
1300 Hz = 0, even parity - one unreadable bit is given back by the parity) and the 1200 Hz stop bit, all measured
relative to the leader so a mistuned radio (up to 200 Hz) is fine.
*/

package xdsopl.robot36;

import java.util.Arrays;

public class VisFinder {

	private final double msSamples;               // samples per millisecond (the track's step)
	private final int frame;                      // samples in one 30 ms analysis window
	private final int fftSize;                    // FFT length (power of 2, >= frame)
	private final float[] window;                 // Hann window over the frame
	private final float[] re, im;                 // FFT work arrays
	private final float[] raw;                    // ring: the latest raw audio samples
	private final int rawMask;                    // raw.length - 1 (a power of 2)
	private final int binLo, binHi;               // FFT bins searched for the strongest tone (900-2500 Hz)
	private final float[] track = new float[1024]; // ring: strongest tone (Hz) of the window centred on each millisecond
	private final float[] sorted = new float[256]; // scratch for medians
	private long samples;                         // raw samples received so far
	private long tracked;                         // milliseconds tracked so far (next one to work out)
	private long cand = -1;                       // best header so far: the millisecond its stop bit ends in (-1: none)
	private int candCode;                         // its code (7 bits)
	private float candOffset;                     // its mistuning (Hz)
	private float candScore;                      // its total error (Hz); lower is better
	private long holdUntil;                       // no header before this millisecond (after a picture start)

	/** Found header: its code (7 bits, parity removed). */
	public int code;
	/** Found header: the sample (counted from the first one pushed) at which its stop bit ends. */
	public long endSample;
	/** Found header: the radio's mistuning (Hz), from the leader. */
	public float offsetHz;

	public VisFinder(int sampleRate) {
		msSamples = sampleRate / 1000.0;          // samples per ms
		frame = (int) Math.round(0.03 * sampleRate); // 30 ms: one code bit
		int n = 1;                                // FFT size: the next power of 2
		while (n < frame)
			n <<= 1;
		fftSize = n;
		window = new float[frame];                // Hann
		for (int i = 0; i < frame; ++i)
			window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (frame - 1)));
		re = new float[fftSize];
		im = new float[fftSize];
		int r = 1;                                // raw ring: room for a frame and a block of input
		while (r < 4 * frame)
			r <<= 1;
		raw = new float[r];
		rawMask = r - 1;
		binLo = (int) Math.floor(900.0 * fftSize / sampleRate);  // lowest bin searched
		binHi = (int) Math.ceil(2500.0 * fftSize / sampleRate);  // highest
	}

	/** One raw audio sample. Returns true when a header has been found (see code, endSample, offsetHz); call
	 *  again with the next sample to go on searching. */
	public boolean push(float sample) {
		raw[(int) (samples & rawMask)] = sample;  // into the ring
		++samples;
		long centre = Math.round(tracked * msSamples); // the next millisecond's window centre
		if (samples < centre + frame - frame / 2) // its window is not complete yet
			return false;
		long ms = tracked++;                      // this millisecond
		track[(int) (ms % track.length)] = centre - frame / 2 < 0 ? 0 : strongest(centre - frame / 2); // its tone
		if (ms >= holdUntil)
			tryHeader(ms);                        // a stop bit ending here?
		if (cand < 0 || ms < cand + 50)
			return false;                         // nothing, or not settled yet (a better one may follow within 45 ms - a bit out of step reads nearly as well)
		code = candCode;                          // found
		endSample = Math.round((cand + 1) * msSamples);
		offsetHz = candOffset;
		cand = -1;                                // used
		return true;
	}

	/** After a picture start: no header for a second (the same one must not start it again). */
	public void hold() {
		holdUntil = tracked + 1000;               // a second on
		cand = -1;                                // and none held
	}

	// The strongest tone (Hz) in 900-2500 Hz of the frame starting at raw sample [start].
	private float strongest(long start) {
		for (int i = 0; i < fftSize; ++i) {       // windowed frame, zero padded
			re[i] = i < frame ? raw[(int) ((start + i) & rawMask)] * window[i] : 0;
			im[i] = 0;
		}
		fft(re, im);
		int best = binLo;                         // the strongest bin
		float bestP = -1;
		for (int k = binLo; k <= binHi; ++k) {
			float p = re[k] * re[k] + im[k] * im[k];
			if (p > bestP) {
				bestP = p;
				best = k;
			}
		}
		float a = power(best - 1), b = bestP, c = power(best + 1); // a parabola through it and its neighbours
		float d = a - 2 * b + c;
		float shift = d < 0 ? 0.5f * (a - c) / d : 0; // the peak's fraction of a bin
		return (float) ((best + shift) * msSamples * 1000 / fftSize); // in Hz
	}

	// Power of FFT bin [k].
	private float power(int k) {
		return re[k] * re[k] + im[k] * im[k];
	}

	// Median of the track's [count] milliseconds from [from] (count <= sorted.length).
	private float median(long from, int count) {
		for (int i = 0; i < count; ++i)
			sorted[i] = track[(int) ((from + i) % track.length)];
		Arrays.sort(sorted, 0, count);
		return sorted[count / 2];
	}

	// A VIS header whose stop bit ends with millisecond [n]? Keeps it if it is the best so far. Timing (ms): leader 300,
	// break 10, leader 300, start bit 30, 8 code bits of 30, stop bit 30. Each bit is read from the 7 windows centred on
	// its middle (each nearly all inside the bit).
	private void tryHeader(long n) {
		long b0 = n - 269;                        // the first code bit's first millisecond
		if (b0 - 300 < 0 || tracked - (b0 - 300) > track.length)
			return;                               // not enough history
		float stopHz = median(b0 + 240 + 12, 7);  // stop bit
		// leader: the 250 ms before the start bit, ending 50 ms early (the start bit may be smeared into it)
		int leaderMs = 250;
		float leaderHz = median(b0 - 300, leaderMs); // its frequency (sorts sorted[0..250))
		if (Math.abs(leaderHz - 1900) > 200)      // mistuned by more than 200 Hz: not a leader
			return;
		int steady = 0;                           // milliseconds within 40 Hz of it
		for (int i = 0; i < leaderMs; ++i)
			if (Math.abs(track[(int) ((b0 - 300 + i) % track.length)] - leaderHz) <= 40)
				++steady;
		if (steady < leaderMs / 3)                // at least a third of it steady (off-air noise breaks up the rest; noise alone gives ~5%)
			return;
		float off = leaderHz - 1900;              // mistuning: every tone is off by this
		float tol = 80;                           // Hz each tone may be off after that
		float score = Math.abs(stopHz - off - 1200); // stop bit 1200 Hz
		if (score > tol)
			return;
		int bits = 0;                             // the 8 bits, LSB first
		int lost = -1;                            // the one bit that is neither tone (-1: none)
		for (int j = 0; j < 8; ++j) {
			float f = median(b0 + 30L * j + 12, 7) - off; // the bit's tone, mistuning taken off
			float e1 = Math.abs(f - 1100), e0 = Math.abs(f - 1300); // distance from a one / a zero
			if (Math.min(e1, e0) > tol) {         // neither
				if (lost >= 0)
					return;                       // a second: not a header
				lost = j;                         // parity gives it back
				score += 2 * tol;                 // (a header read whole wins)
				continue;
			}
			if (e1 < e0)
				bits |= 1 << j;                   // a one
			score += Math.min(e1, e0);            // error
		}
		if (lost >= 0) {
			if (Integer.bitCount(bits) % 2 != 0)
				bits |= 1 << lost;                // whatever makes the parity even
		} else if (Integer.bitCount(bits) % 2 != 0)
			return;                               // even parity over all 8
		if (score > 250)
			return;                               // too far off overall (headers off air read 10-130; picture content 360)
		if (cand >= 0 && score >= candScore)
			return;                               // a better one is already held (within the last 50 ms)
		cand = n;                                 // best so far
		candCode = bits & 127;
		candOffset = off;
		candScore = score;
	}

	// In-place radix-2 complex FFT (length a power of 2).
	private static void fft(float[] xr, float[] xi) {
		int n = xr.length;
		for (int i = 1, j = 0; i < n; ++i) {      // bit-reversed order
			int bit = n >> 1;
			for (; (j & bit) != 0; bit >>= 1)
				j ^= bit;
			j ^= bit;
			if (i < j) {
				float t = xr[i]; xr[i] = xr[j]; xr[j] = t;
				t = xi[i]; xi[i] = xi[j]; xi[j] = t;
			}
		}
		for (int len = 2; len <= n; len <<= 1) {  // butterflies
			double ang = -2 * Math.PI / len;
			float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
			for (int i = 0; i < n; i += len) {
				float cr = 1, ci = 0;             // twiddle
				for (int k = 0; k < len / 2; ++k) {
					int a = i + k, b = a + len / 2;
					float tr = xr[b] * cr - xi[b] * ci, ti = xr[b] * ci + xi[b] * cr;
					xr[b] = xr[a] - tr; xi[b] = xi[a] - ti;
					xr[a] += tr; xi[a] += ti;
					float nr = cr * wr - ci * wi; // next twiddle
					ci = cr * wi + ci * wr;
					cr = nr;
				}
			}
		}
	}
}
