package im.manus.universalhost.dep;

import android.content.Context;

import java.util.List;

/**
 * Интерфейс зависимости Uhm (поиск слов-паразитов «эээ»). Реализация (im.manus.deps.UhmImpl) лежит в dep-uhm.apk вместе с
 * нативной библиотекой и моделью-загрузчиком; хост грузит её через Deps и сам режет и склеивает звук.
 */
public interface FillerEngine {

    /** Создаёт модель; если её файлов в modelDir ещё нет, скачивает их (нужен интернет). */
    void open(Context app, String modelDir) throws Exception;

    /** Моно 16 кГц. Возвращает отрезки {начало, конец} в секундах от начала куска. */
    List<double[]> detect(float[] mono16k) throws Exception;

    void close();
}
