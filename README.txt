Движок очистки «эээ» Steady + удаление зависимостей. Положить в корень репозитория Universal-application с сохранением путей:
  dep-steady/...            новая зависимость (Java, без нативных библиотек)
  settings.gradle           добавлен include ':dep-steady'
  .github/workflows/android.yml   сборка и релиз dep-steady.apk
  ci/patches/clean-engines.patch  правки хоста и модуля (применятся автоматически и удалятся)
Сначала залить всё кроме ci/patches, потом патч.
