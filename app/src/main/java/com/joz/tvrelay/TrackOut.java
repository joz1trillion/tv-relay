package com.joz.tvrelay;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTimestamp;
import android.media.AudioTrack;

/**
 * AudioTrack output. "Low latency" is the same fast mixer path OpenSL ES uses; that path skips
 * audio effects (EQ, Dolby, etc.), so "normal" exists to trade some latency for them.
 */
final class TrackOut implements AudioOut {
    private final AudioTrack track;
    private final boolean lowLatency;
    private final int burst;
    private final AudioTimestamp ts = new AudioTimestamp();
    private int size;
    private int seenUnderruns;
    private volatile long framesWritten;

    TrackOut(int output, int burst) {
        this.burst = burst;
        lowLatency = output == Settings.OUTPUT_LOW_LATENCY;
        int minBuf = AudioTrack.getMinBufferSize(Protocol.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        int perfMode;
        int bufBytes;
        switch (output) {
            case Settings.OUTPUT_NORMAL:
                perfMode = AudioTrack.PERFORMANCE_MODE_NONE;
                bufBytes = minBuf * 2;
                break;
            case Settings.OUTPUT_POWER_SAVING:
                perfMode = AudioTrack.PERFORMANCE_MODE_POWER_SAVING;
                bufBytes = minBuf * 4;
                break;
            default:
                perfMode = AudioTrack.PERFORMANCE_MODE_LOW_LATENCY;
                bufBytes = minBuf;
        }
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(Protocol.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(perfMode)
                .build();
        size = track.getBufferSizeInFrames();
        if (lowLatency) {
            // Start with a tiny device buffer and grow it only if the track actually underruns,
            // the same tuning approach as Oboe's latency tuner.
            size = track.setBufferSizeInFrames(Math.max(burst * 2, Protocol.FRAMES_PER_PACKET * 2));
        }
        track.play();
    }

    @Override
    public boolean write(short[] buf, int frames) {
        if (track.write(buf, 0, frames * Protocol.CHANNELS, AudioTrack.WRITE_BLOCKING) < 0) return false;
        framesWritten += frames;
        int u = track.getUnderrunCount();
        if (u > seenUnderruns) {
            seenUnderruns = u;
            if (lowLatency && size < track.getBufferCapacityInFrames()) {
                size = track.setBufferSizeInFrames(size + burst);
            }
        }
        return true;
    }

    @Override
    public float latencyMs() {
        if (!track.getTimestamp(ts)) return 0;
        float queued = (framesWritten - ts.framePosition) * 1000f / Protocol.SAMPLE_RATE;
        float sinceStamp = (System.nanoTime() - ts.nanoTime) / 1e6f;
        return Math.max(0, queued - sinceStamp);
    }

    @Override
    public long underruns() {
        return seenUnderruns;
    }

    @Override
    public String describe() {
        boolean fast = track.getPerformanceMode() == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY;
        return (fast ? "fast path" : "normal path") + ", device buffer "
                + size * 1000 / Protocol.SAMPLE_RATE + " ms (burst " + burst + ")";
    }

    @Override
    public void stop() {
        track.pause();
    }

    @Override
    public void release() {
        track.release();
    }
}
