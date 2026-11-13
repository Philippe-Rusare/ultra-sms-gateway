package com.ultracoresoft.ultrasmsgateway.dashboard;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.Map;

/**
 * A QR code drawn on the server as an SVG path: nothing is sent to an external service and the page
 * needs no JavaScript library.
 *
 * @param size number of modules per side, quiet zone excluded
 * @param path SVG path of the dark modules, one unit per module
 */
public record QrCode(int size, String path) {

    public static QrCode of(String content) {
        BitMatrix matrix;
        try {
            matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, Map.of(
                    EncodeHintType.MARGIN, 0,
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M));
        } catch (WriterException e) {
            throw new IllegalArgumentException("Content too long for a QR code", e);
        }
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < matrix.getHeight(); y++) {
            int x = 0;
            while (x < matrix.getWidth()) {
                if (!matrix.get(x, y)) {
                    x++;
                    continue;
                }
                int start = x;
                while (x < matrix.getWidth() && matrix.get(x, y)) {
                    x++;
                }
                path.append('M').append(start).append(' ').append(y).append('h').append(x - start).append("v1h-")
                        .append(x - start).append('z');
            }
        }
        return new QrCode(matrix.getWidth(), path.toString());
    }
}
