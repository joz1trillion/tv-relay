package com.joz.tvrelay;

/** Where decoded audio goes: a platform AudioTrack, or an AAudio exclusive (MMAP) stream. */
interface AudioOut {
    /** Blocking write of interleaved stereo; returns false if the output died (e.g. route change). */
    boolean write(short[] buf, int frames);

    /** Time from a frame being written to it leaving the speaker. */
    float latencyMs();

    long underruns();

    String describe();

    /** Unblocks a pending write so the play thread can exit. */
    void stop();

    void release();
}
