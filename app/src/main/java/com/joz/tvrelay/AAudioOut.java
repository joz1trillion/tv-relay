package com.joz.tvrelay;

import android.util.Log;

/** AAudio stream that asks for EXCLUSIVE sharing, i.e. the MMAP path straight to the DSP. */
final class AAudioOut implements AudioOut {
    private static boolean loaded;

    static {
        try {
            System.loadLibrary("relayaudio");
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            Log.e("TvRelayReceiver", "native audio library missing", e);
        }
    }

    private final long handle;

    private AAudioOut(long handle) {
        this.handle = handle;
    }

    /** Returns null if AAudio couldn't open a stream at all. */
    static AAudioOut open() {
        if (!loaded) return null;
        long h = nativeOpen(Protocol.SAMPLE_RATE, Protocol.CHANNELS);
        return h == 0 ? null : new AAudioOut(h);
    }

    @Override
    public boolean write(short[] buf, int frames) {
        return nativeWrite(handle, buf, frames) >= 0;
    }

    @Override
    public float latencyMs() {
        return nativeLatencyMs(handle);
    }

    @Override
    public long underruns() {
        return nativeXRuns(handle);
    }

    @Override
    public String describe() {
        return nativeInfo(handle);
    }

    @Override
    public void stop() {
        nativeStop(handle);
    }

    @Override
    public void release() {
        nativeClose(handle);
    }

    private static native long nativeOpen(int rate, int channels);
    private static native int nativeWrite(long h, short[] buf, int frames);
    private static native float nativeLatencyMs(long h);
    private static native int nativeXRuns(long h);
    private static native String nativeInfo(long h);
    private static native void nativeStop(long h);
    private static native void nativeClose(long h);
}
