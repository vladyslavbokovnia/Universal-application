package im.manus.universalhost;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Склейка видеодорожки (H.264 в mp4) и звука AAC (m4a) в один mp4 без перекодирования. */
public final class YtMux {

    private YtMux() { }

    private static int pick(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith(prefix)) return i;
        }
        return -1;
    }

    /** Видео берётся из video, звук из audio; результат в out (если не вышло, out удаляется). */
    public static void mux(File video, File audio, File out) throws IOException {
        MediaExtractor v = new MediaExtractor();
        MediaExtractor a = new MediaExtractor();
        MediaMuxer m = null;
        boolean started = false, ok = false;
        try {
            v.setDataSource(video.getAbsolutePath());
            a.setDataSource(audio.getAbsolutePath());
            int vt = pick(v, "video/");
            int at = pick(a, "audio/");
            if (vt < 0) throw new IOException("В файле нет видео");
            if (at < 0) throw new IOException("В переводе нет звука");
            v.selectTrack(vt);
            a.selectTrack(at);
            m = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int mv = m.addTrack(v.getTrackFormat(vt));
            int ma = m.addTrack(a.getTrackFormat(at));
            m.start();
            started = true;
            copy(v, m, mv);
            copy(a, m, ma);
            m.stop();
            started = false;
            ok = true;
        } catch (RuntimeException e) {
            throw new IOException("Склейка не вышла: " + e);
        } finally {
            try { if (m != null) { if (started) m.stop(); m.release(); } } catch (Throwable ignored) { }
            try { v.release(); } catch (Throwable ignored) { }
            try { a.release(); } catch (Throwable ignored) { }
            if (!ok) out.delete();
        }
    }

    private static void copy(MediaExtractor ex, MediaMuxer m, int track) {
        ByteBuffer buf = ByteBuffer.allocate(4 << 20);
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        while (true) {
            buf.clear();
            int n = ex.readSampleData(buf, 0);
            if (n < 0) break;
            int flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            bi.set(0, n, ex.getSampleTime(), flags);
            m.writeSampleData(track, buf, bi);
            ex.advance();
        }
    }
}
