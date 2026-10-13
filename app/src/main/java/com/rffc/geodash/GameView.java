package com.rffc.geodash;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.rffc.geodash.game.Level;
import com.rffc.geodash.game.Levels;
import com.rffc.geodash.game.World;

import java.util.concurrent.ConcurrentLinkedQueue;

/** Game loop, input handling and all rendering. */
public final class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable {
    private static final float STEP = 1f / 240f;
    private static final float DEATH_DELAY = 0.9f;

    private enum State { MENU, PLAYING, DYING, PAUSED, COMPLETE }

    private final Level[] levels = Levels.all();
    private final SharedPreferences prefs;
    private final Music music = new Music();

    // Threading / lifecycle.
    private Thread thread;
    private volatile boolean running;
    private volatile boolean surfaceReady;
    private volatile boolean resumed;

    // Input (written on UI thread, read on game thread).
    private volatile boolean touching;
    private volatile int pressCounter;
    private final ConcurrentLinkedQueue<float[]> taps = new ConcurrentLinkedQueue<>();
    private volatile boolean backRequested;
    private int handledPresses;
    private boolean ignoreHold;

    // Game state (game thread only).
    private volatile State state = State.MENU;
    private int selected;
    private World world;
    private int attempts;
    private float accumulator;
    private float stateTime;
    private float time;
    private float camY;
    private boolean soundOn;

    // Layout.
    private int w, h;
    private float bp, groundY, camX;

    // Paints and reusable objects.
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    // Buttons.
    private final RectF btnPause = new RectF();
    private final RectF btnLeft = new RectF();
    private final RectF btnRight = new RectF();
    private final RectF btnPlay = new RectF();
    private final RectF btnCard = new RectF();
    private final RectF btnSound = new RectF();
    private final RectF btnA = new RectF();
    private final RectF btnB = new RectF();
    private final RectF btnC = new RectF();

    // Particles in world coordinates.
    private static final int MAX_P = 600;
    private final float[] px = new float[MAX_P], py = new float[MAX_P];
    private final float[] pvx = new float[MAX_P], pvy = new float[MAX_P];
    private final float[] plife = new float[MAX_P], pmax = new float[MAX_P], psize = new float[MAX_P];
    private final int[] pcolor = new int[MAX_P];
    private int pNext;
    private final java.util.Random rnd = new java.util.Random();

    private static final int PLAYER_MAIN = 0xFF7CFF3B;
    private static final int PLAYER_SECOND = 0xFF3BE8FF;
    private static final int ORB_COLOR = 0xFFFFE135;
    private static final int PORTAL_SHIP = 0xFFFF4FD8;
    private static final int PORTAL_CUBE = 0xFF4FFF7A;

    public GameView(Context context) {
        super(context);
        getHolder().addCallback(this);
        setFocusable(true);
        prefs = context.getSharedPreferences("geometry_rush", Context.MODE_PRIVATE);
        soundOn = prefs.getBoolean("sound", true);
        selected = prefs.getInt("selected", 0);
        if (selected >= levels.length) selected = 0;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        text.setTextAlign(Paint.Align.CENTER);
    }

    // ------------------------------------------------------------------ lifecycle

    public void onResumeActivity() {
        resumed = true;
        startLoop();
    }

    public void onPauseActivity() {
        resumed = false;
        stopLoop();
        if (state == State.PLAYING || state == State.DYING) state = State.PAUSED;
    }

    /** @return true if the back press was consumed. */
    public boolean onBack() {
        if (state == State.MENU) return false;
        backRequested = true;
        return true;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        startLoop();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        stopLoop();
    }

    private synchronized void startLoop() {
        if (running || !surfaceReady || !resumed) return;
        running = true;
        if (soundOn) music.start();
        if (state == State.MENU) playMenuMusic();
        thread = new Thread(this, "game");
        thread.start();
    }

    private synchronized void stopLoop() {
        if (!running) return;
        running = false;
        if (thread != null) {
            try {
                thread.join(1000);
            } catch (InterruptedException ignored) {
            }
            thread = null;
        }
        music.release();
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                touching = true;
                pressCounter++;
                taps.add(new float[]{e.getX(i), e.getY(i)});
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:
                if (e.getPointerCount() <= 1) touching = false;
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                return true;
            default:
                return true;
        }
    }

    // ------------------------------------------------------------------ loop

    @Override
    public void run() {
        long last = System.nanoTime();
        while (running) {
            long now = System.nanoTime();
            float dt = Math.min(0.05f, (now - last) / 1e9f);
            last = now;
            update(dt);
            SurfaceHolder holder = getHolder();
            Canvas c = null;
            try {
                c = holder.getSurface().isValid() ? holder.lockHardwareCanvas() : null;
                if (c != null) render(c);
            } catch (Exception ignored) {
            } finally {
                if (c != null) {
                    try {
                        holder.unlockCanvasAndPost(c);
                    } catch (Exception ignored) {
                    }
                }
            }
            if (c == null) {
                try {
                    Thread.sleep(16);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private void update(float dt) {
        time += dt;
        stateTime += dt;
        if (!touching) ignoreHold = false;

        if (backRequested) {
            backRequested = false;
            switch (state) {
                case PLAYING:
                case DYING:
                    setState(State.PAUSED);
                    music.stop();
                    break;
                case PAUSED:
                case COMPLETE:
                    goToMenu();
                    break;
                default:
                    break;
            }
        }

        float[] tap;
        while ((tap = taps.poll()) != null) handleTap(tap[0], tap[1]);

        if (state == State.PLAYING) {
            boolean newPress = pressCounter != handledPresses;
            handledPresses = pressCounter;
            boolean holding = touching && !ignoreHold;
            if (ignoreHold) newPress = false;
            accumulator += dt;
            while (accumulator >= STEP && state == State.PLAYING) {
                accumulator -= STEP;
                world.step(STEP, holding, newPress);
                newPress = false;
                onWorldEvents();
            }
        } else if (state == State.DYING) {
            if (stateTime >= DEATH_DELAY) restartAttempt();
        } else {
            handledPresses = pressCounter;
        }

        updateParticles(dt);
        updateCamera(dt);
    }

    private void onWorldEvents() {
        if (world.evDeath) {
            explode(world.x + 0.5f, world.y + 0.5f, PLAYER_MAIN, 60);
            music.death();
            saveBest();
            setState(State.DYING);
            return;
        }
        if (world.evWin) {
            saveBest();
            music.stop();
            for (int i = 0; i < 4; i++) {
                explode(world.x - 2 + i, 3 + rnd.nextFloat() * 3, i % 2 == 0 ? ORB_COLOR : PLAYER_SECOND, 30);
            }
            setState(State.COMPLETE);
            return;
        }
        if (world.evOrb || world.evPad) {
            ring(world.x + 0.5f, world.y + 0.5f, ORB_COLOR);
        }
        if (world.evPortal) {
            ring(world.x + 0.5f, world.y + 0.5f, world.ship ? PORTAL_SHIP : PORTAL_CUBE);
        }
        // Trails.
        if (world.ship) {
            if (rnd.nextInt(3) == 0) {
                spawn(world.x + 0.05f, world.y + 0.35f, -2 - rnd.nextFloat() * 2, (rnd.nextFloat() - 0.5f),
                        0.35f, 0.18f, 0xFFFFA040);
            }
        } else if (world.onGround && rnd.nextInt(12) == 0) {
            spawn(world.x, world.y + 0.05f, -1 - rnd.nextFloat(), rnd.nextFloat() * 1.5f, 0.3f, 0.1f,
                    lerpColor(currentLevelColor(), Color.WHITE, 0.6f));
        }
    }

    private void saveBest() {
        int pct = world.won ? 100 : (int) (world.progress() * 100);
        SharedPreferences.Editor e = prefs.edit();
        if (pct > prefs.getInt("best_" + selected, 0)) e.putInt("best_" + selected, pct);
        if (!world.won) e.putInt("attempts_total", prefs.getInt("attempts_total", 0) + 1);
        e.apply();
    }

    private void setState(State s) {
        state = s;
        stateTime = 0;
    }

    private void startLevel(int index) {
        selected = index;
        prefs.edit().putInt("selected", index).apply();
        world = new World(levels[index]);
        attempts = 1;
        accumulator = 0;
        camY = 0;
        clearParticles();
        ignoreHold = true;
        handledPresses = pressCounter;
        if (soundOn) music.play(levels[index].song);
        setState(State.PLAYING);
    }

    private void restartAttempt() {
        world.reset();
        attempts++;
        accumulator = 0;
        camY = 0;
        clearParticles();
        handledPresses = pressCounter;
        if (soundOn) music.play(levels[selected].song);
        setState(State.PLAYING);
    }

    private void goToMenu() {
        world = null;
        clearParticles();
        playMenuMusic();
        setState(State.MENU);
    }

    private void playMenuMusic() {
        if (soundOn) music.playMenu();
    }

    private void handleTap(float x, float y) {
        switch (state) {
            case MENU:
                if (btnLeft.contains(x, y)) {
                    selected = (selected + levels.length - 1) % levels.length;
                } else if (btnRight.contains(x, y)) {
                    selected = (selected + 1) % levels.length;
                } else if (btnSound.contains(x, y)) {
                    soundOn = !soundOn;
                    prefs.edit().putBoolean("sound", soundOn).apply();
                    if (soundOn) {
                        music.start();
                        playMenuMusic();
                    } else {
                        music.release();
                    }
                } else if (btnPlay.contains(x, y) || btnCard.contains(x, y)) {
                    startLevel(selected);
                }
                break;
            case PLAYING:
            case DYING:
                if (btnPause.contains(x, y)) {
                    setState(State.PAUSED);
                    music.stop();
                    handledPresses = pressCounter;
                    ignoreHold = true;
                }
                break;
            case PAUSED:
                if (btnA.contains(x, y)) {
                    ignoreHold = true;
                    handledPresses = pressCounter;
                    if (world.dead) {
                        restartAttempt();
                    } else {
                        if (soundOn) music.playFrom(levels[selected].song, world.x / world.level.speed);
                        setState(State.PLAYING);
                    }
                } else if (btnB.contains(x, y)) {
                    ignoreHold = true;
                    restartAttempt();
                } else if (btnC.contains(x, y)) {
                    goToMenu();
                }
                break;
            case COMPLETE:
                if (stateTime < 0.6f) break;
                if (btnA.contains(x, y)) {
                    startLevel(selected);
                } else if (btnB.contains(x, y)) {
                    goToMenu();
                } else if (btnC.contains(x, y) && selected + 1 < levels.length) {
                    startLevel(selected + 1);
                }
                break;
        }
    }

    // ------------------------------------------------------------------ particles

    private void spawn(float x, float y, float vx, float vy, float life, float size, int color) {
        int i = pNext;
        pNext = (pNext + 1) % MAX_P;
        px[i] = x;
        py[i] = y;
        pvx[i] = vx;
        pvy[i] = vy;
        plife[i] = life;
        pmax[i] = life;
        psize[i] = size;
        pcolor[i] = color;
    }

    private void explode(float x, float y, int color, int n) {
        for (int i = 0; i < n; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            float s = 3 + rnd.nextFloat() * 9;
            spawn(x, y, (float) Math.cos(a) * s, (float) Math.sin(a) * s, 0.5f + rnd.nextFloat() * 0.5f,
                    0.12f + rnd.nextFloat() * 0.22f, i % 3 == 0 ? Color.WHITE : color);
        }
    }

    private void ring(float x, float y, int color) {
        for (int i = 0; i < 18; i++) {
            double a = i / 18.0 * Math.PI * 2;
            spawn(x, y, (float) Math.cos(a) * 6, (float) Math.sin(a) * 6, 0.35f, 0.12f, color);
        }
    }

    private void updateParticles(float dt) {
        for (int i = 0; i < MAX_P; i++) {
            if (plife[i] <= 0) continue;
            plife[i] -= dt;
            px[i] += pvx[i] * dt;
            py[i] += pvy[i] * dt;
            pvx[i] *= (1 - dt * 2);
            pvy[i] *= (1 - dt * 2);
        }
    }

    private void clearParticles() {
        for (int i = 0; i < MAX_P; i++) plife[i] = 0;
    }

    private void updateCamera(float dt) {
        if (world == null) return;
        float target = world.ship ? 0f : Math.max(0f, world.y - 4.5f);
        camY += (target - camY) * Math.min(1f, dt * 5f);
    }

    // ------------------------------------------------------------------ rendering

    private int currentLevelColor() {
        Level l = levels[selected];
        float t = world != null ? world.progress() : 0f;
        return lerpColor(l.colorStart, l.colorEnd, t);
    }

    private void layout(int width, int height) {
        w = width;
        h = height;
        bp = h / 10.5f;
        groundY = h - 1.3f * bp;
        float u = Math.min(w, h) / 100f;
        btnPause.set(w - 16 * u, 4 * u, w - 4 * u, 16 * u);
        float cw = Math.min(w * 0.5f, 90 * u), ch = h * 0.34f;
        btnCard.set(w / 2f - cw / 2, h * 0.3f, w / 2f + cw / 2, h * 0.3f + ch);
        float a = 14 * u;
        btnLeft.set(btnCard.left - a - 6 * u, btnCard.centerY() - a / 2, btnCard.left - 6 * u, btnCard.centerY() + a / 2);
        btnRight.set(btnCard.right + 6 * u, btnCard.centerY() - a / 2, btnCard.right + 6 * u + a, btnCard.centerY() + a / 2);
        float pr = 11 * u;
        btnPlay.set(w / 2f - pr, btnCard.bottom + 4 * u, w / 2f + pr, btnCard.bottom + 4 * u + 2 * pr);
        btnSound.set(w - 30 * u, 4 * u, w - 4 * u, 14 * u);
        float bw = Math.min(w * 0.26f, 60 * u), bh = 15 * u, gap = 4 * u;
        float total = bw * 3 + gap * 2;
        float bx = w / 2f - total / 2, by = h * 0.62f;
        btnA.set(bx, by, bx + bw, by + bh);
        btnB.set(bx + bw + gap, by, bx + 2 * bw + gap, by + bh);
        btnC.set(bx + 2 * (bw + gap), by, bx + 3 * bw + 2 * gap, by + bh);
    }

    private void render(Canvas c) {
        if (c.getWidth() != w || c.getHeight() != h) layout(c.getWidth(), c.getHeight());
        if (state == State.MENU || world == null) {
            renderMenu(c);
            return;
        }
        renderWorld(c);
        renderHud(c);
        if (state == State.PAUSED) renderPause(c);
        if (state == State.COMPLETE) renderComplete(c);
    }

    private float sx(float wx) {
        return (wx - camX) * bp;
    }

    private float sy(float wy) {
        return groundY - (wy - camY) * bp;
    }

    private void drawBackground(Canvas c, int color, float scroll) {
        int top = lerpColor(color, Color.BLACK, 0.25f);
        int bottom = lerpColor(color, Color.BLACK, 0.7f);
        fill.setShader(new LinearGradient(0, 0, 0, h, top, bottom, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);

        // Parallax squares.
        float size = bp * 3.2f;
        float off = (scroll * bp * 0.15f) % size;
        stroke.setColor(Color.argb(28, 255, 255, 255));
        stroke.setStrokeWidth(bp * 0.06f);
        fill.setColor(Color.argb(14, 255, 255, 255));
        for (float x = -off - size; x < w + size; x += size) {
            for (float y = 0; y < h; y += size) {
                int ix = (int) Math.floor((x + off) / size + scroll * 0.15f / 3.2f);
                int iy = (int) (y / size);
                if (((ix * 7 + iy * 13) & 3) == 0) {
                    c.drawRect(x + size * 0.08f, y + size * 0.08f, x + size * 0.92f, y + size * 0.92f, fill);
                }
                c.drawRect(x + size * 0.08f, y + size * 0.08f, x + size * 0.92f, y + size * 0.92f, stroke);
            }
        }
    }

    private void drawGround(Canvas c, int color, float scroll, float pulse, float groundTop) {
        int g = lerpColor(color, Color.BLACK, 0.45f);
        fill.setColor(g);
        c.drawRect(0, groundTop, w, h, fill);
        float size = bp * 2f;
        float off = (scroll * bp) % size;
        stroke.setColor(Color.argb(40, 0, 0, 0));
        stroke.setStrokeWidth(bp * 0.05f);
        for (float x = -off; x < w; x += size) {
            c.drawRect(x, groundTop, x + size, groundTop + size, stroke);
        }
        // Glowing edge.
        fill.setShader(new LinearGradient(0, 0, w, 0,
                new int[]{Color.TRANSPARENT, Color.WHITE, Color.TRANSPARENT}, null, Shader.TileMode.CLAMP));
        float t = bp * (0.05f + 0.04f * pulse);
        c.drawRect(0, groundTop - t, w, groundTop + t, fill);
        fill.setShader(null);
    }

    private void renderWorld(Canvas c) {
        Level l = world.level;
        int color = currentLevelColor();
        float pulse = music.beatPulse();
        camX = world.x - (w * 0.28f) / bp;

        drawBackground(c, color, camX);

        int x0 = (int) Math.floor(camX) - 2;
        int x1 = (int) Math.ceil(camX + w / bp) + 2;

        // Ship mode ceiling.
        if (world.ship) {
            float ceil = sy(World.SHIP_CEILING);
            if (ceil > 0) {
                fill.setColor(lerpColor(color, Color.BLACK, 0.45f));
                c.drawRect(0, 0, w, ceil, fill);
                fill.setColor(Color.WHITE);
                c.drawRect(0, ceil - bp * 0.05f, w, ceil + bp * 0.05f, fill);
            }
        }

        // Attempt counter written into the level.
        text.setColor(Color.argb(220, 255, 255, 255));
        text.setTextSize(bp * 0.9f);
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText("Попытка " + attempts, sx(4), sy(4.2f), text);
        text.setTextAlign(Paint.Align.CENTER);

        for (int bx = x0; bx <= x1; bx++) {
            for (int by = 0; by < l.height; by++) {
                char t = l.at(bx, by);
                if (t == Level.EMPTY) continue;
                float left = sx(bx), right = sx(bx + 1), top = sy(by + 1), bottom = sy(by);
                switch (t) {
                    case Level.BLOCK:
                        drawBlock(c, l, bx, by, left, top, right, bottom, color);
                        break;
                    case Level.SPIKE:
                        drawSpike(c, left, top, right, bottom, false);
                        break;
                    case Level.SPIKE_DOWN:
                        drawSpike(c, left, top, right, bottom, true);
                        break;
                    case Level.ORB:
                        drawOrb(c, (left + right) / 2, (top + bottom) / 2, pulse);
                        break;
                    case Level.PAD:
                        drawPad(c, left, right, bottom);
                        break;
                    default:
                        break;
                }
            }
        }
        for (int i = 0; i < l.portalX.length; i++) {
            int bx = l.portalX[i];
            if (bx < x0 || bx > x1) continue;
            float cx = sx(bx + 0.5f), cy = sy(l.portalY[i] - 0.5f);
            drawPortal(c, cx, cy, l.portalType[i] == Level.PORTAL_SHIP ? PORTAL_SHIP : PORTAL_CUBE);
        }

        // Finish line.
        float fx = sx(l.width + 1);
        if (fx < w + bp) {
            fill.setShader(new LinearGradient(fx - bp * 2, 0, fx + bp * 0.2f, 0,
                    Color.TRANSPARENT, Color.argb(200, 255, 255, 255), Shader.TileMode.CLAMP));
            c.drawRect(fx - bp * 2, 0, fx + bp * 0.2f, h, fill);
            fill.setShader(null);
            fill.setColor(Color.WHITE);
            c.drawRect(fx + bp * 0.2f, 0, w, h, fill);
        }

        drawGround(c, color, camX, pulse, sy(0));
        drawParticles(c);

        if (!world.dead) {
            float cx = sx(world.x + 0.5f), cy = sy(world.y + 0.5f);
            if (world.ship) drawShip(c, cx, cy, world.rotation);
            else drawCube(c, cx, cy, bp * 0.98f, world.rotation);
        } else if (state == State.DYING) {
            float t = stateTime / DEATH_DELAY;
            stroke.setColor(Color.argb((int) (255 * (1 - t)), 255, 255, 255));
            stroke.setStrokeWidth(bp * 0.15f * (1 - t));
            c.drawCircle(sx(world.x + 0.5f), sy(world.y + 0.5f), bp * (0.5f + t * 3f), stroke);
        }
    }

    private void drawBlock(Canvas c, Level l, int bx, int by, float left, float top, float right,
                           float bottom, int color) {
        fill.setColor(Color.argb(235, 10, 10, 18));
        c.drawRect(left, top, right, bottom, fill);
        fill.setShader(new LinearGradient(0, top, 0, bottom,
                Color.argb(90, Color.red(color), Color.green(color), Color.blue(color)),
                Color.TRANSPARENT, Shader.TileMode.CLAMP));
        c.drawRect(left, top, right, bottom, fill);
        fill.setShader(null);
        stroke.setColor(Color.WHITE);
        stroke.setStrokeWidth(bp * 0.06f);
        float s = bp * 0.03f;
        // Outline only on exposed edges so platforms read as one shape.
        if (l.at(bx, by + 1) != Level.BLOCK) c.drawLine(left, top + s, right, top + s, stroke);
        if (l.at(bx, by - 1) != Level.BLOCK && by > 0) c.drawLine(left, bottom - s, right, bottom - s, stroke);
        if (l.at(bx - 1, by) != Level.BLOCK) c.drawLine(left + s, top, left + s, bottom, stroke);
        if (l.at(bx + 1, by) != Level.BLOCK) c.drawLine(right - s, top, right - s, bottom, stroke);
        stroke.setColor(Color.argb(60, 255, 255, 255));
        stroke.setStrokeWidth(bp * 0.03f);
        float in = bp * 0.25f;
        c.drawRect(left + in, top + in, right - in, bottom - in, stroke);
    }

    private void drawSpike(Canvas c, float left, float top, float right, float bottom, boolean down) {
        float inset = (right - left) * 0.06f;
        path.reset();
        if (!down) {
            path.moveTo(left + inset, bottom);
            path.lineTo((left + right) / 2, top + inset);
            path.lineTo(right - inset, bottom);
        } else {
            path.moveTo(left + inset, top);
            path.lineTo((left + right) / 2, bottom - inset);
            path.lineTo(right - inset, top);
        }
        path.close();
        fill.setColor(Color.argb(240, 8, 8, 14));
        c.drawPath(path, fill);
        stroke.setColor(Color.WHITE);
        stroke.setStrokeWidth(bp * 0.06f);
        c.drawPath(path, stroke);
    }

    private void drawOrb(Canvas c, float cx, float cy, float pulse) {
        stroke.setColor(Color.argb(160, 255, 225, 53));
        stroke.setStrokeWidth(bp * 0.07f);
        c.drawCircle(cx, cy, bp * (0.46f + 0.08f * pulse), stroke);
        fill.setColor(ORB_COLOR);
        c.drawCircle(cx, cy, bp * 0.3f, fill);
        fill.setColor(Color.argb(170, 255, 255, 255));
        c.drawCircle(cx - bp * 0.08f, cy - bp * 0.08f, bp * 0.1f, fill);
    }

    private void drawPad(Canvas c, float left, float right, float bottom) {
        rect.set(left + bp * 0.08f, bottom - bp * 0.3f, right - bp * 0.08f, bottom + bp * 0.3f);
        fill.setColor(ORB_COLOR);
        c.drawArc(rect, 180, 180, true, fill);
        fill.setColor(Color.argb(120, 255, 255, 255));
        rect.inset(bp * 0.15f, bp * 0.12f);
        c.drawArc(rect, 180, 180, true, fill);
    }

    private void drawPortal(Canvas c, float cx, float cy, int color) {
        float pw = bp * 0.55f, ph = bp * 1.5f;
        rect.set(cx - pw, cy - ph, cx + pw, cy + ph);
        stroke.setColor(Color.argb(90, Color.red(color), Color.green(color), Color.blue(color)));
        stroke.setStrokeWidth(bp * 0.35f);
        c.drawOval(rect, stroke);
        stroke.setColor(color);
        stroke.setStrokeWidth(bp * 0.14f);
        c.drawOval(rect, stroke);
        stroke.setColor(Color.WHITE);
        stroke.setStrokeWidth(bp * 0.04f);
        c.drawOval(rect, stroke);
    }

    private void drawCube(Canvas c, float cx, float cy, float size, float rotation) {
        c.save();
        c.translate(cx, cy);
        c.rotate(rotation);
        float s = size / 2;
        fill.setColor(PLAYER_MAIN);
        c.drawRect(-s, -s, s, s, fill);
        stroke.setColor(Color.BLACK);
        stroke.setStrokeWidth(size * 0.08f);
        c.drawRect(-s, -s, s, s, stroke);
        float i = s * 0.5f;
        fill.setColor(PLAYER_SECOND);
        c.drawRect(-i, -i, i, i, fill);
        c.drawRect(-i, -i, i, i, stroke);
        fill.setColor(Color.BLACK);
        c.drawRect(-i * 0.65f, -i * 0.55f, -i * 0.2f, -i * 0.1f, fill);
        c.drawRect(i * 0.2f, -i * 0.55f, i * 0.65f, -i * 0.1f, fill);
        c.restore();
    }

    private void drawShip(Canvas c, float cx, float cy, float rotation) {
        c.save();
        c.translate(cx, cy);
        c.rotate(rotation);
        // Little cube rider.
        drawCube(c, -bp * 0.05f, -bp * 0.28f, bp * 0.45f, 0);
        path.reset();
        path.moveTo(-bp * 0.55f, -bp * 0.05f);
        path.lineTo(bp * 0.55f, bp * 0.05f);
        path.lineTo(bp * 0.25f, bp * 0.4f);
        path.lineTo(-bp * 0.5f, bp * 0.4f);
        path.close();
        fill.setColor(PORTAL_SHIP);
        c.drawPath(path, fill);
        stroke.setColor(Color.BLACK);
        stroke.setStrokeWidth(bp * 0.07f);
        c.drawPath(path, stroke);
        fill.setColor(PLAYER_SECOND);
        c.drawRect(-bp * 0.35f, bp * 0.12f, bp * 0.1f, bp * 0.28f, fill);
        c.restore();
    }

    private void drawParticles(Canvas c) {
        for (int i = 0; i < MAX_P; i++) {
            if (plife[i] <= 0) continue;
            float a = plife[i] / pmax[i];
            int col = pcolor[i];
            fill.setColor(Color.argb((int) (255 * a), Color.red(col), Color.green(col), Color.blue(col)));
            float s = psize[i] * bp * (0.5f + 0.5f * a);
            float x = sx(px[i]), y = sy(py[i]);
            c.drawRect(x - s, y - s, x + s, y + s, fill);
        }
    }

    private void renderHud(Canvas c) {
        float u = Math.min(w, h) / 100f;
        // Progress bar.
        float bw = w * 0.4f, bh = 2.6f * u;
        float bx = w / 2f - bw / 2, by = 5 * u;
        rect.set(bx, by, bx + bw, by + bh);
        fill.setColor(Color.argb(120, 0, 0, 0));
        c.drawRoundRect(rect, bh / 2, bh / 2, fill);
        float p = world.progress();
        rect.set(bx, by, bx + bw * p, by + bh);
        fill.setColor(PLAYER_MAIN);
        c.drawRoundRect(rect, bh / 2, bh / 2, fill);
        rect.set(bx, by, bx + bw, by + bh);
        stroke.setColor(Color.WHITE);
        stroke.setStrokeWidth(0.4f * u);
        c.drawRoundRect(rect, bh / 2, bh / 2, stroke);
        drawLabel(c, (int) (p * 100) + "%", bx + bw + 7 * u, by + bh, 4.5f * u, Color.WHITE);

        // Pause button.
        if (state == State.PLAYING || state == State.DYING) {
            fill.setColor(Color.argb(150, 255, 255, 255));
            float cx = btnPause.centerX(), cy = btnPause.centerY(), s = btnPause.width() * 0.22f;
            c.drawRect(cx - s * 1.1f, cy - s * 1.4f, cx - s * 0.35f, cy + s * 1.4f, fill);
            c.drawRect(cx + s * 0.35f, cy - s * 1.4f, cx + s * 1.1f, cy + s * 1.4f, fill);
        }
    }

    private void dim(Canvas c, int alpha) {
        fill.setColor(Color.argb(alpha, 0, 0, 0));
        c.drawRect(0, 0, w, h, fill);
    }

    private void renderPause(Canvas c) {
        float u = Math.min(w, h) / 100f;
        dim(c, 150);
        drawLabel(c, "ПАУЗА", w / 2f, h * 0.3f, 12 * u, Color.WHITE);
        int best = prefs.getInt("best_" + selected, 0);
        drawLabel(c, levels[selected].name + "  •  Рекорд: " + best + "%", w / 2f, h * 0.45f, 5 * u,
                Color.argb(220, 255, 255, 255));
        drawButton(c, btnA, "Продолжить", PLAYER_MAIN);
        drawButton(c, btnB, "Заново", ORB_COLOR);
        drawButton(c, btnC, "Меню", PLAYER_SECOND);
    }

    private void renderComplete(Canvas c) {
        float u = Math.min(w, h) / 100f;
        float t = Math.min(1f, stateTime / 0.5f);
        dim(c, (int) (160 * t));
        float scale = 0.6f + 0.4f * easeOutBack(t);
        drawLabel(c, "УРОВЕНЬ ПРОЙДЕН!", w / 2f, h * 0.3f, 11 * u * scale, ORB_COLOR);
        drawLabel(c, "Попыток: " + attempts + "   Прыжков: " + world.jumps, w / 2f, h * 0.45f, 5.5f * u,
                Color.WHITE);
        if (t >= 1f) {
            drawButton(c, btnA, "Заново", ORB_COLOR);
            drawButton(c, btnB, "Меню", PLAYER_SECOND);
            if (selected + 1 < levels.length) drawButton(c, btnC, "Дальше ▶", PLAYER_MAIN);
        }
        // Celebration sparks.
        if (rnd.nextInt(4) == 0) {
            float wx = camX + rnd.nextFloat() * w / bp;
            float wy = camY + 2 + rnd.nextFloat() * 6;
            explode(wx, wy, rnd.nextBoolean() ? ORB_COLOR : PORTAL_SHIP, 12);
        }
    }

    private void renderMenu(Canvas c) {
        float u = Math.min(w, h) / 100f;
        Level l = levels[selected];
        int color = lerpColor(l.colorStart, l.colorEnd, 0.5f + 0.5f * (float) Math.sin(time * 0.4));
        float scroll = time * l.speed;
        drawBackground(c, color, scroll);
        float ground = h - 1.3f * bp;
        drawGround(c, color, scroll, music.beatPulse(), ground);

        // Demo runner that hops over passing spikes.
        float period = 0.9f, air = 0.48f;
        float cubeX = w * 0.18f;
        float ph = time % period;
        float jy = 0;
        float rot = 0;
        if (ph < air) {
            float k = ph / air;
            jy = 4 * k * (1 - k) * bp * 2.1f;
            rot = k * 180f;
        }
        long n0 = (long) Math.floor((time - 0.24f) / period) - 1;
        float speedPx = l.speed * bp;
        for (long n = n0; n < n0 + 40; n++) {
            float sxp = cubeX + (0.24f + n * period - time) * speedPx;
            if (sxp > w + bp) break;
            if (sxp < -bp || n % 3 == 1) continue;
            drawSpike(c, sxp - bp / 2, ground - bp, sxp + bp / 2, ground, false);
        }
        drawCube(c, cubeX, ground - bp / 2 - jy, bp * 0.98f, rot);

        // Title.
        float bounce = 1f + 0.03f * music.beatPulse();
        drawLabel(c, "GEOMETRY RUSH", w / 2f, h * 0.2f, 13 * u * bounce, Color.WHITE);

        // Level card.
        fill.setColor(Color.argb(150, 0, 0, 0));
        c.drawRoundRect(btnCard, 4 * u, 4 * u, fill);
        stroke.setColor(Color.WHITE);
        stroke.setStrokeWidth(0.6f * u);
        c.drawRoundRect(btnCard, 4 * u, 4 * u, stroke);
        float cx = btnCard.centerX();
        drawLabel(c, l.name, cx, btnCard.top + btnCard.height() * 0.3f, 7 * u, Color.WHITE);
        for (int i = 0; i < 3; i++) {
            drawStar(c, cx + (i - 1) * 7 * u, btnCard.top + btnCard.height() * 0.5f, 2.6f * u,
                    i < l.stars ? ORB_COLOR : Color.argb(70, 255, 255, 255));
        }
        int best = prefs.getInt("best_" + selected, 0);
        float barW = btnCard.width() * 0.7f, barH = 3 * u;
        float bx = cx - barW / 2, by = btnCard.top + btnCard.height() * 0.68f;
        rect.set(bx, by, bx + barW, by + barH);
        fill.setColor(Color.argb(120, 255, 255, 255));
        c.drawRoundRect(rect, barH / 2, barH / 2, fill);
        rect.set(bx, by, bx + barW * best / 100f, by + barH);
        fill.setColor(best >= 100 ? ORB_COLOR : PLAYER_MAIN);
        c.drawRoundRect(rect, barH / 2, barH / 2, fill);
        drawLabel(c, best >= 100 ? "Пройден! 100%" : "Рекорд: " + best + "%", cx,
                by + barH + 6 * u, 4.2f * u, Color.WHITE);
        drawLabel(c, (selected + 1) + " / " + levels.length, cx, btnCard.top + 6 * u, 3.5f * u,
                Color.argb(160, 255, 255, 255));

        drawArrow(c, btnLeft, true);
        drawArrow(c, btnRight, false);

        // Play button.
        float pr = btnPlay.width() / 2 * (1f + 0.05f * music.beatPulse());
        fill.setColor(PLAYER_MAIN);
        c.drawCircle(btnPlay.centerX(), btnPlay.centerY(), pr, fill);
        stroke.setColor(Color.BLACK);
        stroke.setStrokeWidth(0.8f * u);
        c.drawCircle(btnPlay.centerX(), btnPlay.centerY(), pr, stroke);
        path.reset();
        float tri = pr * 0.45f;
        path.moveTo(btnPlay.centerX() - tri * 0.7f, btnPlay.centerY() - tri);
        path.lineTo(btnPlay.centerX() + tri, btnPlay.centerY());
        path.lineTo(btnPlay.centerX() - tri * 0.7f, btnPlay.centerY() + tri);
        path.close();
        fill.setColor(Color.WHITE);
        c.drawPath(path, fill);
        c.drawPath(path, stroke);

        // Sound toggle.
        fill.setColor(Color.argb(120, 0, 0, 0));
        c.drawRoundRect(btnSound, 2 * u, 2 * u, fill);
        drawLabel(c, soundOn ? "Звук: вкл" : "Звук: выкл", btnSound.centerX(), btnSound.centerY() + 1.4f * u,
                3.8f * u, Color.WHITE);

        int total = prefs.getInt("attempts_total", 0);
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(3.5f * u);
        text.setColor(Color.argb(170, 255, 255, 255));
        c.drawText("Всего попыток: " + total, 4 * u, 9 * u, text);
        text.setTextAlign(Paint.Align.CENTER);
    }

    private void drawArrow(Canvas c, RectF r, boolean left) {
        fill.setColor(Color.argb(150, 0, 0, 0));
        c.drawCircle(r.centerX(), r.centerY(), r.width() / 2, fill);
        path.reset();
        float s = r.width() * 0.22f;
        float d = left ? -1 : 1;
        path.moveTo(r.centerX() - d * s * 0.6f, r.centerY() - s);
        path.lineTo(r.centerX() + d * s * 0.8f, r.centerY());
        path.lineTo(r.centerX() - d * s * 0.6f, r.centerY() + s);
        stroke.setColor(Color.WHITE);
        stroke.setStrokeWidth(r.width() * 0.08f);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        c.drawPath(path, stroke);
        stroke.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawStar(Canvas c, float cx, float cy, float r, int color) {
        path.reset();
        for (int i = 0; i < 10; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 5;
            float rr = i % 2 == 0 ? r : r * 0.45f;
            float x = cx + (float) Math.cos(a) * rr, y = cy + (float) Math.sin(a) * rr;
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        path.close();
        fill.setColor(color);
        c.drawPath(path, fill);
    }

    private void drawButton(Canvas c, RectF r, String label, int color) {
        float rad = r.height() * 0.3f;
        fill.setColor(color);
        c.drawRoundRect(r, rad, rad, fill);
        stroke.setColor(Color.BLACK);
        stroke.setStrokeWidth(r.height() * 0.06f);
        c.drawRoundRect(r, rad, rad, stroke);
        text.setTextSize(r.height() * 0.38f);
        text.setColor(Color.BLACK);
        c.drawText(label, r.centerX(), r.centerY() + r.height() * 0.13f, text);
    }

    /** Text with a dark outline for readability on any background. */
    private void drawLabel(Canvas c, String s, float x, float y, float size, int color) {
        text.setTextSize(size);
        text.setStyle(Paint.Style.STROKE);
        text.setStrokeWidth(size * 0.14f);
        text.setColor(Color.argb(200, 0, 0, 0));
        c.drawText(s, x, y, text);
        text.setStyle(Paint.Style.FILL);
        text.setColor(color);
        c.drawText(s, x, y, text);
    }

    private static float easeOutBack(float t) {
        float c1 = 1.70158f, c3 = c1 + 1;
        return 1 + c3 * (float) Math.pow(t - 1, 3) + c1 * (float) Math.pow(t - 1, 2);
    }

    private static int lerpColor(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return Color.argb(
                (int) (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }
}
