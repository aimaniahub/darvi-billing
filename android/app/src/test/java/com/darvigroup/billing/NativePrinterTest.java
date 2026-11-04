package com.darvigroup.billing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class NativePrinterTest {

    @Test
    public void textModeInitializationResetsPrinterAndSelectsPc437() {
        assertArrayEquals(new byte[] {
            0x1B, 0x40,
            0x1B, 0x74, 0x00,
            0x1B, 0x21, 0x00
        }, NativePrinter.textInitializationCommands());
    }

    @Test
    public void rasterCommandUsesEscPosGsV0HeaderAndPackedRows() {
        byte[] pixels = {(byte) 0x80, 0x40, 0x01, 0x02};

        assertArrayEquals(new byte[] {
            0x1D, 0x76, 0x30, 0x00,
            0x02, 0x00,
            0x02, 0x00,
            (byte) 0x80, 0x40, 0x01, 0x02
        }, NativePrinter.rasterCommand(2, 2, pixels));
    }

    @Test
    public void rasterCommandRejectsDataWithIncorrectDimensions() {
        assertThrows(IllegalArgumentException.class,
            () -> NativePrinter.rasterCommand(2, 2, new byte[] {0x01}));
    }

    @Test
    public void largeRasterIsSplitIntoOrderedPrinterSizedBands() {
        int widthBytes = 2;
        int height = 260;
        byte[] image = new byte[widthBytes * height];
        image[0] = 1;
        image[widthBytes * 128] = 2;
        image[widthBytes * 256] = 3;

        java.util.List<byte[]> bands = NativePrinter.rasterBands(widthBytes, height, image);

        assertEquals(3, bands.size());
        assertArrayEquals(new byte[] {0x1D, 0x76, 0x30, 0, 2, 0, (byte) 128, 0},
            java.util.Arrays.copyOf(bands.get(0), 8));
        assertArrayEquals(new byte[] {0x1D, 0x76, 0x30, 0, 2, 0, (byte) 128, 0},
            java.util.Arrays.copyOf(bands.get(1), 8));
        assertArrayEquals(new byte[] {0x1D, 0x76, 0x30, 0, 2, 0, 4, 0},
            java.util.Arrays.copyOf(bands.get(2), 8));
        assertEquals(1, bands.get(0)[8]);
        assertEquals(2, bands.get(1)[8]);
        assertEquals(3, bands.get(2)[8]);
    }

    @Test
    public void receiptSendsThankYouRasterAsSeparateFlushBeforeFeed() {
        byte[] bodyRaster = {0x1D, 0x76, 0x30, 0, 0x01, 0, 0x10, 0, 0x01};
        byte[] footerRaster = {0x1D, 0x76, 0x30, 0, 0x01, 0, 0x02, 0, 0x02};
        List<byte[]> segments = NativePrinter.receiptSegments(bodyRaster, footerRaster);

        assertEquals(4, segments.size());
        assertArrayEquals(new byte[] {0x1B, 0x40}, segments.get(0));
        assertArrayEquals(bodyRaster, segments.get(1));
        assertArrayEquals(footerRaster, segments.get(2));
        assertArrayEquals(new byte[] {0x1B, 0x64, 0x04}, segments.get(3));
    }

    @Test
    public void receiptRowsSeparateLabelsAndAmountsForRightAlignment() {
        assertEquals("2 x 50.00\t100.00",
            NativePrinter.alignedReceiptLine("2 x 50.00", "100.00"));
        assertEquals("TOTAL\t6335.00",
            NativePrinter.alignedReceiptLine("TOTAL", "6335.00"));
    }

}
