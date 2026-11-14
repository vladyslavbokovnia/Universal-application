package im.manus.deps;

import android.content.Context;

import im.manus.universalhost.dep.FillerEngine;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;

/**
 * Реализация {@link FillerEngine} на модели Uhm (Desert Ant Labs, на устройстве, без сервера).
 *
 * Библиотека вызывается через reflection, как раньше в хосте: её метод detect, это suspend-функция Kotlin, поэтому в нужный
 * момент подставляется свой Continuation и поток ждёт результата. Хост создаёт эту реализацию через DexClassLoader
 * из dep-uhm.apk; сама библиотека и её нативные части лежат там же.
 */
public final class UhmImpl implements FillerEngine {

    private static final String UHM = "ai.desertant.uhm.Uhm";

    private Object uhm;
    private Object opts;
    private Method detect;

    public UhmImpl() { }

    @Override public synchronized void open(Context app, String modelDir) throws Exception {
        close();
        Class<?> uhmCls = Class.forName(UHM);
        Class<?> optCls = Class.forName("ai.desertant.uhm.Options");
        Class<?> biasCls = Class.forName("ai.desertant.uhm.Bias");
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object precision = Enum.valueOf((Class<? extends Enum>) biasCls.asSubclass(Enum.class), "PRECISION");
        Constructor<?> optCtor = optCls.getConstructor(biasCls, Double.class, Double.class, double.class, boolean.class);
        Object o = optCtor.newInstance(precision, null, null, 0.5, false);
        Constructor<?> uhmCtor = uhmCls.getConstructor(Context.class, String.class);
        Object u = uhmCtor.newInstance(app, modelDir);
        try {
            boolean ready = (Boolean) uhmCls.getMethod("isDownloaded").invoke(u);
            if (!ready) callSuspend(uhmCls.getMethod("download", Continuation.class), u);
            detect = uhmCls.getMethod("detect", float[].class, double.class, optCls, List.class, Continuation.class);
        } catch (Exception e) {
            try { ((AutoCloseable) u).close(); } catch (Throwable ignored) { }
            throw e;
        }
        opts = o;
        uhm = u;
    }

    @Override public synchronized List<double[]> detect(float[] mono16k) throws Exception {
        if (uhm == null) throw new IOException("Модель не открыта");
        Object r = callSuspend(detect, uhm, mono16k, 16000.0, opts, null);
        List<?> fillers = (List<?>) r.getClass().getMethod("getFillers").invoke(r);
        List<double[]> out = new ArrayList<double[]>();
        for (Object f : fillers) {
            double st = (Double) f.getClass().getMethod("getStart").invoke(f);
            double en = (Double) f.getClass().getMethod("getEnd").invoke(f);
            out.add(new double[]{st, en});
        }
        return out;
    }

    @Override public synchronized void close() {
        Object u = uhm;
        uhm = null;
        opts = null;
        detect = null;
        if (u == null) return;
        try { ((AutoCloseable) u).close(); } catch (Throwable ignored) { }
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
}
