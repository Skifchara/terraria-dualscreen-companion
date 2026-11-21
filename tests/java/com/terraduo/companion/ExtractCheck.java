package com.terraduo.companion;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Runs the on-device sprite extraction on a PC and compares every PNG with the ones the Python tools produced.
 *
 *   javac -d out <the unity classes> tests/java/.../ExtractCheck.java
 *   java -cp out com.terraduo.companion.ExtractCheck <installed base.apk> <output dir> <reference atlas dir> [reference backgrounds dir]
 */
public final class ExtractCheck {
    public static void main(String[] args) throws Exception {
        File apk = new File(args[0]);
        File out = new File(args[1]);
        File ref = new File(args[2]);
        File refBackgrounds = args.length > 3 ? new File(args[3]) : null;
        List<File> apks = new ArrayList<>();
        apks.add(apk);
        long start = System.currentTimeMillis();
        final int[] last = {-1};
        SpriteExtractor.run(apks, out, new File(out, "_tmp"), new SpriteExtractor.Listener() {
            @Override public void progress(int done, int total, String what) {
                int percent = total == 0 ? 0 : done * 100 / total;
                if (percent / 10 != last[0]) {
                    last[0] = percent / 10;
                    System.out.println(percent + "%  " + what);
                }
            }

            @Override public boolean cancelled() {
                return false;
            }
        });
        System.out.println("extracted in " + (System.currentTimeMillis() - start) / 1000.0 + " s");
        int checked = 0;
        List<String> problems = new ArrayList<>();
        checked += compare(ref, out, problems);
        if (refBackgrounds != null) checked += compare(refBackgrounds, new File(out, "backgrounds"), problems);
        Collections.sort(problems);
        for (String p : problems.subList(0, Math.min(problems.size(), 40))) System.out.println("DIFF " + p);
        System.out.println("compared " + checked + " files, " + problems.size() + " differ");
        System.exit(problems.isEmpty() ? 0 : 1);
    }

    private static int compare(File reference, File produced, List<String> problems) throws Exception {
        File[] files = reference.listFiles((d, n) -> n.endsWith(".png"));
        int count = 0;
        if (files == null) return 0;
        for (File r : files) {
            count++;
            File p = new File(produced, r.getName());
            if (!p.isFile()) {
                problems.add(r.getName() + " missing");
                continue;
            }
            BufferedImage a = ImageIO.read(r);
            BufferedImage b = ImageIO.read(p);
            if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
                problems.add(r.getName() + " size " + a.getWidth() + "x" + a.getHeight() + " vs " + b.getWidth() + "x" + b.getHeight());
                continue;
            }
            long wrong = 0;
            int first = -1;
            for (int y = 0; y < a.getHeight(); y++) {
                for (int x = 0; x < a.getWidth(); x++) {
                    int pa = a.getRGB(x, y);
                    int pb = b.getRGB(x, y);
                    boolean same = (pa >>> 24) == 0 && (pb >>> 24) == 0 || pa == pb;
                    if (!same) {
                        wrong++;
                        if (first < 0) first = y * a.getWidth() + x;
                    }
                }
            }
            if (wrong > 0) problems.add(r.getName() + " " + wrong + " pixels differ (first at " + first % a.getWidth() + "," + first / a.getWidth() + ")");
        }
        return count;
    }
}
