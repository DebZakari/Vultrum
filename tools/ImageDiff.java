import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Compares two screenshots. Usage: java tools/ImageDiff.java base.png candidate.png diff.png
 * Prints one JSON line. The diff image shows per-pixel difference amplified 4x, and pixels whose
 * largest channel difference exceeds the threshold in red.
 */
public class ImageDiff {
    static final int THRESHOLD = 16;

    public static void main(String[] args) throws Exception {
        BufferedImage a = ImageIO.read(new File(args[0])), b = ImageIO.read(new File(args[1]));
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            System.out.printf("{\"error\":\"size %dx%d vs %dx%d\"}%n", a.getWidth(), a.getHeight(), b.getWidth(), b.getHeight());
            return;
        }
        int w = a.getWidth(), h = a.getHeight();
        var diff = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        long sum = 0, changed = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y), max = 0;
                for (int shift = 0; shift <= 16; shift += 8) {
                    int d = Math.abs(((p >> shift) & 0xFF) - ((q >> shift) & 0xFF));
                    sum += d;
                    max = Math.max(max, d);
                }
                if (max > THRESHOLD) { ++changed; diff.setRGB(x, y, 0xFF0000); }
                else { int g = Math.min(255, max * 4); diff.setRGB(x, y, g << 16 | g << 8 | g); }
            }
        }
        ImageIO.write(diff, "png", new File(args[2]));
        System.out.printf(java.util.Locale.ROOT, "{\"meanAbsDiff\":%.4f,\"changedPct\":%.4f}%n",
                sum / (3.0 * w * h), 100.0 * changed / ((double) w * h));
    }
}
