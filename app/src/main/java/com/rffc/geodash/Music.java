package com.rffc.geodash;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Procedural electronic soundtrack streamed through an AudioTrack. */
public final class Music implements Runnable {
    private static final int SR = 22050;

    private static final class Song {
        final float bpm;
        final int[] roots;      // semitones relative to A, one per bar
        final int[][] chords;   // chord intervals per bar
        final int[] arp;        // arpeggio pattern over 16 steps, index into chord (-1 = rest)

        Song(float bpm, int[] roots, int[][] chords, int[] arp) {
            this.bpm = bpm;
            this.roots = roots;
            this.chords = chords;
            this.arp = arp;
        }
    }

    private static final int[] MINOR = {0, 3, 7, 12};
    private static final int[] MAJOR = {0, 4, 7, 12};

    private static final Song[] SONGS = {
            new Song(128, new int[]{0, -4, 3, -2}, new int[][]{MINOR, MAJOR, MAJOR, MAJOR},
                    new int[]{0, 1, 2, 3, 2, 1, 0, 1, 2, 3, 2, 1, 0, 2, 3, 2}),
            new Song(136, new int[]{-5, 0, -2, 3}, new int[][]{MAJOR, MINOR, MAJOR, MAJOR},
                    new int[]{0, -1, 2, 1, 3, -1, 2, 1, 0, -1, 2, 1, 3, 2, 1, 2}),
            new Song(150, new int[]{0, 0, -4, -2}, new int[][]{MINOR, MINOR, MAJOR, MAJOR},
                    new int[]{3, 2, 1, 0, 3, 2, 1, 0, 2, 3, 2, 1, 0, 1, 2, 3}),
    };

    private AudioTrack track;
    private Thread thread;
    private volatile boolean running;
    private volatile boolean playing;
    private volatile int song;
    private volatile long samplePos;
    private volatile boolean deathTrigger;
    private volatile boolean menuMode;

    private float deathEnv;
    private int noiseState = 0x1234567;
    private double bassPhase, leadPhase, kickPhase;
    private float hatPrev;

    public synchronized void start() {
        if (running) return;
        int min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SR)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(min, 4096))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            track.play();
        } catch (Exception e) {
            track = null;
            return;
        }
        running = true;
        thread = new Thread(this, "music");
        thread.start();
    }

    public synchronized void release() {
        running = false;
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException ignored) {
            }
            thread = null;
        }
        if (track != null) {
            try {
                track.stop();
            } catch (Exception ignored) {
            }
            track.release();
            track = null;
        }
    }

    /** Starts a level song from its beginning. */
    public void play(int songIndex) {
        song = Math.max(0, Math.min(SONGS.length - 1, songIndex));
        menuMode = false;
        samplePos = 0;
        playing = true;
    }

    /** Continues a level song from the given position, e.g. after a pause. */
    public void playFrom(int songIndex, float seconds) {
        song = Math.max(0, Math.min(SONGS.length - 1, songIndex));
        menuMode = false;
        samplePos = (long) (seconds * SR);
        playing = true;
    }

    /** Soft looping menu groove. */
    public void playMenu() {
        song = 0;
        menuMode = true;
        samplePos = 0;
        playing = true;
    }

    public void stop() {
        playing = false;
    }

    public void death() {
        playing = false;
        deathTrigger = true;
    }

    /** 1 right on a beat, decaying towards 0 before the next. */
    public float beatPulse() {
        if (!playing) return 0f;
        double spb = 60.0 / SONGS[song].bpm * SR;
        double phase = (samplePos % spb) / spb;
        return (float) Math.exp(-phase * 5.0);
    }

    @Override
    public void run() {
        short[] buf = new short[512];
        while (running) {
            for (int i = 0; i < buf.length; i++) buf[i] = sample();
            AudioTrack t = track;
            if (t == null) break;
            t.write(buf, 0, buf.length);
        }
    }

    private float noise() {
        noiseState ^= noiseState << 13;
        noiseState ^= noiseState >>> 17;
        noiseState ^= noiseState << 5;
        return (noiseState & 0xFFFF) / 32768f - 1f;
    }

    private static double freq(int semitonesFromA4) {
        return 440.0 * Math.pow(2.0, semitonesFromA4 / 12.0);
    }

    private short sample() {
        float out = 0f;
        if (deathTrigger) {
            deathTrigger = false;
            deathEnv = 1f;
        }
        if (deathEnv > 0.001f) {
            out += noise() * deathEnv * 0.45f;
            deathEnv *= 0.99975f;
        }

        if (playing) {
            Song s = SONGS[song];
            long pos = samplePos++;
            double t = (double) pos / SR;
            double stepLen = 60.0 / s.bpm / 4.0;
            long step = (long) (t / stepLen);
            double st = t - step * stepLen;
            int stepInBar = (int) (step % 16);
            int bar = (int) ((step / 16) % s.roots.length);
            int root = s.roots[bar];
            int[] chord = s.chords[bar];
            boolean intro = !menuMode && step < 32;

            // Kick on every beat.
            if (stepInBar % 4 == 0) {
                double kf = 50 + 110 * Math.exp(-st * 30);
                kickPhase += kf / SR;
                out += (float) (Math.sin(kickPhase * 2 * Math.PI) * Math.exp(-st * 9) * 0.55);
            } else {
                kickPhase = 0;
            }
            if (!menuMode) {
                // Clap on 2 and 4.
                if (stepInBar % 8 == 4) out += noise() * (float) Math.exp(-st * 22) * 0.22f;
                // Off-beat hats (simple high-pass on noise).
                if (stepInBar % 2 == 1) {
                    float n = noise();
                    out += (n - hatPrev) * (float) Math.exp(-st * 70) * 0.06f;
                    hatPrev = n;
                }
            }
            // Bass: saw on eighth notes, octave jumping.
            int bassNote = root - 24 + ((step % 2 == 1) ? 12 : 0);
            bassPhase += freq(bassNote) / SR;
            bassPhase -= Math.floor(bassPhase);
            float saw = (float) (bassPhase * 2 - 1);
            out += saw * (float) Math.exp(-(t % (stepLen * 2)) * 6) * (menuMode ? 0.12f : 0.18f);
            // Lead arpeggio: square wave.
            if (!intro) {
                int ai = s.arp[stepInBar];
                if (ai >= 0) {
                    int note = root + chord[ai % chord.length] + (menuMode ? 0 : 12);
                    leadPhase += freq(note) / SR;
                    leadPhase -= Math.floor(leadPhase);
                    float sq = leadPhase < 0.5 ? 1f : -1f;
                    out += sq * (float) Math.exp(-st * 10) * (menuMode ? 0.05f : 0.08f);
                }
            }
        }

        if (out > 1f) out = 1f;
        if (out < -1f) out = -1f;
        return (short) (out * 30000);
    }
}
