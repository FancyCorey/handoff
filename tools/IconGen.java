import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Renders the Handoff logo from the same geometry as the Android vector drawables
 * (app/src/main/res/drawable/ic_launcher_*.xml) and docs/brand/handoff-logo.svg.
 *
 * Glyph coordinates live in the 108x108 adaptive-icon space: an "H" whose crossbar is a sound
 * wave, two devices with the sound travelling between them.
 *
 * Run from the repo root:  java tools/IconGen.java
 */
public class IconGen {
    static final Color TOP = new Color(0x1F, 0xA2, 0xC4);
    static final Color BOTTOM = new Color(0x3D, 0x3B, 0xB7);
    static final Color GLYPH = Color.WHITE;
    static final Color WAVE = new Color(0xFF, 0xC9, 0x40);

    public static void main(String[] args) throws Exception {
        new File("docs/brand").mkdirs();
        new File("desktop/src/main/resources").mkdirs();
        ImageIO.write(icon(512), "png", new File("docs/brand/handoff-logo-512.png"));
        // Google Play wants a full square; it applies its own rounded mask.
        ImageIO.write(playIcon(512), "png", new File("docs/brand/handoff-play-icon-512.png"));
        ImageIO.write(icon(256), "png", new File("desktop/src/main/resources/handoff.png"));
        List<BufferedImage> sizes = new ArrayList<>();
        for (int s : new int[] {16, 20, 24, 32, 40, 48, 64, 128, 256}) sizes.add(icon(s));
        writeIco(sizes, new File("desktop/src/main/resources/handoff.ico"));
        // Small, high-contrast tray icon (white glyph on transparent is invisible on light taskbars).
        ImageIO.write(icon(64), "png", new File("desktop/src/main/resources/tray.png"));
        System.out.println("icons written");
    }

    /** Rounded-square app icon with the glyph centred. */
    static BufferedImage icon(int size) {
        return render(size, size * 0.44, 1.1);
    }

    /** Full-bleed square for the Play Store (no transparency, no rounded corners). */
    static BufferedImage playIcon(int size) {
        return render(size, 0, 1.25);
    }

    static BufferedImage render(int size, double cornerRadius, double glyphScale) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setPaint(new GradientPaint(0, 0, TOP, size, size, BOTTOM));
        if (cornerRadius > 0) {
            g.fill(new RoundRectangle2D.Double(0, 0, size, size, cornerRadius, cornerRadius));
        } else {
            g.fillRect(0, 0, size, size);
        }
        double k = size / 108.0 * glyphScale;
        AffineTransform t = new AffineTransform();
        t.translate(size / 2.0, size / 2.0);
        t.scale(k, k);
        t.translate(-54, -54);
        g.transform(t);
        drawGlyph(g, true);
        g.dispose();
        return img;
    }

    static void drawGlyph(Graphics2D g, boolean colorWave) {
        g.setColor(GLYPH);
        // Two devices: stadium bars 13 wide, 44 tall.
        g.fill(new RoundRectangle2D.Double(29, 32, 13, 44, 13, 13));
        g.fill(new RoundRectangle2D.Double(66, 32, 13, 44, 13, 13));
        // The sound wave between them.
        Path2D wave = new Path2D.Double();
        wave.moveTo(40, 58);
        wave.curveTo(48, 58, 49, 48, 54, 48);
        wave.curveTo(59, 48, 60, 58, 68, 58);
        g.setStroke(new BasicStroke(9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(colorWave ? WAVE : GLYPH);
        g.draw(wave);
    }

    /** ICO container with PNG-compressed entries (supported since Windows Vista). */
    static void writeIco(List<BufferedImage> images, File out) throws Exception {
        List<byte[]> pngs = new ArrayList<>();
        for (BufferedImage img : images) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", bos);
            pngs.add(bos.toByteArray());
        }
        int headerSize = 6 + 16 * images.size();
        ByteBuffer header = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort((short) 0).putShort((short) 1).putShort((short) images.size());
        int offset = headerSize;
        for (int i = 0; i < images.size(); i++) {
            int s = images.get(i).getWidth();
            header.put((byte) (s >= 256 ? 0 : s)).put((byte) (s >= 256 ? 0 : s));
            header.put((byte) 0).put((byte) 0).putShort((short) 1).putShort((short) 32);
            header.putInt(pngs.get(i).length).putInt(offset);
            offset += pngs.get(i).length;
        }
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(header.array());
            for (byte[] png : pngs) fos.write(png);
        }
    }
}
