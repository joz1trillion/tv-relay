package com.joz.tvrelay;

import android.media.MediaCodec;
import android.media.MediaFormat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/** Thin synchronous wrappers around the platform's Opus MediaCodec (built into Android 10+). */
final class OpusCodec {
    interface PacketSink {
        void onPacket(long ptsUs, byte[] data, int len);
    }

    interface PcmSink {
        void onPcm(long ptsUs, ShortBuffer pcm, int frames, int channels);
    }

    static final class Encoder {
        private final MediaCodec codec;
        private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        private final byte[] out = new byte[Protocol.MAX_PACKET];

        Encoder(int channels, int bitrate) throws IOException {
            MediaFormat f = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_OPUS, Protocol.SAMPLE_RATE, channels);
            f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
        }

        void encode(byte[] pcm, int len, long ptsUs, PacketSink sink) {
            int in = codec.dequeueInputBuffer(5000);
            if (in >= 0) {
                ByteBuffer b = codec.getInputBuffer(in);
                b.clear();
                b.put(pcm, 0, len);
                codec.queueInputBuffer(in, 0, len, ptsUs, 0);
            }
            while (true) {
                int o = codec.dequeueOutputBuffer(info, 0);
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue;
                if (o < 0) break;
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        && info.size > 0 && info.size <= out.length) {
                    ByteBuffer b = codec.getOutputBuffer(o);
                    b.position(info.offset);
                    b.get(out, 0, info.size);
                    sink.onPacket(info.presentationTimeUs, out, info.size);
                }
                codec.releaseOutputBuffer(o, false);
            }
        }

        void release() {
            try {
                codec.stop();
            } catch (IllegalStateException ignored) {
            }
            codec.release();
        }
    }

    static final class Decoder {
        final int channels;
        private final MediaCodec codec;
        private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        private int outChannels;

        Decoder(int channels) throws IOException {
            this.channels = channels;
            outChannels = channels;
            MediaFormat f = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_OPUS, Protocol.SAMPLE_RATE, channels);
            f.setByteBuffer("csd-0", opusHead(channels));
            f.setByteBuffer("csd-1", nanos(0));          // pre-skip 0 keeps output aligned to 5 ms chunks
            f.setByteBuffer("csd-2", nanos(80_000_000L)); // seek pre-roll
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
            codec.configure(f, null, null, 0);
            codec.start();
        }

        void decode(byte[] data, int off, int len, long ptsUs, PcmSink sink) {
            int in = codec.dequeueInputBuffer(5000);
            if (in >= 0) {
                ByteBuffer b = codec.getInputBuffer(in);
                b.clear();
                b.put(data, off, len);
                codec.queueInputBuffer(in, 0, len, ptsUs, 0);
            }
            while (true) {
                int o = codec.dequeueOutputBuffer(info, 0);
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outChannels = codec.getOutputFormat().getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    continue;
                }
                if (o < 0) break;
                if (info.size > 0) {
                    ByteBuffer b = codec.getOutputBuffer(o);
                    b.position(info.offset);
                    b.limit(info.offset + info.size);
                    ShortBuffer pcm = b.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                    sink.onPcm(info.presentationTimeUs, pcm, info.size / 2 / outChannels, outChannels);
                }
                codec.releaseOutputBuffer(o, false);
            }
        }

        void release() {
            try {
                codec.stop();
            } catch (IllegalStateException ignored) {
            }
            codec.release();
        }

        private static ByteBuffer opusHead(int channels) {
            ByteBuffer b = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
            b.put("OpusHead".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            b.put((byte) 1);                // version
            b.put((byte) channels);
            b.putShort((short) 0);          // pre-skip
            b.putInt(Protocol.SAMPLE_RATE); // input sample rate
            b.putShort((short) 0);          // output gain
            b.put((byte) 0);                // channel mapping family
            b.flip();
            return b;
        }

        private static ByteBuffer nanos(long v) {
            ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());
            b.putLong(v);
            b.flip();
            return b;
        }
    }

    private OpusCodec() {}
}
