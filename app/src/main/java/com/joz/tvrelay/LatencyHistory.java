package com.joz.tvrelay;

/** Last minute of latency samples (4 per second), split by where the delay comes from. */
final class LatencyHistory {
    static final int CAPACITY = 240;
    static final int NETWORK = 0;
    static final int CODEC = 1;
    static final int BUFFER = 2;
    static final int OUTPUT = 3;
    static final int PARTS = 4;

    private final float[][] samples = new float[CAPACITY][PARTS];
    private int head;
    private int count;

    synchronized void add(float network, float codec, float buffer, float output) {
        float[] s = samples[head];
        s[NETWORK] = network;
        s[CODEC] = codec;
        s[BUFFER] = buffer;
        s[OUTPUT] = output;
        head = (head + 1) % CAPACITY;
        if (count < CAPACITY) count++;
    }

    /** Copies samples oldest-first into {@code out}; returns how many there are. */
    synchronized int snapshot(float[][] out) {
        for (int i = 0; i < count; i++) {
            System.arraycopy(samples[(head - count + i + CAPACITY) % CAPACITY], 0, out[i], 0, PARTS);
        }
        return count;
    }

    /** Mean total latency over the last {@code n} samples, or -1 with no data. */
    synchronized float averageTotal(int n) {
        int k = Math.min(n, count);
        if (k == 0) return -1;
        float sum = 0;
        for (int i = 1; i <= k; i++) {
            for (float v : samples[(head - i + CAPACITY) % CAPACITY]) sum += v;
        }
        return sum / k;
    }

    synchronized float[] latest() {
        return count == 0 ? null : samples[(head - 1 + CAPACITY) % CAPACITY].clone();
    }
}
