import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;

/**
 * The visual check of one case: what Vultrum's culls change in its screenshots. Usage:
 * java tools/VisualCheck.java on1.png off1.png on2.png off2.png out.png
 *
 * <p>Each shot is split into 16-pixel tiles, each tile's difference between two shots being the mean of its pixels'
 * channel differences (0 to 255). A tile's change is the least of its four on-off differences, so a pixel that moves
 * in every shot (waving leaves, water) doesn't count unless the culls change it each time. Its noise is the most that
 * two shots of the same setting differ (on1 and on2, off1 and off2) in it and the 8 tiles around it. A tile fails when
 * its change is over {@value #FLOOR} and over {@value #NOISE_FACTOR} times its noise. Prints one JSON line: failing
 * tiles, their largest 4-connected cluster (tiles, and its box in pixels), whether that cluster is brighter with the
 * culls on (light where there should be shadow), and the verdict: a cluster of {@value #CLUSTER} tiles or more, or
 * over {@value #SHARE} of the tiles failing, fails the case. out.png is on1 dimmed, failing tiles red, noisy ones blue.
 */
public class VisualCheck {
    static final int TILE = 16, CLUSTER = 3;
    static final double FLOOR = 4, NOISE_FACTOR = 2, SHARE = 0.002;

    public static void main(String[] args) throws Exception {
        BufferedImage[] shots = new BufferedImage[4];
        for (int i = 0; i < 4; i++) shots[i] = ImageIO.read(new File(args[i]));
        int w = shots[0].getWidth(), h = shots[0].getHeight();
        for (var shot : shots) {
            if (shot.getWidth() != w || shot.getHeight() != h) {
                System.out.printf("{\"error\":\"sizes differ\"}%n");
                return;
            }
        }
        int tw = w / TILE, th = h / TILE;
        int[][] pixels = new int[4][];
        for (int i = 0; i < 4; i++) pixels[i] = shots[i].getRGB(0, 0, w, h, null, 0, w);
        // on1 = 0, off1 = 1, on2 = 2, off2 = 3.
        double[][] change = new double[th][tw], noise = new double[th][tw], signed = new double[th][tw];
        for (int ty = 0; ty < th; ty++) {
            for (int tx = 0; tx < tw; tx++) {
                double a = diff(pixels, 0, 1, w, tx, ty), b = diff(pixels, 0, 3, w, tx, ty);
                double c = diff(pixels, 2, 1, w, tx, ty), d = diff(pixels, 2, 3, w, tx, ty);
                change[ty][tx] = Math.min(Math.min(a, b), Math.min(c, d));
                noise[ty][tx] = Math.max(diff(pixels, 0, 2, w, tx, ty), diff(pixels, 1, 3, w, tx, ty));
                signed[ty][tx] = brightness(pixels, 0, w, tx, ty) + brightness(pixels, 2, w, tx, ty)
                        - brightness(pixels, 1, w, tx, ty) - brightness(pixels, 3, w, tx, ty);
            }
        }
        boolean[][] fail = new boolean[th][tw];
        boolean[][] noisy = new boolean[th][tw];
        int failing = 0;
        for (int ty = 0; ty < th; ty++) {
            for (int tx = 0; tx < tw; tx++) {
                double around = 0;
                for (int y = Math.max(0, ty - 1); y <= Math.min(th - 1, ty + 1); y++)
                    for (int x = Math.max(0, tx - 1); x <= Math.min(tw - 1, tx + 1); x++) around = Math.max(around, noise[y][x]);
                noisy[ty][tx] = around > FLOOR;
                fail[ty][tx] = change[ty][tx] > FLOOR && change[ty][tx] > NOISE_FACTOR * around;
                if (fail[ty][tx]) failing++;
            }
        }
        // The largest cluster of failing tiles.
        boolean[][] seen = new boolean[th][tw];
        int best = 0, bx0 = 0, by0 = 0, bx1 = 0, by1 = 0;
        double bestSigned = 0;
        var queue = new ArrayDeque<int[]>();
        for (int ty = 0; ty < th; ty++) {
            for (int tx = 0; tx < tw; tx++) {
                if (!fail[ty][tx] || seen[ty][tx]) continue;
                int size = 0, x0 = tx, y0 = ty, x1 = tx, y1 = ty;
                double sum = 0;
                seen[ty][tx] = true;
                queue.add(new int[] {tx, ty});
                while (!queue.isEmpty()) {
                    int[] t = queue.poll();
                    size++;
                    sum += signed[t[1]][t[0]];
                    x0 = Math.min(x0, t[0]);
                    y0 = Math.min(y0, t[1]);
                    x1 = Math.max(x1, t[0]);
                    y1 = Math.max(y1, t[1]);
                    int[][] next = {{t[0] + 1, t[1]}, {t[0] - 1, t[1]}, {t[0], t[1] + 1}, {t[0], t[1] - 1}};
                    for (int[] n : next) {
                        if (n[0] < 0 || n[1] < 0 || n[0] >= tw || n[1] >= th || !fail[n[1]][n[0]] || seen[n[1]][n[0]]) continue;
                        seen[n[1]][n[0]] = true;
                        queue.add(n);
                    }
                }
                if (size > best) {
                    best = size;
                    bestSigned = sum;
                    bx0 = x0;
                    by0 = y0;
                    bx1 = x1;
                    by1 = y1;
                }
            }
        }
        var out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = pixels[0][y * w + x];
                int r = ((p >> 16) & 0xFF) * 2 / 5, g = ((p >> 8) & 0xFF) * 2 / 5, b = (p & 0xFF) * 2 / 5;
                int tx = Math.min(x / TILE, tw - 1), ty = Math.min(y / TILE, th - 1);
                if (fail[ty][tx]) r = Math.min(255, r + 160);
                else if (noisy[ty][tx]) b = Math.min(255, b + 90);
                out.setRGB(x, y, r << 16 | g << 8 | b);
            }
        }
        ImageIO.write(out, "png", new File(args[4]));
        double share = (double) failing / (tw * th);
        boolean pass = best < CLUSTER && share <= SHARE;
        System.out.printf(java.util.Locale.ROOT,
                "{\"verdict\":\"%s\",\"failingTiles\":%d,\"failingPct\":%.3f,\"largestCluster\":%d,\"clusterBox\":[%d,%d,%d,%d],"
                        + "\"clusterBrighterOn\":%s,\"noisyPct\":%.2f}%n",
                pass ? "pass" : "fail", failing, 100 * share, best, bx0 * TILE, by0 * TILE, (bx1 + 1) * TILE, (by1 + 1) * TILE,
                best == 0 ? "null" : Boolean.toString(bestSigned > 0), 100.0 * count(noisy) / (tw * th));
    }

    /** Mean channel difference between two shots over a tile. */
    static double diff(int[][] pixels, int a, int b, int w, int tx, int ty) {
        long sum = 0;
        for (int y = ty * TILE; y < ty * TILE + TILE; y++) {
            for (int x = tx * TILE; x < tx * TILE + TILE; x++) {
                int p = pixels[a][y * w + x], q = pixels[b][y * w + x];
                for (int shift = 0; shift <= 16; shift += 8) sum += Math.abs(((p >> shift) & 0xFF) - ((q >> shift) & 0xFF));
            }
        }
        return sum / (3.0 * TILE * TILE);
    }

    /** Mean channel value of a shot over a tile. */
    static double brightness(int[][] pixels, int a, int w, int tx, int ty) {
        long sum = 0;
        for (int y = ty * TILE; y < ty * TILE + TILE; y++) {
            for (int x = tx * TILE; x < tx * TILE + TILE; x++) {
                int p = pixels[a][y * w + x];
                for (int shift = 0; shift <= 16; shift += 8) sum += (p >> shift) & 0xFF;
            }
        }
        return sum / (3.0 * TILE * TILE);
    }

    static int count(boolean[][] tiles) {
        int n = 0;
        for (boolean[] row : tiles) for (boolean t : row) if (t) n++;
        return n;
    }
}
