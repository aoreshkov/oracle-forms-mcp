/*
 * Renders assets/icon.svg to a PNG with Java2D — the registry's `icons` entries need a PNG
 * because MCP clients MUST support image/png but only SHOULD support image/svg+xml.
 *
 * This is NOT wired into the Gradle build or CI: it is a one-shot generator, run by hand
 * whenever assets/icon.svg changes, and the resulting PNG is committed. It exists so the repo
 * does not need ImageMagick / Inkscape / cairosvg just to keep one file in sync.
 *
 * Run from the repo root with the JDK 21 single-file source launcher:
 *
 *   java scripts/icon/GenerateIcon.java [output.png]
 *
 * The geometry below MUST mirror assets/icon.svg. Keep the two in step by hand — there is no
 * SVG parser here, and a divergence only shows up as a mismatched icon in a marketplace listing.
 */

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.Paint;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

public final class GenerateIcon {

    private static final int SIZE = 512;
    // SVG `rx` is a radius; Java2D's arc width/height are diameters — always pass 2 * radius.
    private static final int PANEL_RADIUS = 96;
    private static final int ROW_RADIUS = 16;

    private static final Color BG_FROM = new Color(0x0d1b2a);
    private static final Color BG_TO = new Color(0x132a3f);
    private static final Color ACCENT_FROM = new Color(0x7F52FF);
    private static final Color ACCENT_TO = new Color(0x3b82f6);

    public static void main(String[] args) throws IOException {
        File out = new File(args.length > 0 ? args[0] : "assets/icon-512.png");

        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        RoundRectangle2D panel =
                new RoundRectangle2D.Float(0, 0, SIZE, SIZE, 2 * PANEL_RADIUS, 2 * PANEL_RADIUS);

        // panel background: diagonal gradient
        g.setPaint(new LinearGradientPaint(
                new Point2D.Float(0, 0), new Point2D.Float(SIZE, SIZE),
                new float[] {0f, 1f}, new Color[] {BG_FROM, BG_TO}));
        g.fill(panel);

        // accent strip along the top, clipped to the rounded panel
        g.setClip(panel);
        g.setPaint(accent());
        g.fillRect(0, 0, SIZE, 34);
        g.setClip(null);

        // three form rows: accent label chip + field bar
        row(g, 148, 240, 0.92f, true);
        row(g, 246, 176, 0.62f, false);
        row(g, 344, 208, 0.62f, false);

        g.dispose();

        File parent = out.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create output directory: " + parent);
        }
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("No PNG writer available");
        }
        System.out.println("Wrote " + out.getAbsolutePath() + " (" + SIZE + "x" + SIZE + ")");
    }

    private static void row(Graphics2D g, int y, int barWidth, float barAlpha, boolean bright) {
        int arc = 2 * ROW_RADIUS;
        g.setPaint(accent());
        g.fill(new RoundRectangle2D.Float(88, y, 80, 64, arc, arc));

        Color base = bright ? new Color(0xe2e8f0) : new Color(0xcbd5e1);
        g.setPaint(new Color(base.getRed(), base.getGreen(), base.getBlue(), Math.round(barAlpha * 255)));
        g.fill(new RoundRectangle2D.Float(184, y, barWidth, 64, arc, arc));
    }

    /** Horizontal purple-to-blue accent, matching the `accent` gradient in the SVG. */
    private static Paint accent() {
        return new LinearGradientPaint(
                new Point2D.Float(0, 0), new Point2D.Float(SIZE, 0),
                new float[] {0f, 1f}, new Color[] {ACCENT_FROM, ACCENT_TO});
    }
}
