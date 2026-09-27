package com.example.tvremote;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/**
 * Tap-to-talk: streams the phone's raw microphone audio (16-bit mono PCM at 8 kHz — exactly the format
 * the official Android TV Remote app uses) to the TV's voice session. No extra processing is applied,
 * matching the reference implementation exactly. Stops on a second tap, when the TV ends the session,
 * or after MAX_MS as a safety net.
 */
final class VoiceRecorder implements Runnable {
    interface Sink {
        void toast(String m);

        /** Called the instant real capture begins (TV has confirmed it is listening) — this is the cue to speak. */
        void started();

        void finished();
    }

    private static final int MAX_MS = 8000;
    private static final int RATE = 8000;
    private static final int CHUNK_BYTES = 8192; // 512 ms of audio at 8 kHz / 16-bit / mono

    private final RemoteSession session;
    private final Sink sink;
    private volatile boolean stop;

    VoiceRecorder(RemoteSession s, Sink sink) {
        this.session = s;
        this.sink = sink;
    }

    void requestStop() {
        stop = true;
    }

    private static AudioRecord make(int rate) {
        // VOICE_RECOGNITION first: it skips the aggressive noise-suppression/AGC processing that MIC applies,
        // which a TV's speech recognizer needs to hear clearly (confirmed against a known-working remote app).
        int[] sources = {MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC};
        for (int i = 0; i < sources.length; i++) {
            AudioRecord r = tryMake(rate, sources[i]);
            if (r != null) return r;
        }
        return null;
    }

    private static AudioRecord tryMake(int rate, int source) {
        try {
            int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) return null;
            AudioRecord r = new AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate * 2 * 2));
            if (r.getState() == AudioRecord.STATE_INITIALIZED) return r;
            r.release();
        } catch (Throwable t) {
            TvLog.d("AudioRecord " + rate + "/" + source + ": " + t);
        }
        return null;
    }

    public void run() {
        AudioRecord rec = null;
        long id = RemoteSession.VOICE_FAILED;
        boolean everSent = false;
        try {
            int decim = 1;
            rec = make(RATE);
            if (rec == null) {
                rec = make(16000); // some phones cannot record at 8 kHz: record at 16 kHz and halve it
                decim = 2;
            }
            if (rec == null) {
                sink.toast("Mic start nahi ho paya");
                return;
            }
            // IMPORTANT: do NOT start capturing yet. Recording used to start here, before the
            // TV had even answered — so the phone's mic buffer filled with silence/handshake
            // noise for up to ~2.5 s while we waited for the TV, that internal buffer (only
            // ~2 s deep) would overflow, and the *stale* audio sitting in it is what got sent
            // first once the loop below finally started reading. That's why the TV showed the
            // mic as pressed but never actually heard a word. Fix: only touch the mic once the
            // TV has confirmed (remote_voice_begin) that it is genuinely listening, so the very
            // first byte we send is live audio from the moment the user starts talking.
            id = session.voiceBegin(4000);
            if (id == RemoteSession.VOICE_FAILED) {
                sink.toast("TV ne voice session nahi kholi, dobara try karo");
                return;
            }
            rec.startRecording();
            sink.started();
            long start = System.currentTimeMillis();
            byte[] raw = new byte[CHUNK_BYTES * decim];
            int filled = 0;
            while (!session.isClosed()) {
                int n = rec.read(raw, filled, raw.length - filled);
                if (n > 0) filled += n;
                boolean full = filled == raw.length;
                boolean flushRemainder = (stop || n < 0) && filled > 0;
                if (full || flushRemainder) {
                    byte[] pcm = decim == 1 ? raw : half(raw, filled);
                    int len = decim == 1 ? filled : filled / 2;
                    session.voiceChunk(id, pcm, len); // raw PCM, unmodified
                    everSent = true;
                    filled = 0;
                }
                if (stop || n < 0 || session.voiceEndedByTv() || System.currentTimeMillis() - start > MAX_MS) break;
            }
        } catch (Throwable t) {
            TvLog.d("voice: " + t);
            sink.toast("Voice error: " + t.getClass().getSimpleName());
        } finally {
            try {
                if (id != RemoteSession.VOICE_FAILED) {
                    session.voiceEnd(id);
                    if (!everSent) sink.toast("Mic se data nahi mila");
                }
            } catch (Throwable ignored) {
            }
            try {
                if (rec != null) {
                    rec.stop();
                    rec.release();
                }
            } catch (Throwable ignored) {
            }
            sink.finished();
        }
    }

    /** 16 kHz -> 8 kHz: average pairs of 16-bit little-endian samples. */
    private static byte[] half(byte[] in, int len) {
        int n = len / 4;
        byte[] out = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            int a = (short) ((in[i * 4] & 0xFF) | (in[i * 4 + 1] << 8));
            int b = (short) ((in[i * 4 + 2] & 0xFF) | (in[i * 4 + 3] << 8));
            int v = (a + b) / 2;
            out[i * 2] = (byte) v;
            out[i * 2 + 1] = (byte) (v >> 8);
        }
        return out;
    }
}
