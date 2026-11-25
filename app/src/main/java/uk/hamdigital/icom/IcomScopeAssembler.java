package uk.hamdigital.icom;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Assembles IC-705 CI-V 0x27 0x00 scope frames split over several serial packets. */
public final class IcomScopeAssembler {
    public static final int AMPLITUDE_COUNT = 475;

    public static final class Frame {
        public final int[] amplitudes;
        public final double startMhz;
        public final double endMhz;
        public final int scopeMode;

        Frame(int[] amplitudes, double startMhz, double endMhz, int scopeMode) {
            this.amplitudes = amplitudes;
            this.startMhz = startMhz;
            this.endMhz = endMhz;
            this.scopeMode = scopeMode;
        }
    }

    private final ByteArrayOutputStream data = new ByteArrayOutputStream(AMPLITUDE_COUNT);
    private int nextSequence;
    private double startMhz;
    private double endMhz;
    private int scopeMode;

    private void reset() {
        data.reset();
        nextSequence = 0;
        startMhz = 0;
        endMhz = 0;
    }

    public Frame accept(byte[] payload) {
        if (payload == null || payload.length < 2) return null;
        int sequence = bcd(payload[0]);
        int sequenceMax = bcd(payload[1]);
        if (sequence <= 0 || sequenceMax <= 0 || sequence > sequenceMax) {
            reset();
            return null;
        }
        if (sequence == 1) {
            if (payload.length < 14) { reset(); return null; }
            data.reset();
            scopeMode = payload[2] & 0xFF;
            startMhz = decodeBcdFrequency(payload, 3) / 1_000_000.0;
            endMhz = decodeBcdFrequency(payload, 8) / 1_000_000.0;
            if (scopeMode == 0) {
                double span = endMhz;
                startMhz -= span;
                endMhz = startMhz + span * 2.0;
            }
            if ((payload[13] & 0xFF) != 0) {
                int[] flat = new int[AMPLITUDE_COUNT];
                Arrays.fill(flat, 0);
                double capturedStart = startMhz;
                double capturedEnd = endMhz;
                int capturedMode = scopeMode;
                reset();
                return new Frame(flat, capturedStart, capturedEnd, capturedMode);
            }
            data.write(payload, 14, payload.length - 14);
            nextSequence = sequence + 1;
        } else {
            if (nextSequence != sequence) { reset(); return null; }
            data.write(payload, 2, payload.length - 2);
            nextSequence = sequence + 1;
        }
        if (sequence != sequenceMax) return null;
        byte[] raw = data.toByteArray();
        double capturedStart = startMhz;
        double capturedEnd = endMhz;
        int capturedMode = scopeMode;
        reset();
        if (raw.length != AMPLITUDE_COUNT) return null;
        int[] amplitudes = new int[AMPLITUDE_COUNT];
        for (int i = 0; i < AMPLITUDE_COUNT; i++) amplitudes[i] = raw[i] & 0xFF;
        return new Frame(amplitudes, capturedStart, capturedEnd, capturedMode);
    }

    private static int bcd(int value) { return (value & 0x0F) + ((value >> 4) & 0x0F) * 10; }

    private static long decodeBcdFrequency(byte[] data, int offset) {
        long value = 0;
        long mul = 1;
        for (int i = 0; i < 5; i++) {
            int b = data[offset + i] & 0xFF;
            value += (b & 0x0F) * mul;
            mul *= 10;
            value += ((b >> 4) & 0x0F) * mul;
            mul *= 10;
        }
        return value;
    }
}
