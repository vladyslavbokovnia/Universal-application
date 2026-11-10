package im.manus.universalhost;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;

/**
 * Вырезает из аудиофайла паузы-«эээ» (филлеры) моделью Uhm (Desert Ant Labs, на устройстве, без сервера).
 *
 * Модель вызывается через reflection: пока в сборку не добавлена зависимость ai.desertant:uhm, класс просто не найден,
 * и вызов заканчивается понятной ошибкой, а хост собирается как обычно. Её метод detect — suspend-функция Kotlin,
 * поэтому в нужный момент подставляется свой Continuation и поток ждёт результата.
 *
 * Порядок: файл декодируется в сырой PCM (MediaCodec) → моно 16 кГц кусками по 90 с (+2 с нахлёста) уходит в модель →
 * найденные отрезки склеиваются с короткой перекрёстной затухающей склейкой → результат кодируется обратно в AAC (m4a).
 * Если вырезать нечего или модель нашла подозрительно много (больше четверти записи), исходный файл не трогается.
 * Модель обучена на английском: для русской речи это перенос, потому порог «precision» и защита от лишней резки.
 */
public final class FillerCleaner {

    private static final String UHM = "ai.desertant.uhm.Uhm";
    private static final double CHUNK_SEC = 90;
    private static final double OVERLAP_SEC = 2;
    private static final double MERGE_GAP_SEC = 0.12;
    private static final double MAX_SHARE = 0.25;
    private static final int XFADE_MS = 8;

    public static final class Report {
        /** Сколько филлеров вырезано. */
        public int count;
        public double removedSec;
        public double totalSec;
        /** Итоговый файл (может отличаться от исходного расширением). */
        public String file;
    }

    /** Модели нет в сборке (не подключена зависимость). */
    public static final class Unavailable extends IOException {
        Unavailable(String m) { super(m); }
    }

    private FillerCleaner() { }

    public static boolean available() {
        try {
            Class.forName(UHM);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final class Pcm {
        int rate, ch;
        long frames;
    }

    /** Чистит файл на месте. Возвращает отчёт; file в нём — путь результата (при count = 0 это исходный файл). */
    public static Report clean(Context ctx, File audio) throws Exception {
        if (!available()) throw new Unavailable("Модель Uhm не подключена");
        Context app = ctx.getApplicationContext();
        File work = new File(app.getCacheDir(), "fillers");
        work.mkdirs();
        long stamp = System.nanoTime();
        File pcmFile = new File(work, "src-" + stamp + ".pcm");
        File cutFile = new File(work, "cut-" + stamp + ".pcm");
        File encFile = new File(audio.getParentFile(), audio.getName() + ".clean.part");
        RandomAccessFile raf = null;
        try {
            Pcm p = decode(audio, pcmFile);
            if (p.ch < 1 || p.ch > 2) throw new IOException("Каналов: " + p.ch);
            if (p.frames < p.rate) throw new IOException("Слишком короткая запись");
            Report rep = new Report();
            rep.totalSec = (double) p.frames / p.rate;
            rep.file = audio.getAbsolutePath();

            raf = new RandomAccessFile(pcmFile, "r");
            List<double[]> found = detectAll(app, raf, p);
            List<double[]> merged = merge(found);
            double removed = 0;
            for (double[] c : merged) removed += c[1] - c[0];
            if (merged.isEmpty()) return rep;
            if (removed > rep.totalSec * MAX_SHARE) throw new IOException("Найдено слишком много (" + Math.round(100 * removed / rep.totalSec) + "%), файл не тронут");

            List<long[]> cuts = new ArrayList<long[]>();
            for (double[] c : merged) {
                long cs = Math.max(0, Math.round(c[0] * p.rate));
                long ce = Math.min(p.frames, Math.round(c[1] * p.rate));
                if (ce > cs) cuts.add(new long[]{cs, ce});
            }
            long kept = writeCut(raf, p, cuts, cutFile);
            raf.close();
            raf = null;
            encode(cutFile, p.rate, p.ch, encFile);

            String name = audio.getName();
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            File dir = audio.getParentFile();
            File out = new File(dir, base + ".m4a");
            if (out.equals(audio)) {
                if (!audio.delete()) throw new IOException("Не заменить файл");
                if (!encFile.renameTo(out)) throw new IOException("Не сохранить файл");
            } else {
                for (int n = 2; out.exists(); n++) out = new File(dir, base + " (" + n + ").m4a");
                if (!encFile.renameTo(out)) throw new IOException("Не сохранить файл");
                audio.delete();
            }
            rep.count = merged.size();
            rep.removedSec = (double) (p.frames - kept) / p.rate;
            rep.file = out.getAbsolutePath();
            return rep;
        } finally {
            try { if (raf != null) raf.close(); } catch (Throwable ignored) { }
            pcmFile.delete();
            cutFile.delete();
            encFile.delete();
        }
    }

    // ------------------------------------------------------------ декодирование

    private static Pcm decode(File src, File pcm) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        OutputStream out = null;
        try {
            ex.setDataSource(src.getAbsolutePath());
            int track = -1;
            MediaFormat inFmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; inFmt = f; break; }
            }
            if (track < 0) throw new IOException("В файле нет звука");
            ex.selectTrack(track);
            dec = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME));
            dec.configure(inFmt, null, null, 0);
            dec.start();
            Pcm r = new Pcm();
            r.rate = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            r.ch = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            out = new BufferedOutputStream(new FileOutputStream(pcm), 1 << 16);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            byte[] tmp = new byte[1 << 16];
            int idle = 0;
            while (!outDone) {
                boolean moved = false;
                if (!inDone) {
                    int i = dec.dequeueInputBuffer(10000);
                    if (i >= 0) {
                        ByteBuffer b = dec.getInputBuffer(i);
                        int n = ex.readSampleData(b, 0);
                        if (n < 0) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            dec.queueInputBuffer(i, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                        moved = true;
                    }
                }
                int o = dec.dequeueOutputBuffer(info, 10000);
                if (o >= 0) {
                    moved = true;
                    if (info.size > 0) {
                        ByteBuffer ob = dec.getOutputBuffer(o);
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        while (ob.hasRemaining()) {
                            int n = Math.min(ob.remaining(), tmp.length);
                            ob.get(tmp, 0, n);
                            out.write(tmp, 0, n);
                        }
                    }
                    dec.releaseOutputBuffer(o, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    moved = true;
                    MediaFormat f = dec.getOutputFormat();
                    r.rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    r.ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                }
                idle = moved ? 0 : idle + 1;
                if (idle > 3000) throw new IOException("Декодер завис");
            }
            out.flush();
            r.frames = pcm.length() / (2L * r.ch);
            return r;
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) { }
            try { if (dec != null) { dec.stop(); dec.release(); } } catch (Throwable ignored) { }
            try { ex.release(); } catch (Throwable ignored) { }
        }
    }

    // ------------------------------------------------------------ поиск филлеров

    /** Моно 16 кГц: outSamples отсчётов начиная с времени t0 (линейная интерполяция, каналы усредняются). */
    private static float[] mono16k(RandomAccessFile raf, Pcm p, double t0, int outSamples) throws IOException {
        double step = p.rate / 16000.0;
        long first = (long) Math.floor(t0 * p.rate);
        long last = Math.min(p.frames - 1, (long) Math.ceil(t0 * p.rate + outSamples * step) + 1);
        int n = (int) (last - first + 1);
        if (n < 2) return new float[0];
        byte[] raw = new byte[n * p.ch * 2];
        raf.seek(first * p.ch * 2L);
        raf.readFully(raw);
        java.nio.ShortBuffer sb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        float[] mono = new float[n];
        for (int i = 0; i < n; i++) {
            float s = 0;
            for (int c = 0; c < p.ch; c++) s += sb.get(i * p.ch + c);
            mono[i] = s / p.ch / 32768f;
        }
        float[] out = new float[outSamples];
        int m = 0;
        double base = t0 * p.rate - first;
        for (int j = 0; j < outSamples; j++) {
            double pos = base + j * step;
            int i0 = (int) pos;
            if (i0 >= n - 1) break;
            float fr = (float) (pos - i0);
            out[m++] = mono[i0] * (1 - fr) + mono[i0 + 1] * fr;
        }
        if (m == outSamples) return out;
        float[] cut = new float[m];
        System.arraycopy(out, 0, cut, 0, m);
        return cut;
    }

    private static List<double[]> detectAll(Context app, RandomAccessFile raf, Pcm p) throws Exception {
        Class<?> uhmCls = Class.forName(UHM);
        Class<?> optCls = Class.forName("ai.desertant.uhm.Options");
        Class<?> biasCls = Class.forName("ai.desertant.uhm.Bias");
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object precision = Enum.valueOf((Class<? extends Enum>) biasCls.asSubclass(Enum.class), "PRECISION");
        Constructor<?> optCtor = optCls.getConstructor(biasCls, Double.class, Double.class, double.class, boolean.class);
        Object opts = optCtor.newInstance(precision, null, null, 0.5, false);
        Constructor<?> uhmCtor = uhmCls.getConstructor(Context.class, String.class);
        String dir = new File(app.getFilesDir(), "uhm").getAbsolutePath();
        Object uhm = uhmCtor.newInstance(app, dir);
        try {
            boolean ready = (Boolean) uhmCls.getMethod("isDownloaded").invoke(uhm);
            if (!ready) callSuspend(uhmCls.getMethod("download", Continuation.class), uhm);
            Method detect = uhmCls.getMethod("detect", float[].class, double.class, optCls, List.class, Continuation.class);
            double total = (double) p.frames / p.rate;
            List<double[]> res = new ArrayList<double[]>();
            for (double t0 = 0; t0 < total; t0 += CHUNK_SEC) {
                boolean last = t0 + CHUNK_SEC >= total;
                double len = Math.min(CHUNK_SEC + OVERLAP_SEC, total - t0);
                int n = (int) (len * 16000);
                if (n < 8000) { if (t0 > 0) break; else continue; }
                float[] s = mono16k(raf, p, t0, n);
                if (s.length < 8000) continue;
                Object r = callSuspend(detect, uhm, s, 16000.0, opts, null);
                List<?> fillers = (List<?>) r.getClass().getMethod("getFillers").invoke(r);
                for (Object f : fillers) {
                    double st = (Double) f.getClass().getMethod("getStart").invoke(f);
                    double en = (Double) f.getClass().getMethod("getEnd").invoke(f);
                    if (last || st < CHUNK_SEC) res.add(new double[]{t0 + st, t0 + en});
                }
            }
            return res;
        } finally {
            try { ((AutoCloseable) uhm).close(); } catch (Throwable ignored) { }
        }
    }

    /** Вызывает suspend-функцию Kotlin: подставляет Continuation и ждёт результата. */
    private static Object callSuspend(Method m, Object target, Object... args) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final Object[] box = new Object[1];
        Continuation<Object> c = new Continuation<Object>() {
            @Override public CoroutineContext getContext() { return EmptyCoroutineContext.INSTANCE; }

            @Override public void resumeWith(Object result) {
                box[0] = result;
                latch.countDown();
            }
        };
        Object[] full = new Object[args.length + 1];
        System.arraycopy(args, 0, full, 0, args.length);
        full[args.length] = c;
        Object r;
        try {
            r = m.invoke(target, full);
        } catch (InvocationTargetException e) {
            Throwable t = e.getCause() == null ? e : e.getCause();
            throw t instanceof Exception ? (Exception) t : new IOException(t.toString());
        }
        if (r == IntrinsicsKt.getCOROUTINE_SUSPENDED()) {
            latch.await();
            r = box[0];
        }
        if (r != null && r.getClass().getName().equals("kotlin.Result$Failure")) {
            java.lang.reflect.Field f = r.getClass().getDeclaredField("exception");
            f.setAccessible(true);
            Throwable t = (Throwable) f.get(r);
            throw t instanceof Exception ? (Exception) t : new IOException(String.valueOf(t));
        }
        return r;
    }

    /** Сортирует отрезки и склеивает те, что ближе MERGE_GAP_SEC друг к другу. */
    private static List<double[]> merge(List<double[]> in) {
        List<double[]> a = new ArrayList<double[]>(in);
        Collections.sort(a, new Comparator<double[]>() {
            @Override public int compare(double[] x, double[] y) { return Double.compare(x[0], y[0]); }
        });
        List<double[]> r = new ArrayList<double[]>();
        for (double[] c : a) {
            if (!r.isEmpty() && c[0] - r.get(r.size() - 1)[1] < MERGE_GAP_SEC) {
                double[] l = r.get(r.size() - 1);
                l[1] = Math.max(l[1], c[1]);
            } else {
                r.add(new double[]{c[0], c[1]});
            }
        }
        return r;
    }

    // ------------------------------------------------------------ резка

    private static void copyFrames(RandomAccessFile raf, OutputStream o, int ch, long start, long count) throws IOException {
        if (count <= 0) return;
        raf.seek(start * ch * 2L);
        byte[] buf = new byte[ch * 2 * 16384];
        long left = count;
        while (left > 0) {
            int n = (int) Math.min(left, 16384);
            raf.readFully(buf, 0, n * ch * 2);
            o.write(buf, 0, n * ch * 2);
            left -= n;
        }
    }

    private static short[] readShorts(RandomAccessFile raf, int ch, long start, int frames) throws IOException {
        byte[] raw = new byte[frames * ch * 2];
        raf.seek(start * ch * 2L);
        raf.readFully(raw);
        short[] s = new short[frames * ch];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s);
        return s;
    }

    /** Вырезает отрезки; на каждом шве короткая перекрёстная склейка, чтобы не щёлкало. Возвращает число оставшихся кадров. */
    private static long writeCut(RandomAccessFile raf, Pcm p, List<long[]> cuts, File out) throws IOException {
        OutputStream o = new BufferedOutputStream(new FileOutputStream(out), 1 << 16);
        try {
            long pos = 0, written = 0;
            int xf = Math.max(1, p.rate * XFADE_MS / 1000);
            for (long[] c : cuts) {
                long cs = c[0], ce = c[1];
                if (cs < pos) continue;
                int n = (int) Math.max(0, Math.min(xf, Math.min(cs - pos, p.frames - ce)));
                copyFrames(raf, o, p.ch, pos, cs - n - pos);
                written += cs - n - pos;
                if (n > 0) {
                    short[] a = readShorts(raf, p.ch, cs - n, n);
                    short[] b = readShorts(raf, p.ch, ce, n);
                    ByteBuffer bb = ByteBuffer.allocate(n * p.ch * 2).order(ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < n; i++) {
                        float w = (i + 1f) / (n + 1f);
                        for (int ch = 0; ch < p.ch; ch++) {
                            int k = i * p.ch + ch;
                            bb.putShort((short) Math.round(a[k] * (1 - w) + b[k] * w));
                        }
                    }
                    o.write(bb.array(), 0, n * p.ch * 2);
                    written += n;
                }
                pos = ce + n;
            }
            copyFrames(raf, o, p.ch, pos, p.frames - pos);
            written += p.frames - pos;
            return written;
        } finally {
            o.close();
        }
    }

    // ------------------------------------------------------------ кодирование в AAC

    private static int readFully(InputStream in, byte[] buf, int want) throws IOException {
        int got = 0;
        while (got < want) {
            int n = in.read(buf, got, want - got);
            if (n < 0) break;
            got += n;
        }
        return got;
    }

    private static void encode(File pcm, int rate, int ch, File out) throws IOException {
        MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, ch);
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        f.setInteger(MediaFormat.KEY_BIT_RATE, ch == 1 ? 96000 : 160000);
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 << 16);
        MediaCodec enc = null;
        MediaMuxer mux = null;
        InputStream in = null;
        boolean muxStarted = false;
        try {
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            in = new BufferedInputStream(new FileInputStream(pcm), 1 << 16);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int frameBytes = 2 * ch;
            byte[] buf = new byte[1 << 14];
            long sent = 0;
            int track = -1, idle = 0;
            boolean inDone = false, outDone = false;
            while (!outDone) {
                boolean moved = false;
                if (!inDone) {
                    int ii = enc.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = enc.getInputBuffer(ii);
                        ib.clear();
                        int want = Math.min(ib.remaining(), buf.length);
                        want -= want % frameBytes;
                        int got = readFully(in, buf, want);
                        got -= got % frameBytes;
                        long pts = sent * 1000000L / rate;
                        if (got <= 0) {
                            enc.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            ib.put(buf, 0, got);
                            enc.queueInputBuffer(ii, 0, got, pts, 0);
                            sent += got / frameBytes;
                        }
                        moved = true;
                    }
                }
                int oi = enc.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = mux.addTrack(enc.getOutputFormat());
                    mux.start();
                    muxStarted = true;
                    moved = true;
                } else if (oi >= 0) {
                    moved = true;
                    ByteBuffer ob = enc.getOutputBuffer(oi);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && muxStarted) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        mux.writeSampleData(track, ob, info);
                    }
                    enc.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                }
                idle = moved ? 0 : idle + 1;
                if (idle > 3000) throw new IOException("Кодер завис");
            }
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
            try { if (enc != null) { enc.stop(); enc.release(); } } catch (Throwable ignored) { }
            try { if (mux != null) { if (muxStarted) mux.stop(); mux.release(); } } catch (Throwable ignored) { }
        }
    }
}
