package com.omnideck.mobile.core;

import java.io.IOException;
import java.io.InputStream;

/**
 * Reads a JPEG's EXIF orientation (tag 0x0112 in IFD0 of the APP1 "Exif"
 * segment) from its first bytes. Phone cameras often store a portrait photo
 * as landscape pixels plus this tag, and BitmapFactory ignores the tag, so the
 * app turns the pixels itself before a vision model sees them. Plain Java:
 * works for content:// streams on every API level (ExifInterface(InputStream)
 * only exists from API 24).
 */
public final class ExifOrientation {
    /** No orientation found (not a JPEG, no EXIF, or no tag). */
    public static final int UNDEFINED = 0;
    public static final int NORMAL = 1;
    public static final int FLIP_HORIZONTAL = 2;
    public static final int ROTATE_180 = 3;
    public static final int FLIP_VERTICAL = 4;
    public static final int TRANSPOSE = 5;
    public static final int ROTATE_90 = 6;
    public static final int TRANSVERSE = 7;
    public static final int ROTATE_270 = 8;

    /** Bytes read from the start of the file: EXIF sits in the first segments and APP1 is at most 64 KB. */
    public static final int HEAD_BYTES = 128 * 1024;

    private static final int TAG_ORIENTATION = 0x0112;

    private ExifOrientation() {}

    /** Reads up to {@link #HEAD_BYTES} of {@code in} (the caller closes it) and returns the orientation. */
    public static int read(InputStream in) throws IOException {
        byte[] buf = new byte[HEAD_BYTES];
        int len = 0;
        while (len < buf.length) {
            int n = in.read(buf, len, buf.length - len);
            if (n < 0) break;
            len += n;
        }
        return read(buf, len);
    }

    /** The orientation (1–8) in the first {@code len} bytes of a JPEG, or {@link #UNDEFINED}. */
    public static int read(byte[] b, int len) {
        int end = Math.min(len, b == null ? 0 : b.length);
        if (end < 4 || u8(b, 0) != 0xFF || u8(b, 1) != 0xD8) return UNDEFINED;
        int pos = 2;
        while (pos + 4 <= end) {
            if (u8(b, pos) != 0xFF) return UNDEFINED; // not at a marker: corrupt or not a JPEG
            int marker = u8(b, pos + 1);
            if (marker == 0xFF) { // fill byte
                pos++;
                continue;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) { // markers without a length
                pos += 2;
                continue;
            }
            if (marker == 0xDA || marker == 0xD9) return UNDEFINED; // image data starts: no EXIF before it
            int segLen = (u8(b, pos + 2) << 8) | u8(b, pos + 3);
            if (segLen < 2) return UNDEFINED;
            int data = pos + 4;
            if (marker == 0xE1 && segLen >= 8 && isExifHeader(b, data, end)) {
                int o = fromTiff(b, data + 6, Math.min(end, pos + 2 + segLen));
                // Another APP1 (XMP) may come first; keep looking if this one had no tag.
                if (o != UNDEFINED) return o;
            }
            pos += 2 + segLen;
        }
        return UNDEFINED;
    }

    /** Clockwise turn that shows the image upright: 0, 90, 180 or 270. */
    public static int degrees(int orientation) {
        switch (orientation) {
            case ROTATE_180:
            case FLIP_VERTICAL:
                return 180;
            case ROTATE_90:
            case TRANSPOSE:
                return 90;
            case ROTATE_270:
            case TRANSVERSE:
                return 270;
            default:
                return 0;
        }
    }

    /** True when the image must also be mirrored left-to-right, after turning it by {@link #degrees}. */
    public static boolean mirrored(int orientation) {
        return orientation == FLIP_HORIZONTAL || orientation == FLIP_VERTICAL || orientation == TRANSPOSE
                || orientation == TRANSVERSE;
    }

    /** The orientation for a plain clockwise rotation in degrees (MediaStore's "orientation" column). */
    public static int fromDegrees(int degrees) {
        int d = ((degrees % 360) + 360) % 360;
        return d == 90 ? ROTATE_90 : d == 180 ? ROTATE_180 : d == 270 ? ROTATE_270 : NORMAL;
    }

    private static boolean isExifHeader(byte[] b, int at, int end) {
        return at + 6 <= end && b[at] == 'E' && b[at + 1] == 'x' && b[at + 2] == 'i' && b[at + 3] == 'f'
                && b[at + 4] == 0 && b[at + 5] == 0;
    }

    /** Walks IFD0 of the TIFF structure at {@code tiff} (bounded by {@code end}) for the orientation tag. */
    private static int fromTiff(byte[] b, int tiff, int end) {
        if (tiff + 8 > end) return UNDEFINED;
        boolean little;
        if (b[tiff] == 'I' && b[tiff + 1] == 'I') little = true;
        else if (b[tiff] == 'M' && b[tiff + 1] == 'M') little = false;
        else return UNDEFINED;
        if (u16(b, tiff + 2, little) != 42) return UNDEFINED;
        long ifd = u32(b, tiff + 4, little);
        if (ifd < 8 || tiff + ifd + 2 > end) return UNDEFINED;
        int at = (int) (tiff + ifd);
        int count = u16(b, at, little);
        at += 2;
        for (int i = 0; i < count && at + 12 <= end; i++, at += 12) {
            if (u16(b, at, little) != TAG_ORIENTATION) continue;
            int type = u16(b, at + 2, little);
            long value = type == 4 ? u32(b, at + 8, little) : type == 3 ? u16(b, at + 8, little) : -1;
            return value >= NORMAL && value <= ROTATE_270 ? (int) value : UNDEFINED;
        }
        return UNDEFINED;
    }

    private static int u8(byte[] b, int i) {
        return b[i] & 0xFF;
    }

    private static int u16(byte[] b, int i, boolean little) {
        return little ? u8(b, i) | (u8(b, i + 1) << 8) : (u8(b, i) << 8) | u8(b, i + 1);
    }

    private static long u32(byte[] b, int i, boolean little) {
        long v = little
                ? u8(b, i) | (u8(b, i + 1) << 8) | (u8(b, i + 2) << 16) | ((long) u8(b, i + 3) << 24)
                : ((long) u8(b, i) << 24) | (u8(b, i + 1) << 16) | (u8(b, i + 2) << 8) | u8(b, i + 3);
        return v & 0xFFFFFFFFL;
    }
}
