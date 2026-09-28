import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
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
 * Glyph coordinates live in the 108x108 adaptive-icon space: headphones whose headband carries
 * a forward arrow ("the headset moves on").
 *
 * Run from the repo root:  java tools/IconGen.java
 */
public class IconGen {
    static final Color TOP = new Color(0x1F, 0xA2, 0xC4);
    static final Color BOTTOM = new Color(0x3D, 0x3B, 0xB7);
    static final Color GLYPH = Color.WHITE;
    static final Color ARROW = new Color(0xFF, 0xC9, 0x40);

    public static void main(String[] args) throws Exception {
        new File("docs/brand").mkdirs();
        new File("desktop/src/main/resources").mkdirs();
        ImageIO.write(icon(512), "png", new File("docs/brand/handoff-logo-512.png"));
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
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setPaint(new GradientPaint(0, 0, TOP, size, size, BOTTOM));
        double r = size * 0.44;
        g.fill(new RoundRectangle2D.Double(0, 0, size, size, r, r));
        // Glyph box is x 30..78, y ~34..75 in 108-space; make it ~64% of the icon width.
        double k = size * 0.64 / 48.0;
        AffineTransform t = new AffineTransform();
        t.translate(size / 2.0, size / 2.0 + size * 0.02);
        t.scale(k, k);
        t.translate(-54, -55);
        g.transform(t);
        drawGlyph(g, true);
        g.dispose();
        return img;
    }

    static void drawGlyph(Graphics2D g, boolean colorArrow) {
        g.setColor(GLYPH);
        g.fill(new RoundRectangle2D.Double(30, 55, 10, 20, 8, 8));
        g.fill(new RoundRectangle2D.Double(68, 55, 10, 20, 8, 8));
        g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        // Headband: centre (54,58), radius 19. The left piece runs to 100 degrees and ends in an
        // arrowhead along the curve's tangent; the right piece resumes at 60 degrees.
        g.draw(new Arc2D.Double(35, 39, 38, 38, 180, -80, Arc2D.OPEN));
        g.draw(new Arc2D.Double(35, 39, 38, 38, 60, -60, Arc2D.OPEN));
        Path2D arrow = new Path2D.Double();
        arrow.moveTo(48.68, 33.55);
        arrow.lineTo(59.57, 37.73);
        arrow.lineTo(50.76, 45.37);
        arrow.closePath();
        g.setColor(colorArrow ? ARROW : GLYPH);
        g.fill(arrow);
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
