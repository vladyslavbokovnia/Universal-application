Очередь патчей. Любой файл *.patch в этой папке при пуше в main применяется автоматически (git apply), проверяется сборкой, коммитится в main и удаляется.
Патчи затрагивают только modules/, app/src, app/build.gradle, build.gradle, settings.gradle, gradle.properties: остальные пути (dep-*, .github) пушатся напрямую.
