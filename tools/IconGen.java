import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * The single source of the Handoff logo. Generates every logo file from one geometry:
 * the SVG master, the Android vector drawables, the PNG and ICO files and the Play Store icon.
 *
 * The mark: a pair of headphones whose left half is an arrow. It rises from the left earcup and
 * curves over towards the right half, which sits one step up and over: the sound being handed
 * from one device to the other. Lavender is where it comes from, white is where it goes.
 *
 * Geometry lives in the 108x108 Android adaptive-icon grid. Angles are measured clockwise on
 * screen from 3 o'clock, so 180 is the left of a circle and 270 its top.
 *
 * Run from the repo root:  java tools/IconGen.java
 */
public class IconGen {
    // Colours
    static final Color TOP = new Color(0x4F, 0x46, 0xE5);      // indigo
    static final Color BOTTOM = new Color(0x31, 0x2E, 0x81);   // deep indigo
    static final Color FROM = new Color(0xA5, 0xB4, 0xFC);     // lavender: where the sound comes from
    static final Color TO = Color.WHITE;                       // where it goes

    // Geometry (108 grid)
    static final double SW = 8;                 // line weight
    static final double R = 22;                 // headband radius
    static final double[] C1 = {50, 61.5};      // left half's circle
    static final double[] C2 = {58, 55.5};      // right half's circle: one step up and over
    static final double TIP = 296;              // where the arrow's tip lands on the left circle
    static final double HEAD_LEN = 11.5, HEAD_HALF = 9.6, NOTCH = 0.14, HEAD_ROUND = 4.5;
    static final double RIGHT_START = 302;      // where the right half's band begins
    static final double CUP_W = SW * 1.5, CUP_H = 20;

    /** Optical centre of the drawing, used to centre scaling and crops (tile, mono mark). */
    static final double CX = 54, CY = 55.5;

    public static void main(String[] args) throws Exception {
        Geometry g = new Geometry();
        write("docs/brand/handoff-logo.svg", svgLogo(g));
        write("docs/brand/handoff-mark.svg", svgMark(g));
        String res = "app/src/main/res/drawable/";
        write(res + "ic_launcher_background.xml", vectorBackground());
        write(res + "ic_launcher_foreground.xml", vector(g, 108, 0, 0, 108, 0.66, hex(FROM), hex(TO), false));
        write(res + "ic_launcher_monochrome.xml", vector(g, 108, 0, 0, 108, 0.66, "#FFFFFFFF", "#FFFFFFFF", false));
        write(res + "ic_logo.xml", vector(g, 48, 0, 0, 108, 1.0, hex(FROM), hex(TO), true));
        write(res + "ic_mark.xml", vector(g, 48, CX - 34, CY - 34, 68, 1.0, hex(FROM), hex(TO), false));
        write(res + "ic_tile.xml", vector(g, 24, CX - 34, CY - 34, 68, 1.0, "#FFFFFFFF", "#FFFFFFFF", false));

        new File("desktop/src/main/resources").mkdirs();
        ImageIO.write(render(g, 512, 512 * 0.22, 1.0), "png", new File("docs/brand/handoff-logo-512.png"));
        ImageIO.write(render(g, 512, 0, 1.12), "png", new File("docs/brand/handoff-play-icon-512.png"));
        ImageIO.write(render(g, 256, 256 * 0.22, 1.0), "png", new File("desktop/src/main/resources/handoff.png"));
        ImageIO.write(render(g, 64, 64 * 0.22, 1.08), "png", new File("desktop/src/main/resources/tray.png"));
        ImageIO.write(renderMark(g, 128), "png", new File("desktop/src/main/resources/handoff-mark.png"));
        List<BufferedImage> sizes = new ArrayList<>();
        for (int s : new int[] {16, 20, 24, 32, 40, 48, 64, 128, 256}) sizes.add(render(g, s, s * 0.22, s <= 24 ? 1.08 : 1.0));
        writeIco(sizes, new File("desktop/src/main/resources/handoff.ico"));
        System.out.println("logo files written");
    }

    // ---- geometry ----------------------------------------------------------------------

    static double[] pt(double[] c, double deg) {
        double a = Math.toRadians(deg);
        return new double[] {c[0] + R * Math.cos(a), c[1] + R * Math.sin(a)};
    }

    /** All points of the mark, computed once. */
    static final class Geometry {
        final double[] leftStart = pt(C1, 180), rightStart = pt(C2, RIGHT_START), rightEnd = pt(C2, 360);
        final double[] leftEnd, tip, barb1, notch, barb2;
        final double leftEndDeg;
        final double[] leftCup = {C1[0] - R - CUP_W / 2, C1[1] - 4}, rightCup = {C2[0] + R - CUP_W / 2, C2[1] - 4};

        Geometry() {
            // The head's base sits one head-length back along the curve, and the head points along
            // the curve's direction there, so line and head read as one stroke.
            double baseDeg = TIP - Math.toDegrees(HEAD_LEN / R);
            double[] base = pt(C1, baseDeg);
            double a = Math.toRadians(baseDeg);
            double[] ax = {-Math.sin(a), Math.cos(a)};
            double[] nrm = {-ax[1], ax[0]};
            tip = new double[] {base[0] + ax[0] * HEAD_LEN, base[1] + ax[1] * HEAD_LEN};
            barb1 = new double[] {base[0] + nrm[0] * HEAD_HALF, base[1] + nrm[1] * HEAD_HALF};
            barb2 = new double[] {base[0] - nrm[0] * HEAD_HALF, base[1] - nrm[1] * HEAD_HALF};
            notch = new double[] {base[0] + ax[0] * HEAD_LEN * NOTCH, base[1] + ax[1] * HEAD_LEN * NOTCH};
            // The line runs a little way into the head, where it is hidden.
            leftEndDeg = baseDeg + Math.toDegrees(HEAD_LEN * 0.35 / R);
            leftEnd = pt(C1, leftEndDeg);
        }

        String leftArc() { return "M" + p(leftStart) + " A" + n(R) + "," + n(R) + " 0 0 1 " + p(leftEnd); }
        String rightArc() { return "M" + p(rightStart) + " A" + n(R) + "," + n(R) + " 0 0 1 " + p(rightEnd); }
        String head() { return "M" + p(tip) + " L" + p(barb1) + " L" + p(notch) + " L" + p(barb2) + " Z"; }
        String cup(double[] at) {
            double x = at[0], y = at[1], r = CUP_W / 2;
            return "M" + n(x + r) + "," + n(y) + " a" + n(r) + "," + n(r) + " 0 0 1 " + n(r) + "," + n(r)
                + " v" + n(CUP_H - 2 * r) + " a" + n(r) + "," + n(r) + " 0 0 1 " + n(-2 * r) + ",0"
                + " v" + n(-(CUP_H - 2 * r)) + " a" + n(r) + "," + n(r) + " 0 0 1 " + n(r) + "," + n(-r) + " z";
        }
    }

    static String n(double v) { return String.format(Locale.ROOT, "%.2f", v); }
    static String p(double[] v) { return n(v[0]) + "," + n(v[1]); }
    static String hex(Color c) { return String.format("#FF%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue()); }
    static String css(Color c) { return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue()); }

    // ---- SVG ---------------------------------------------------------------------------

    static String svgGlyph(Geometry g, String from, String to) {
        String line = "fill=\"none\" stroke-width=\"" + n(SW) + "\" stroke-linecap=\"round\"";
        return "    <path d=\"" + g.leftArc() + "\" stroke=\"" + from + "\" " + line + "/>\n"
            + "    <path d=\"" + g.cup(g.leftCup) + "\" fill=\"" + from + "\"/>\n"
            + "    <path d=\"" + g.head() + "\" fill=\"" + from + "\" stroke=\"" + from + "\" stroke-width=\"" + n(HEAD_ROUND) + "\" stroke-linejoin=\"round\"/>\n"
            + "    <path d=\"" + g.rightArc() + "\" stroke=\"" + to + "\" " + line + "/>\n"
            + "    <path d=\"" + g.cup(g.rightCup) + "\" fill=\"" + to + "\"/>\n";
    }

    static String svgLogo(Geometry g) {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 108 108\" width=\"512\" height=\"512\">\n"
            + "  <!-- Handoff logo master, generated by tools/IconGen.java. Headphones whose left half is an\n"
            + "       arrow handing the sound over to the right half. 108x108 adaptive-icon grid. -->\n"
            + "  <defs>\n    <linearGradient id=\"bg\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\">\n"
            + "      <stop offset=\"0\" stop-color=\"" + css(TOP) + "\"/>\n      <stop offset=\"1\" stop-color=\"" + css(BOTTOM) + "\"/>\n"
            + "    </linearGradient>\n  </defs>\n"
            + "  <rect width=\"108\" height=\"108\" rx=\"24\" fill=\"url(#bg)\"/>\n"
            + "  <g>\n" + svgGlyph(g, css(FROM), css(TO)) + "  </g>\n</svg>\n";
    }

    static String svgMark(Geometry g) {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"" + n(CX - 34) + " " + n(CY - 34) + " 68 68\" width=\"512\" height=\"512\">\n"
            + "  <!-- Handoff mark, single colour (currentColor). Generated by tools/IconGen.java. -->\n"
            + "  <g>\n" + svgGlyph(g, "currentColor", "currentColor") + "  </g>\n</svg>\n";
    }

    // ---- Android vector drawables -----------------------------------------------------

    static final String XML_HEADER = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
        + "<!-- Handoff logo, generated by tools/IconGen.java: edit the geometry there, not here. -->\n";

    static String vectorBackground() {
        return XML_HEADER
            + "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\" xmlns:aapt=\"http://schemas.android.com/aapt\"\n"
            + "    android:width=\"108dp\"\n    android:height=\"108dp\"\n    android:viewportWidth=\"108\"\n    android:viewportHeight=\"108\">\n"
            + "    <path android:pathData=\"M0,0 H108 V108 H0 Z\">\n" + gradientAttr() + "    </path>\n</vector>\n";
    }

    static String gradientAttr() {
        return "        <aapt:attr name=\"android:fillColor\">\n            <gradient\n                android:type=\"linear\"\n"
            + "                android:startX=\"0\" android:startY=\"0\"\n                android:endX=\"0\" android:endY=\"108\"\n"
            + "                android:startColor=\"" + hex(TOP) + "\"\n                android:endColor=\"" + hex(BOTTOM) + "\" />\n"
            + "        </aapt:attr>\n";
    }

    /**
     * A square vector of [sizeDp] showing the [viewport]-sized square at ([vx],[vy]) of the 108
     * grid, with the glyph scaled by [scale] about the drawing's optical centre.
     */
    static String vector(Geometry g, int sizeDp, double vx, double vy, double viewport, double scale,
                         String from, String to, boolean background) {
        StringBuilder s = new StringBuilder(XML_HEADER);
        s.append("<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"");
        if (background) s.append(" xmlns:aapt=\"http://schemas.android.com/aapt\"");
        s.append("\n    android:width=\"").append(sizeDp).append("dp\"\n    android:height=\"").append(sizeDp).append("dp\"\n")
            .append("    android:viewportWidth=\"").append(n(viewport)).append("\"\n    android:viewportHeight=\"").append(n(viewport)).append("\">\n");
        if (background) {
            s.append("    <path android:pathData=\"M24,0 H84 A24,24 0 0 1 108,24 V84 A24,24 0 0 1 84,108 H24 A24,24 0 0 1 0,84 V24 A24,24 0 0 1 24,0 Z\">\n")
                .append(gradientAttr()).append("    </path>\n");
        }
        s.append("    <group android:translateX=\"").append(n(-vx)).append("\" android:translateY=\"").append(n(-vy)).append("\">\n");
        s.append("        <group android:pivotX=\"").append(n(CX)).append("\" android:pivotY=\"").append(n(CY))
            .append("\" android:scaleX=\"").append(n(scale)).append("\" android:scaleY=\"").append(n(scale)).append("\">\n");
        String line = "                android:strokeWidth=\"" + n(SW) + "\"\n                android:strokeLineCap=\"round\"\n";
        s.append("            <path\n                android:pathData=\"").append(g.leftArc()).append("\"\n").append(line)
            .append("                android:strokeColor=\"").append(from).append("\" />\n");
        s.append("            <path\n                android:fillColor=\"").append(from).append("\"\n                android:pathData=\"").append(g.cup(g.leftCup)).append("\" />\n");
        s.append("            <path\n                android:fillColor=\"").append(from).append("\"\n                android:strokeColor=\"").append(from)
            .append("\"\n                android:strokeWidth=\"").append(n(HEAD_ROUND)).append("\"\n                android:strokeLineJoin=\"round\"\n")
            .append("                android:pathData=\"").append(g.head()).append("\" />\n");
        s.append("            <path\n                android:pathData=\"").append(g.rightArc()).append("\"\n").append(line)
            .append("                android:strokeColor=\"").append(to).append("\" />\n");
        s.append("            <path\n                android:fillColor=\"").append(to).append("\"\n                android:pathData=\"").append(g.cup(g.rightCup)).append("\" />\n");
        s.append("        </group>\n    </group>\n</vector>\n");
        return s.toString();
    }

    // ---- raster ------------------------------------------------------------------------

    /** The icon at [size] px: gradient square ([cornerRadius] 0 = full bleed) with the glyph scaled by [glyphScale]. */
    static BufferedImage render(Geometry g, int size, double cornerRadius, double glyphScale) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gr = img.createGraphics();
        gr.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        gr.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        gr.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        gr.setPaint(new GradientPaint(0, 0, TOP, 0, size, BOTTOM));
        if (cornerRadius > 0) gr.fill(new RoundRectangle2D.Double(0, 0, size, size, cornerRadius * 2, cornerRadius * 2));
        else gr.fillRect(0, 0, size, size);
        AffineTransform t = new AffineTransform();
        t.scale(size / 108.0, size / 108.0);
        t.translate(CX, CY);
        t.scale(glyphScale, glyphScale);
        t.translate(-CX, -CY);
        gr.transform(t);
        drawGlyph(gr, g);
        gr.dispose();
        return img;
    }

    /** The two-tone mark alone on a transparent background, cropped like the tile icon (68 of 108). */
    static BufferedImage renderMark(Geometry g, int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gr = img.createGraphics();
        gr.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        gr.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        gr.scale(size / 68.0, size / 68.0);
        gr.translate(-(CX - 34), -(CY - 34));
        drawGlyph(gr, g);
        gr.dispose();
        return img;
    }

    static void drawGlyph(Graphics2D gr, Geometry g) {
        BasicStroke line = new BasicStroke((float) SW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        BasicStroke round = new BasicStroke((float) HEAD_ROUND, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        gr.setColor(FROM);
        gr.setStroke(line);
        gr.draw(arc(C1, 180, g.leftEndDeg));
        gr.fill(cup(g.leftCup));
        Shape head = poly(g.tip, g.barb1, g.notch, g.barb2);
        gr.fill(head);
        gr.setStroke(round);
        gr.draw(head);
        gr.setColor(TO);
        gr.setStroke(line);
        gr.draw(arc(C2, RIGHT_START, 360));
        gr.fill(cup(g.rightCup));
    }

    /** Clockwise-on-screen arc from [fromDeg] to [toDeg] (Java's Arc2D measures counter-clockwise). */
    static Shape arc(double[] c, double fromDeg, double toDeg) {
        return new Arc2D.Double(c[0] - R, c[1] - R, 2 * R, 2 * R, -fromDeg, -(toDeg - fromDeg), Arc2D.OPEN);
    }

    static Shape cup(double[] at) {
        return new RoundRectangle2D.Double(at[0], at[1], CUP_W, CUP_H, CUP_W, CUP_W);
    }

    static Shape poly(double[]... pts) {
        Path2D path = new Path2D.Double();
        path.moveTo(pts[0][0], pts[0][1]);
        for (int i = 1; i < pts.length; i++) path.lineTo(pts[i][0], pts[i][1]);
        path.closePath();
        return path;
    }

    static void write(String path, String text) throws Exception {
        new File(path).getAbsoluteFile().getParentFile().mkdirs();
        Files.write(new File(path).toPath(), text.getBytes(StandardCharsets.UTF_8));
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
