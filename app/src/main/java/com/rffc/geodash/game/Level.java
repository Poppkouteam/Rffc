package com.rffc.geodash.game;

import java.util.ArrayList;
import java.util.List;

/**
 * Tile map of a level. x grows to the right, y grows upwards, y = 0 is the row
 * standing on the ground. Pure Java so it can be simulated off-device.
 */
public final class Level {
    public static final char EMPTY = '.';
    public static final char BLOCK = '#';
    public static final char SPIKE = '^';
    public static final char SPIKE_DOWN = 'v';
    public static final char ORB = 'o';
    public static final char PAD = '_';
    public static final char PORTAL_SHIP = 'S';
    public static final char PORTAL_CUBE = 'C';

    public final String name;
    public final int stars;
    public final float speed;
    public final int colorStart;
    public final int colorEnd;
    public final int song;
    public final int width;
    public final int height;

    private final char[][] tiles;
    /** Portal x positions and types, sorted by x. */
    public final int[] portalX;
    public final char[] portalType;
    public final int[] portalY;

    private Level(String name, int stars, float speed, int colorStart, int colorEnd, int song,
                  char[][] tiles, int width, int height) {
        this.name = name;
        this.stars = stars;
        this.speed = speed;
        this.colorStart = colorStart;
        this.colorEnd = colorEnd;
        this.song = song;
        this.tiles = tiles;
        this.width = width;
        this.height = height;

        List<int[]> portals = new ArrayList<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                char c = tiles[x][y];
                if (c == PORTAL_SHIP || c == PORTAL_CUBE) portals.add(new int[]{x, y, c});
            }
        }
        portalX = new int[portals.size()];
        portalY = new int[portals.size()];
        portalType = new char[portals.size()];
        for (int i = 0; i < portals.size(); i++) {
            portalX[i] = portals.get(i)[0];
            portalY[i] = portals.get(i)[1];
            portalType[i] = (char) portals.get(i)[2];
        }
    }

    public char at(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) return EMPTY;
        return tiles[x][y];
    }

    /** Builds a level from segments appended left to right, bottom-aligned. */
    public static final class Builder {
        private final String name;
        private final int stars;
        private final float speed;
        private final int colorStart;
        private final int colorEnd;
        private final int song;
        private final List<char[]> columns = new ArrayList<>();
        private int height = 1;

        public Builder(String name, int stars, float speed, int colorStart, int colorEnd, int song) {
            this.name = name;
            this.stars = stars;
            this.speed = speed;
            this.colorStart = colorStart;
            this.colorEnd = colorEnd;
            this.song = song;
        }

        /** Appends a segment. Rows are given top first; the last row is y = 0. */
        public Builder add(String... rows) {
            int w = 0;
            for (String r : rows) w = Math.max(w, r.length());
            height = Math.max(height, rows.length);
            for (int x = 0; x < w; x++) {
                char[] col = new char[rows.length];
                for (int i = 0; i < rows.length; i++) {
                    String r = rows[rows.length - 1 - i];
                    col[i] = x < r.length() ? r.charAt(x) : EMPTY;
                }
                columns.add(col);
            }
            return this;
        }

        public Builder gap(int n) {
            for (int i = 0; i < n; i++) columns.add(new char[]{EMPTY});
            return this;
        }

        /**
         * Ship corridor: pairs of walls from the floor and from the ceiling
         * (ceiling at y = {@link World#SHIP_CEILING}) leaving a gap.
         */
        public Builder shipWalls(int spacing, int thickness, int gapSize, int... gapBottoms) {
            int h = (int) World.SHIP_CEILING;
            height = Math.max(height, h);
            for (int gb : gapBottoms) {
                for (int i = 0; i < spacing; i++) columns.add(new char[]{EMPTY});
                for (int t = 0; t < thickness; t++) {
                    char[] col = new char[h];
                    for (int y = 0; y < h; y++) {
                        col[y] = (y >= gb && y < gb + gapSize) ? EMPTY : BLOCK;
                    }
                    columns.add(col);
                }
            }
            return this;
        }

        public Level build() {
            int w = columns.size();
            char[][] tiles = new char[w][height];
            for (int x = 0; x < w; x++) {
                char[] col = columns.get(x);
                for (int y = 0; y < height; y++) tiles[x][y] = y < col.length ? col[y] : EMPTY;
            }
            return new Level(name, stars, speed, colorStart, colorEnd, song, tiles, w, height);
        }
    }
}
