#!/usr/bin/env python3
"""Заглушка: основной патч браузера уже применён в main. Нужна только потому, что workflow
apply-browser-patch.yml запускает ci/patches/browser_hub.py первым шагом."""
print("browser_hub: уже применён, ничего не делаю")
