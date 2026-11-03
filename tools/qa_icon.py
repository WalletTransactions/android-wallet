# Генерирует QA-иконку (openspec android-flavors) из иконки prod:
#   python3 tools/qa_icon.py app/src/main/res/drawable/main_icon.png app/src/qa/res/drawable/main_icon.png
# Зелёная гамма → оранжевая (золото монеты не трогается), плашка «QA» в safe zone. Нужен Pillow.
import sys
from PIL import Image, ImageDraw, ImageFont
src, dst = sys.argv[1], sys.argv[2]
im = Image.open(src).convert("RGBA")
alpha = im.getchannel("A")
H, S, V = im.convert("RGB").convert("HSV").split()
# зелёные/бирюзовые тона (≈70..200°) → оранжевые (≈22°); золото монеты (~45°) не трогаем
mask = H.point(lambda h: 255 if 37 < h < 150 else 0)
H = Image.composite(H.point(lambda h: 16), H, mask)
S = Image.composite(S.point(lambda s: min(255, int(s*1.1))), S, mask)
out = Image.merge("HSV", (H, S, V)).convert("RGB").convert("RGBA"); out.putalpha(alpha)
W = out.size[0]
d = ImageDraw.Draw(out)
# закрыть надпись внизу (вне safe zone adaptive-иконки она всё равно обрезается)
# плашка QA — внутри safe zone (круг 66% по центру)
font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", int(W*0.15))
text = "QA"
tb = d.textbbox((0, 0), text, font=font)
tw, th = tb[2]-tb[0], tb[3]-tb[1]
pw, ph = int(tw + W*0.10), int(th + W*0.07)
cx, cy = W//2, int(W*0.665)
box = [cx-pw//2, cy-ph//2, cx+pw//2, cy+ph//2]
d.rounded_rectangle([box[0]-10, box[1]-10, box[2]+10, box[3]+10], radius=ph//2+10, fill=(255,255,255,255))
d.rounded_rectangle(box, radius=ph//2, fill=(198,40,40,255))
d.text((cx - tw/2 - tb[0], cy - th/2 - tb[1]), text, font=font, fill=(255,255,255,255))
out.save(dst, optimize=True)
