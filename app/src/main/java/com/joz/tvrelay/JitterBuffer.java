package com.joz.tvrelay;

import android.os.SystemClock;

/**
 * Reorders packets and absorbs Wi-Fi jitter, with three tricks aimed at crackle:
 *  - adaptive depth ("Auto"): an underrun grows the target buffer, clean stretches shrink it;
 *    or a fixed target picked by the user;
 *  - clock-drift correction by resampling a packet by +/-1 frame (~0.4%, inaudible),
 *    instead of the hard skips/stalls that sound like clicks; after a Wi-Fi spike leaves a big
 *    backlog it catches up faster, up to 5 frames (~2%), so the extra delay is gone in ~1 s,
 *    or in aggressive mode skips straight back to the target with a short fade;
 *  - concealment that plays the last packet backwards with a fade, so the waveform stays
 *    continuous, then fades back in on recovery.
 */
final class JitterBuffer {
    private static final int SLOTS = 512; // 2.56 s of history, power of two
    private static final int MASK = SLOTS - 1;
    private static final int MAX_TARGET = 80; // 400 ms
    private static final long SHRINK_AFTER_MS = 5000;
    private static final int FADE_FRAMES = 96;
    private static final int N = Protocol.FRAMES_PER_PACKET;
    private static final int C = Protocol.CHANNELS;

    private final short[][] slots = new short[SLOTS][Protocol.SAMPLES_PER_PACKET];
    private final int[] slotSeq = new int[SLOTS];
    private final boolean[] filled = new boolean[SLOTS];
    private final short[] last = new short[Protocol.SAMPLES_PER_PACKET];

    private boolean haveAny;
    private boolean playing;   // false while (re)buffering up to target
    private boolean lastValid;
    private boolean fadeIn;
    private int concealRun;
    private int nextSeq;
    private int highestSeq;
    private final boolean adaptive;
    private final int minTarget;
    private final int maxSpeedup;      // frames per chunk: 1 = ~0.4%, 5 = ~2%
    private final boolean skipAhead;   // aggressive: jump back to target instead of speeding up
    private int target;
    private int extra;         // fixed delay on top of the target (Kodi fine-tune), in chunks
    private int pendingShift;  // chunks of that delay still to add (+, by pausing) or drop (-)
    private int speed;         // frames dropped (+) or added (-) per 240-frame chunk
    private float avgDepth;
    private long lastAdjustMs;

    // stats
    long received;
    long lost;
    long late;
    long underruns;
    long skips;

    /**
     * @param fixedTarget buffer depth in 5 ms chunks, or 0 for adaptive
     * @param minTarget   adaptive floor; Opus arrives 4 chunks at a time so it needs more
     */
    JitterBuffer(int fixedTarget, int minTarget, int catchup) {
        maxSpeedup = catchup == Settings.CATCHUP_GENTLE ? 1 : 5;
        skipAhead = catchup == Settings.CATCHUP_AGGRESSIVE;
        adaptive = fixedTarget == 0;
        this.minTarget = adaptive ? minTarget : fixedTarget;
        target = adaptive ? minTarget + 2 : fixedTarget;
    }

    /** Adds a fixed delay on top of the jitter target, applied at once: a short pause or skip. */
    synchronized void setExtraDelay(int chunks) {
        chunks = Math.max(0, chunks);
        pendingShift += chunks - extra;
        extra = chunks;
    }

    /** Stores one 5 ms chunk of interleaved stereo PCM. */
    synchronized void put(int seq, short[] chunk) {
        if (!haveAny || Math.abs(seq - highestSeq) > SLOTS / 2) reset(seq); // first packet or sender restarted
        if (seq - nextSeq < 0) {
            late++;
            return;
        }
        if (seq - nextSeq >= SLOTS) return;
        int i = seq & MASK;
        System.arraycopy(chunk, 0, slots[i], 0, Protocol.SAMPLES_PER_PACKET);
        slotSeq[i] = seq;
        filled[i] = true;
        if (seq - highestSeq > 0) highestSeq = seq;
        received++;
    }

    private void reset(int seq) {
        java.util.Arrays.fill(filled, false);
        haveAny = true;
        playing = false;
        nextSeq = seq;
        highestSeq = seq;
        avgDepth = target;
        lastAdjustMs = SystemClock.elapsedRealtime();
    }

    /** Fills {@code out} with the next chunk of audio; returns the number of frames written. */
    synchronized int next(short[] out) {
        int depth = haveAny ? highestSeq - nextSeq + 1 : 0;
        long now = SystemClock.elapsedRealtime();
        int goal = target + extra;

        if (playing && pendingShift > 0) { // pause for one chunk: the buffer, and the delay, grow by 5 ms
            pendingShift--;
            return conceal(out);
        }
        if (pendingShift < 0) {            // drop chunks: the delay shrinks at once
            int drop = Math.min(-pendingShift, Math.max(0, depth - 1));
            for (int d = 0; d < drop; d++) filled[(nextSeq + d) & MASK] = false;
            nextSeq += drop;
            depth -= drop;
            avgDepth -= drop;
            pendingShift = 0;
            fadeIn = true;
        }

        if (!playing) {
            pendingShift = 0; // (re)buffering fills up to the goal, extra delay included
            if (!haveAny || depth < goal) return conceal(out);
            playing = true;
        }

        if (depth > MAX_TARGET * 2) { // huge backlog (e.g. after the phone was suspended): jump ahead
            nextSeq = highestSeq - goal + 1;
            depth = goal;
        }

        int i = nextSeq & MASK;
        if (!filled[i] || slotSeq[i] != nextSeq) {
            if (depth > 2) { // later packets are here, so this one is gone
                lost++;
                nextSeq++;
                return conceal(out);
            }
            // Ran dry: rebuffer with a bigger cushion.
            underruns++;
            if (adaptive) target = Math.min(target + 2, MAX_TARGET);
            lastAdjustMs = now;
            playing = false;
            return conceal(out);
        }
        filled[i] = false;
        nextSeq++;

        if (adaptive && now - lastAdjustMs > SHRINK_AFTER_MS && target > minTarget) {
            target--;
            lastAdjustMs = now;
        }

        // ~0.5 s moving average; arrival is bursty so the raw depth is noisy.
        avgDepth += (depth - avgDepth) * 0.01f;
        // The slow average lags a sudden backlog, so catch-up also looks at the live depth.
        float excess = Math.min(avgDepth, depth) - goal;
        if (speed >= 0 && excess > 3) {
            speed = Math.max(speed, Math.min(maxSpeedup, 1 + (int) ((excess - 3) / 2)));
        } else if (speed == 0 && avgDepth < goal - 3) {
            speed = -1;
        }
        if ((speed > 0 && excess <= 0) || (speed < 0 && avgDepth >= goal)) {
            speed = 0;
        } else if (speed > 1 && excess < 2) {
            speed = 1; // ease off near the goal to avoid overshooting
        }

        int frames = N - speed;
        resample(slots[i], out, frames);
        if (fadeIn) {
            for (int f = 0; f < FADE_FRAMES; f++) {
                float g = (float) f / FADE_FRAMES;
                for (int c = 0; c < C; c++) out[f * C + c] = (short) (out[f * C + c] * g);
            }
            fadeIn = false;
        }
        System.arraycopy(slots[i], 0, last, 0, last.length);
        lastValid = true;
        concealRun = 0;

        if (skipAhead && excess > 4) {
            // Fade this chunk out, drop the backlog, and fade the next one in: a ~4 ms dip
            // instead of a click, and the extra delay is gone at once.
            for (int f = 0; f < FADE_FRAMES; f++) {
                float g = (float) f / FADE_FRAMES;
                for (int c = 0; c < C; c++) {
                    int k = (frames - 1 - f) * C + c;
                    out[k] = (short) (out[k] * g);
                }
            }
            int drop = Math.round(excess);
            for (int d = 0; d < drop; d++) filled[(nextSeq + d) & MASK] = false;
            nextSeq += drop;
            avgDepth -= drop;
            speed = 0;
            fadeIn = true;
            skips++;
        }
        return frames;
    }

    private int conceal(short[] out) {
        if (lastValid && concealRun == 0) {
            // Reverse playback keeps continuity with the sample just played; fade to silence.
            for (int f = 0; f < N; f++) {
                float g = 1f - (float) f / N;
                int src = (N - 1 - f) * C;
                for (int c = 0; c < C; c++) out[f * C + c] = (short) (last[src + c] * g);
            }
        } else {
            java.util.Arrays.fill(out, 0, N * C, (short) 0);
        }
        if (haveAny) concealRun++;
        fadeIn = true;
        return N;
    }

    private static void resample(short[] in, short[] out, int frames) {
        if (frames == N) {
            System.arraycopy(in, 0, out, 0, N * C);
            return;
        }
        float step = (float) (N - 1) / (frames - 1);
        for (int f = 0; f < frames; f++) {
            float pos = f * step;
            int i0 = Math.min((int) pos, N - 2);
            float t = pos - i0;
            for (int c = 0; c < C; c++) {
                float a = in[i0 * C + c];
                float b = in[(i0 + 1) * C + c];
                out[f * C + c] = (short) (a + (b - a) * t);
            }
        }
    }

    synchronized int bufferMs() {
        int depth = haveAny ? Math.max(0, highestSeq - nextSeq + 1) : 0;
        return depth * N * 1000 / Protocol.SAMPLE_RATE;
    }

    /** Smoothed depth, which is what actually adds delay; the instant value jumps with each burst. */
    synchronized float avgBufferMs() {
        return avgDepth * N * 1000f / Protocol.SAMPLE_RATE;
    }

    synchronized int targetMs() {
        return (target + extra) * N * 1000 / Protocol.SAMPLE_RATE;
    }
}
