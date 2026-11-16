package im.manus.deps;

import android.content.Context;

import im.manus.universalhost.dep.FillerEngine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Лёгкий поиск «эээ» без модели и без нативных библиотек: ищутся долгие ровные гласные, то есть участки, где голос
 * есть, а высота тона и форма спектра почти не меняются (в живой речи они меняются каждые 80-200 мс).
 *
 * Для каждого кадра в 10 мс считаются: громкость, периодичность (нормированная автокорреляция на 8 кГц), высота тона
 * и огибающая спектра (16 полос 150-4000 Гц, из логарифма вычтена средняя громкость). Кадр «ровный», если тон и огибающая
 * почти не отличаются от предыдущего кадра. Отрезок из ровных кадров дольше MIN_SEC считается «эээ»; по краям отрезка
 * оставляется TRIM_SEC звука, чтобы не задеть соседние слова.
 *
 * Русскому языку модель не нужна: метод от языка не зависит. Платой за простоту будут ошибки: ровно протянутое слово
 * («ну-у-у», «так-к-к») тоже вырежется, а «эээ» с дрожащим тоном останется.
 */
public final class SteadyImpl implements FillerEngine {

    private static final int SR = 16000;
    private static final double MIN_SEC = 0.40;
    private static final double MAX_SEC = 3.0;
    private static final double TRIM_SEC = 0.05;
    private static final double MAX_DF0 = 0.035;     // относительное изменение тона между соседними кадрами
    private static final double MAX_DSPEC = 1.2;     // изменение огибающей (среднеквадратичное по 16 полосам)
    private static final double MIN_R = 0.55;        // нормированная автокорреляция: ниже голос не считается звонким
    private static final double ABOVE_FLOOR_DB = 12; // громче «пола» шума на столько дБ
    private static final int BANDS = 16;
    private static final int FFT = 512;
    private static final int WIN8 = 320;             // 40 мс при 8 кГц
    private static final int HOP8 = 80;              // 10 мс
    private static final int LAG_MIN = 20;           // 400 Гц
    private static final int LAG_MAX = 115;          // 70 Гц

    public SteadyImpl() { }

    @Override public void open(Context app, String modelDir) { }

    @Override public void close() { }

    @Override public List<double[]> detect(float[] x) {
        List<double[]> out = new ArrayList<double[]>();
        int n8 = x.length / 2;
        if (n8 < WIN8 + HOP8) return out;
        double[] x8 = new double[n8];
        for (int j = 0; j < n8; j++) x8[j] = (x[2 * j] + x[2 * j + 1]) * 0.5;
        int nfr = (n8 - WIN8) / HOP8 + 1;

        double[] db = new double[nfr];
        double[] r = new double[nfr];
        double[] f0 = new double[nfr];
        double[][] spec = new double[nfr][BANDS];

        // полосы: 16 штук, границы 150..4000 Гц геометрически; бин БПФ = 31,25 Гц
        double[] edges = new double[BANDS + 1];
        for (int k = 0; k <= BANDS; k++) edges[k] = 150.0 * Math.pow(4000.0 / 150.0, (double) k / BANDS);
        int[] bandOf = new int[FFT / 2 + 1];
        Arrays.fill(bandOf, -1);
        for (int b = 0; b <= FFT / 2; b++) {
            double f = b * (double) SR / FFT;
            for (int k = 0; k < BANDS; k++) {
                if (f >= edges[k] && f < edges[k + 1]) { bandOf[b] = k; break; }
            }
        }
        double[] hann = new double[400];
        for (int i = 0; i < 400; i++) hann[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / 399.0);
        double[] re = new double[FFT];
        double[] im = new double[FFT];
        double[] s = new double[WIN8];
        double[] cum = new double[WIN8 + 1];
        int span = WIN8 - LAG_MAX;

        for (int i = 0; i < nfr; i++) {
            int o = i * HOP8;
            double sum = 0, mean = 0;
            for (int k = 0; k < WIN8; k++) { double v = x8[o + k]; sum += v * v; mean += v; }
            mean /= WIN8;
            double rms = Math.sqrt(sum / WIN8 + 1e-12);
            db[i] = 20 * Math.log10(rms + 1e-9);

            // нормированная автокорреляция
            cum[0] = 0;
            for (int k = 0; k < WIN8; k++) { s[k] = x8[o + k] - mean; cum[k + 1] = cum[k] + s[k] * s[k]; }
            double d0 = cum[span];
            double best = 0;
            int bl = 0;
            for (int lag = LAG_MIN; lag <= LAG_MAX; lag++) {
                double dot = 0;
                for (int k = 0; k < span; k++) dot += s[k] * s[k + lag];
                double eb = cum[lag + span] - cum[lag];
                double c = dot / Math.sqrt(d0 * eb + 1e-12);
                if (c > best) { best = c; bl = lag; }
            }
            r[i] = best;
            f0[i] = bl > 0 ? 8000.0 / bl : 0;

            // огибающая спектра по окну в 25 мс (400 отсчётов при 16 кГц) вокруг центра кадра
            int c16 = o * 2 + WIN8;
            Arrays.fill(re, 0);
            Arrays.fill(im, 0);
            int st = c16 - 200;
            for (int k = 0; k < 400; k++) {
                int idx = st + k;
                re[k] = (idx >= 0 && idx < x.length) ? x[idx] * hann[k] : 0;
            }
            fft(re, im);
            double[] bandSum = new double[BANDS];
            for (int b = 0; b <= FFT / 2; b++) {
                int k = bandOf[b];
                if (k >= 0) bandSum[k] += re[b] * re[b] + im[b] * im[b];
            }
            double m = 0;
            for (int k = 0; k < BANDS; k++) { spec[i][k] = Math.log(bandSum[k] + 1e-9); m += spec[i][k]; }
            m /= BANDS;
            for (int k = 0; k < BANDS; k++) spec[i][k] -= m;
        }

        double[] sorted = db.clone();
        Arrays.sort(sorted);
        double floor = sorted[(int) (0.10 * (nfr - 1))];

        boolean[] voiced = new boolean[nfr];
        for (int i = 0; i < nfr; i++) voiced[i] = db[i] > floor + ABOVE_FLOOR_DB && r[i] > MIN_R;
        boolean[] steady = new boolean[nfr];
        for (int i = 1; i < nfr; i++) {
            if (!voiced[i] || !voiced[i - 1]) continue;
            double dp = Math.abs(f0[i] - f0[i - 1]) / Math.max(f0[i], 1);
            double ds = 0;
            for (int k = 0; k < BANDS; k++) { double d = spec[i][k] - spec[i - 1][k]; ds += d * d; }
            ds = Math.sqrt(ds / BANDS);
            steady[i] = dp < MAX_DF0 && ds < MAX_DSPEC;
        }
        // сглаживание: в окне из 10 кадров ровных не меньше 80 %
        boolean[] ok = new boolean[nfr];
        for (int i = 0; i < nfr; i++) {
            int cnt = 0;
            for (int j = i - 5; j <= i + 4; j++) if (j >= 0 && j < nfr && steady[j]) cnt++;
            ok[i] = cnt >= 8 && voiced[i];
        }
        int i = 0;
        while (i < nfr) {
            if (!ok[i]) { i++; continue; }
            int j = i, gap = 0, last = i;
            while (j < nfr && gap <= 3) {
                if (ok[j]) { last = j; gap = 0; } else gap++;
                j++;
            }
            double a = i * 0.01, b = (last + 1) * 0.01 + 0.04;
            double d = b - a;
            if (d >= MIN_SEC && d <= MAX_SEC) out.add(new double[]{a + TRIM_SEC, b - TRIM_SEC});
            i = last + 1;
        }
        return out;
    }

    /** Быстрое преобразование Фурье на месте, длина степень двойки. */
    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int u = i + k, v = i + k + len / 2;
                    double xr = re[v] * cr - im[v] * ci;
                    double xi = re[v] * ci + im[v] * cr;
                    re[v] = re[u] - xr; im[v] = im[u] - xi;
                    re[u] += xr; im[u] += xi;
                    double nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = nr;
                }
            }
        }
    }
}
