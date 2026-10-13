package com.rffc.geodash.game;

/**
 * Player physics and collisions, in block units. Deterministic and free of
 * Android dependencies so levels can be verified by a headless bot.
 */
public final class World {
    public static final float GRAVITY = 85f;
    public static final float JUMP_V = (float) Math.sqrt(2 * GRAVITY * 2.3);
    public static final float ORB_V = (float) Math.sqrt(2 * GRAVITY * 2.5);
    public static final float PAD_V = (float) Math.sqrt(2 * GRAVITY * 5.0);
    public static final float MAX_FALL = 28f;
    public static final float SHIP_ACCEL = 55f;
    public static final float SHIP_MAX_V = 10f;
    public static final float SHIP_CEILING = 9f;
    public static final float END_PADDING = 3f;

    public final Level level;

    public float x;
    public float y;
    public float vy;
    public float rotation;
    public boolean onGround;
    public boolean ship;
    public boolean dead;
    public boolean won;
    public int jumps;

    private boolean pressAvailable;
    private boolean[] used;
    private int nextPortal;

    // One-shot events for effects; cleared at the start of every step.
    public boolean evJump, evOrb, evPad, evPortal, evDeath, evWin;

    public World(Level level) {
        this.level = level;
        reset();
    }

    public void reset() {
        x = 0;
        y = 0;
        vy = 0;
        rotation = 0;
        onGround = true;
        ship = false;
        dead = false;
        won = false;
        jumps = 0;
        pressAvailable = false;
        used = new boolean[level.width * level.height];
        nextPortal = 0;
    }

    public World copy() {
        World w = new World(level);
        w.x = x;
        w.y = y;
        w.vy = vy;
        w.rotation = rotation;
        w.onGround = onGround;
        w.ship = ship;
        w.dead = dead;
        w.won = won;
        w.jumps = jumps;
        w.pressAvailable = pressAvailable;
        w.used = used.clone();
        w.nextPortal = nextPortal;
        return w;
    }

    public float progress() {
        return Math.max(0f, Math.min(1f, x / level.width));
    }

    /**
     * @param holding  whether the screen is currently touched
     * @param newPress whether a new touch started since the previous step
     */
    public void step(float dt, boolean holding, boolean newPress) {
        evJump = evOrb = evPad = evPortal = evDeath = evWin = false;
        if (dead || won) return;

        if (newPress) pressAvailable = true;
        if (!holding) pressAvailable = false;

        float prevY = y;
        x += level.speed * dt;

        if (!ship) {
            if (onGround && holding) {
                vy = JUMP_V;
                onGround = false;
                pressAvailable = false;
                jumps++;
                evJump = true;
            }
            vy -= GRAVITY * dt;
            if (vy < -MAX_FALL) vy = -MAX_FALL;
        } else {
            vy += (holding ? SHIP_ACCEL : -SHIP_ACCEL) * dt;
            if (vy > SHIP_MAX_V) vy = SHIP_MAX_V;
            if (vy < -SHIP_MAX_V) vy = -SHIP_MAX_V;
        }
        y += vy * dt;

        boolean wasOnGround = onGround;
        onGround = false;
        if (y <= 0) {
            y = 0;
            if (vy < 0) vy = 0;
            onGround = true;
        }
        if (ship && y >= SHIP_CEILING - 1) {
            y = SHIP_CEILING - 1;
            if (vy > 0) vy = 0;
        }

        // Portals switch mode when the player's center crosses the portal center.
        while (nextPortal < level.portalX.length && x + 0.5f >= level.portalX[nextPortal] + 0.5f) {
            boolean toShip = level.portalType[nextPortal] == Level.PORTAL_SHIP;
            if (toShip != ship) {
                ship = toShip;
                vy *= 0.5f;
                rotation %= 360f;
                if (rotation > 180f) rotation -= 360f;
                evPortal = true;
            }
            nextPortal++;
        }

        int bx0 = (int) Math.floor(x) - 1;
        int bx1 = (int) Math.floor(x + 1) + 1;
        int by0 = Math.max(0, (int) Math.floor(y) - 1);
        int by1 = (int) Math.floor(y + 1) + 1;

        // Solid blocks first so landing resolves before hazards are checked.
        for (int bx = bx0; bx <= bx1; bx++) {
            for (int by = by0; by <= by1; by++) {
                if (level.at(bx, by) != Level.BLOCK) continue;
                if (!overlap(x, y, 1, 1, bx, by, 1, 1)) continue;
                float top = by + 1;
                if (vy <= 0 && prevY >= top - 0.4f) {
                    y = top;
                    vy = 0;
                    onGround = true;
                } else if (ship && vy >= 0 && prevY + 1 <= by + 0.4f) {
                    y = by - 1;
                    vy = 0;
                } else if (overlap(x + 0.25f, y + 0.25f, 0.5f, 0.5f, bx, by, 1, 1)) {
                    die();
                    return;
                }
            }
        }

        float hx = x + 0.15f, hy = y + 0.15f, hs = 0.7f;
        for (int bx = bx0; bx <= bx1; bx++) {
            for (int by = by0; by <= by1; by++) {
                char c = level.at(bx, by);
                switch (c) {
                    case Level.SPIKE:
                        if (overlap(hx, hy, hs, hs, bx + 0.35f, by, 0.3f, 0.55f)) {
                            die();
                            return;
                        }
                        break;
                    case Level.SPIKE_DOWN:
                        if (overlap(hx, hy, hs, hs, bx + 0.35f, by + 0.45f, 0.3f, 0.55f)) {
                            die();
                            return;
                        }
                        break;
                    case Level.ORB: {
                        int idx = bx * level.height + by;
                        if (pressAvailable && !used[idx]
                                && overlap(x, y, 1, 1, bx - 0.15f, by - 0.15f, 1.3f, 1.3f)) {
                            used[idx] = true;
                            pressAvailable = false;
                            vy = ship ? SHIP_MAX_V : ORB_V;
                            onGround = false;
                            evOrb = true;
                        }
                        break;
                    }
                    case Level.PAD: {
                        int idx = bx * level.height + by;
                        if (!used[idx] && overlap(x, y, 1, 1, bx, by, 1, 0.35f)) {
                            used[idx] = true;
                            vy = ship ? SHIP_MAX_V : PAD_V;
                            onGround = false;
                            evPad = true;
                        }
                        break;
                    }
                    default:
                        break;
                }
            }
        }

        // Cube spins in the air and settles on the nearest quarter turn when grounded.
        if (ship) {
            float target = -vy * 3.5f;
            rotation += (target - rotation) * Math.min(1f, dt * 12f);
        } else if (!onGround) {
            rotation += 400f * dt;
        } else {
            float snap = Math.round(rotation / 90f) * 90f;
            rotation += (snap - rotation) * Math.min(1f, dt * 25f);
            if (!wasOnGround) rotation = snap;
        }

        if (x >= level.width + END_PADDING) {
            won = true;
            evWin = true;
        }
    }

    private void die() {
        dead = true;
        evDeath = true;
    }

    private static boolean overlap(float ax, float ay, float aw, float ah,
                                   float bx, float by, float bw, float bh) {
        final float e = 0.001f;
        return ax < bx + bw - e && ax + aw > bx + e && ay < by + bh - e && ay + ah > by + e;
    }
}
