package io.github.jbellis.brokk.util;

import dev.langchain4j.data.image.Image;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * Utility class for image handling, particularly conversions needed for LangChain4j.
 */
public class ImageUtil {
    private static final Logger logger = LogManager.getLogger(ImageUtil.class);

    /**
     * Converts a java.awt.Image to a LangChain4j Image object.
     * Encodes the image as Base64 PNG data.
     *
     * @param awtImage The java.awt.Image to convert.
     * @return A LangChain4j Image object containing the Base64 encoded data and MIME type.
     * @throws IOException If an error occurs during image writing or encoding.
     */
    public static Image toL4JImage(java.awt.Image awtImage) throws IOException {
        if (awtImage == null) {
            throw new IllegalArgumentException("Input java.awt.Image cannot be null");
        }

        // Convert java.awt.Image to BufferedImage
        BufferedImage bufferedImage;
        if (awtImage instanceof BufferedImage bi) {
            bufferedImage = bi;
        } else {
            // Create a BufferedImage with transparency support
            int width = awtImage.getWidth(null);
            int height = awtImage.getHeight(null);
            if (width <= 0 || height <= 0) {
                throw new IOException("Invalid image dimensions: " + width + "x" + height);
            }
            // Use TYPE_INT_ARGB for transparency support
            bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

            // Draw the awtImage onto the BufferedImage
            Graphics2D g2d = bufferedImage.createGraphics();
            try {
                g2d.drawImage(awtImage, 0, 0, null);
            } finally {
                g2d.dispose();
            }
        }

        // Write BufferedImage to byte array as PNG
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        boolean success = ImageIO.write(bufferedImage, "png", baos);
        if (!success) {
            logger.error("Failed to find writer for PNG format. ImageIO plugins might be missing.");
            throw new IOException("Could not write image to PNG format");
        }
        byte[] imageBytes = baos.toByteArray();

        // Encode byte array to Base64
        String base64Data = Base64.getEncoder().encodeToString(imageBytes);

        // Define MIME type
        String mimeType = "image/png";

        // Create LangChain4j Image object
        return Image.builder()
                    .base64Data(base64Data)
                    .mimeType(mimeType)
                    .build();
    }
}
