package com.omnideck.mobile.core;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExifOrientationTest {

    /** A minimal JPEG: SOI, optional JFIF APP0, optional XMP APP1, an Exif APP1 with IFD0, SOS, EOI. */
    static byte[] jpeg(boolean little, int orientation, boolean jfif, boolean xmpFirst) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);
        if (jfif) {
            byte[] app0 = {'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0};
            segment(out, 0xE0, app0);
        }
        if (xmpFirst) {
            byte[] xmp = "http://ns.adobe.com/xap/1.0/\0<x:xmpmeta/>".getBytes(Http.UTF8);
            segment(out, 0xE1, xmp);
        }
        if (orientation >= 0) {
            ByteArrayOutputStream tiff = new ByteArrayOutputStream();
            tiff.write(little ? 'I' : 'M');
            tiff.write(little ? 'I' : 'M');
            u16(tiff, 42, little);
            u32(tiff, 8, little); // IFD0 right after the header
            u16(tiff, 2, little); // two entries: Make, then Orientation
            u16(tiff, 0x010F, little);
            u16(tiff, 2, little); // ASCII
            u32(tiff, 4, little);
            tiff.write('A');
            tiff.write('C');
            tiff.write('M');
            tiff.write(0);
            u16(tiff, 0x0112, little);
            u16(tiff, 3, little); // SHORT
            u32(tiff, 1, little);
            u16(tiff, orientation, little);
            u16(tiff, 0, little); // padding of the 4-byte value field
            u32(tiff, 0, little); // no next IFD
            ByteArrayOutputStream app1 = new ByteArrayOutputStream();
            app1.write('E');
            app1.write('x');
            app1.write('i');
            app1.write('f');
            app1.write(0);
            app1.write(0);
            byte[] t = tiff.toByteArray();
            app1.write(t, 0, t.length);
            segment(out, 0xE1, app1.toByteArray());
        }
        segment(out, 0xDA, new byte[]{1, 2, 3, 4});
        for (int i = 0; i < 64; i++) out.write(i); // "image data"
        out.write(0xFF);
        out.write(0xD9);
        return out.toByteArray();
    }

    private static void segment(ByteArrayOutputStream out, int marker, byte[] data) {
        out.write(0xFF);
        out.write(marker);
        int len = data.length + 2;
        out.write(len >> 8);
        out.write(len & 0xFF);
        out.write(data, 0, data.length);
    }

    private static void u16(ByteArrayOutputStream o, int v, boolean little) {
        if (little) {
            o.write(v & 0xFF);
            o.write((v >> 8) & 0xFF);
        } else {
            o.write((v >> 8) & 0xFF);
            o.write(v & 0xFF);
        }
    }

    private static void u32(ByteArrayOutputStream o, long v, boolean little) {
        for (int i = 0; i < 4; i++) {
            int shift = little ? 8 * i : 8 * (3 - i);
            o.write((int) ((v >> shift) & 0xFF));
        }
    }

    private static int read(byte[] b) {
        return ExifOrientation.read(b, b.length);
    }

    @Test
    public void readsEveryOrientationInBothByteOrders() {
        for (int o = 1; o <= 8; o++) {
            assertEquals("little " + o, o, read(jpeg(true, o, false, false)));
            assertEquals("big " + o, o, read(jpeg(false, o, false, false)));
            assertEquals("after JFIF " + o, o, read(jpeg(true, o, true, false)));
        }
    }

    @Test
    public void skipsAnXmpSegmentBeforeTheExifOne() {
        assertEquals(6, read(jpeg(false, 6, true, true)));
    }

    @Test
    public void missingOrInvalidDataIsUndefined() {
        assertEquals(ExifOrientation.UNDEFINED, read(jpeg(true, -1, true, false)));
        assertEquals("out of range", ExifOrientation.UNDEFINED, read(jpeg(true, 9, false, false)));
        assertEquals(ExifOrientation.UNDEFINED, read(jpeg(true, 0, false, false)));
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13};
        assertEquals(ExifOrientation.UNDEFINED, read(png));
        assertEquals(ExifOrientation.UNDEFINED, ExifOrientation.read(new byte[0], 0));
        assertEquals(ExifOrientation.UNDEFINED, ExifOrientation.read(null, 10));
    }

    @Test
    public void truncatedHeadsNeverThrow() {
        byte[] full = jpeg(true, 6, true, true);
        for (int len = 0; len <= full.length; len++) {
            int o = ExifOrientation.read(full, len);
            assertTrue(o == ExifOrientation.UNDEFINED || o == 6);
        }
        // Random bytes after a JPEG signature: no exceptions, only UNDEFINED or a valid value.
        Random r = new Random(42);
        for (int i = 0; i < 500; i++) {
            byte[] junk = new byte[r.nextInt(400) + 4];
            r.nextBytes(junk);
            junk[0] = (byte) 0xFF;
            junk[1] = (byte) 0xD8;
            int o = read(junk);
            assertTrue(o >= 0 && o <= 8);
        }
    }

    @Test
    public void readsFromASlowStream() throws IOException {
        final byte[] img = jpeg(false, 8, true, false);
        InputStream slow = new FilterInputStream(new ByteArrayInputStream(img)) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return super.read(b, off, Math.min(len, 3)); // tiny reads, like a network stream
            }
        };
        assertEquals(8, ExifOrientation.read(slow));
    }

    @Test
    public void rotationAndMirroring() {
        int[] degrees = {0, 0, 0, 180, 180, 90, 90, 270, 270};
        boolean[] mirror = {false, false, true, false, true, true, false, true, false};
        for (int o = 0; o <= 8; o++) {
            assertEquals("degrees " + o, degrees[o], ExifOrientation.degrees(o));
            assertEquals("mirror " + o, mirror[o], ExifOrientation.mirrored(o));
        }
        assertEquals(ExifOrientation.ROTATE_90, ExifOrientation.fromDegrees(90));
        assertEquals(ExifOrientation.ROTATE_180, ExifOrientation.fromDegrees(180));
        assertEquals(ExifOrientation.ROTATE_270, ExifOrientation.fromDegrees(-90));
        assertEquals(ExifOrientation.NORMAL, ExifOrientation.fromDegrees(0));
        assertEquals(ExifOrientation.NORMAL, ExifOrientation.fromDegrees(45));
        assertFalse(ExifOrientation.mirrored(ExifOrientation.ROTATE_90));
    }
}
