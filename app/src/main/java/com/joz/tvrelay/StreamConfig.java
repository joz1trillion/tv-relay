package com.joz.tvrelay;

/** The stream format the phone asks the TV for. */
final class StreamConfig {
    static final StreamConfig DEFAULT = new StreamConfig(Protocol.CODEC_PCM, 2, 128);

    final int codec;
    final int channels;
    final int bitrateKbps;

    StreamConfig(int codec, int channels, int bitrateKbps) {
        this.codec = codec;
        this.channels = channels;
        this.bitrateKbps = bitrateKbps;
    }

    boolean matches(int codec, int channels, int bitrateKbps) {
        return this.codec == codec && this.channels == channels && this.bitrateKbps == bitrateKbps;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StreamConfig
                && ((StreamConfig) o).matches(codec, channels, bitrateKbps);
    }

    @Override
    public int hashCode() {
        return (codec * 31 + channels) * 31 + bitrateKbps;
    }

    String describe() {
        String ch = channels == 2 ? "stereo" : "mono";
        return codec == Protocol.CODEC_OPUS ? "Opus " + bitrateKbps + " kbps " + ch : "PCM " + ch;
    }
}
