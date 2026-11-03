#!/usr/bin/env python3
"""Значок хоста: вырезать букву U из тёмной плашки.

Остаются светящиеся линии и под ними тёмная полупрозрачная заливка в форме буквы U; плашка вокруг и
фон прозрачные. Запускается в CI перед сборкой и переписывает ic_launcher_foreground.png.
"""
import sys

import numpy as np
from PIL import Image
from scipy import ndimage

path = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png"
im = Image.open(path).convert("RGBA")
a = np.asarray(im).astype(np.float32)
rgb = a[..., :3]
alpha0 = a[..., 3] / 255.0
lum = (0.2126 * rgb[..., 0] + 0.7152 * rgb[..., 1] + 0.0722 * rgb[..., 2]) * alpha0

# 1. светящиеся линии буквы: яркие пиксели (кольцо, риски и край плашки тусклее и отсекаются ниже)
lines = lum > 70
# 2. замыкаем щели контура и оставляем только самую большую связную фигуру: это сама буква, а кольцо, риски
#    и край плашки отдельные куски и отбрасываются
closed = ndimage.binary_closing(lines, structure=np.ones((3, 3)), iterations=6)
lab0, n0 = ndimage.label(closed, structure=np.ones((3, 3)))
sizes = np.bincount(lab0.ravel())
sizes[0] = 0
closed = lab0 == int(np.argmax(sizes))
lines = lines & closed
lum = lum * ndimage.binary_dilation(closed, iterations=30)
# что не достижимо снаружи этой фигуры, то буква
# берём компоненты фона, которые касаются края кадра
lab, n = ndimage.label(~closed)
border = set(np.unique(np.concatenate([lab[0, :], lab[-1, :], lab[:, 0], lab[:, -1]])))
border.discard(0)
bg = np.isin(lab, list(border))
letter = ~bg
# 3. сглаживаем край буквы
letter_f = ndimage.gaussian_filter(letter.astype(np.float32), 2.0)

# 4. тёмная полупрозрачная заливка внутри буквы + линии по яркости; свечение только рядом с буквой,
#    всё остальное от плашки (кольцо, риски, край) убираем
near = ndimage.gaussian_filter(ndimage.binary_dilation(letter, iterations=22).astype(np.float32), 4.0)
glow = np.clip((lum - 25.0) / 160.0, 0.0, 1.0) * near
fill = 0.62 * letter_f
alpha = np.maximum(fill, glow)
out = np.zeros_like(a)
base = np.array([2.0, 7.0, 17.0], dtype=np.float32)
# цвет: яркие пиксели сохраняют свой цвет, тёмные становятся тёмной заливкой
w = np.clip(glow * 1.4, 0.0, 1.0)[..., None]
out[..., :3] = rgb * w + base * (1.0 - w)
out[..., 3] = alpha * 255.0
Image.fromarray(np.clip(out, 0, 255).astype(np.uint8), "RGBA").save(path, optimize=True)
print("icon written:", path, "letter px:", int(letter.sum()))
