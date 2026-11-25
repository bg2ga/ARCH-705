package uk.hamdigital.icom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;

public class IcomScopeAssemblerTest {
    @Test public void parsesSinglePacketCenterScope() {
        IcomScopeAssembler assembler = new IcomScopeAssembler();
        byte[] payload = new byte[14 + IcomScopeAssembler.AMPLITUDE_COUNT];
        payload[0] = 0x01; payload[1] = 0x01; payload[2] = 0x00;
        copy(payload, 3, new byte[]{0x00, 0x40, 0x07, 0x14, 0x00});
        copy(payload, 8, new byte[]{0x00, 0x50, 0x00, 0x00, 0x00});
        payload[13] = 0x00;
        for (int i = 0; i < IcomScopeAssembler.AMPLITUDE_COUNT; i++) payload[14 + i] = (byte) (i % 161);
        IcomScopeAssembler.Frame frame = assembler.accept(payload);
        assertNotNull(frame);
        assertEquals(14.069, frame.startMhz, 0.0001);
        assertEquals(14.079, frame.endMhz, 0.0001);
        assertEquals(0, frame.scopeMode);
        assertEquals(100, frame.amplitudes[100]);
    }

    @Test public void assemblesSplitScopePackets() {
        IcomScopeAssembler assembler = new IcomScopeAssembler();
        byte[] first = new byte[14 + 300];
        first[0] = 0x01; first[1] = 0x02; first[2] = 0x01;
        copy(first, 3, new byte[]{0x00, 0x40, 0x07, 0x14, 0x00});
        copy(first, 8, new byte[]{0x00, 0x50, 0x00, 0x00, 0x00});
        Arrays.fill(first, 14, first.length, (byte) 1);
        assertNull(assembler.accept(first));
        byte[] second = new byte[2 + 175];
        second[0] = 0x02; second[1] = 0x02;
        Arrays.fill(second, 2, second.length, (byte) 2);
        IcomScopeAssembler.Frame frame = assembler.accept(second);
        assertNotNull(frame);
        assertEquals(1, frame.amplitudes[299]);
        assertEquals(2, frame.amplitudes[300]);
        assertEquals(2, frame.amplitudes[474]);
    }

    @Test public void rejectsNon475PointFrame() {
        IcomScopeAssembler assembler = new IcomScopeAssembler();
        byte[] payload = new byte[14 + IcomScopeAssembler.AMPLITUDE_COUNT - 1];
        payload[0] = 0x01; payload[1] = 0x01; payload[2] = 0x00;
        copy(payload, 3, new byte[]{0x00, 0x40, 0x07, 0x14, 0x00});
        copy(payload, 8, new byte[]{0x00, 0x50, 0x00, 0x00, 0x00});
        assertNull(assembler.accept(payload));
    }

    private static void copy(byte[] target, int offset, byte[] source) {
        System.arraycopy(source, 0, target, offset, source.length);
    }
}
