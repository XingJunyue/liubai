# 生成一张像课表应用截图的图片，给模拟器里的 OCR 测试用
import sys, glob
from PIL import Image, ImageDraw, ImageFont

font_path = (glob.glob('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc') + glob.glob('/usr/share/fonts/**/NotoSansCJK*.ttc', recursive=True))[0]
W, H = 1080, 2000
img = Image.new('RGB', (W, H), (250, 250, 250))
d = ImageDraw.Draw(img)
f = lambda s: ImageFont.truetype(font_path, s)
left, top, colw, rowh = 90, 170, 141, 140
days = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
d.text((30, 40), '第 12 周', font=f(44), fill=(30, 30, 30))
for i, t in enumerate(days):
    x = left + i * colw + colw // 2
    d.text((x, 100), t, font=f(32), fill=(60, 60, 60), anchor='mm')
    d.text((x, 140), f'11/{16 + i}', font=f(22), fill=(140, 140, 140), anchor='mm')
for n in range(1, 13):
    y = top + (n - 1) * rowh + rowh // 2
    d.text((left // 2, y), str(n), font=f(30), fill=(90, 90, 90), anchor='mm')
# (星期, 起始节, 节数, 课名, 地点)
courses = [
    (1, 1, 2, '高等数学', '@教1-201'), (1, 5, 2, '大学物理', '@理A305'),
    (2, 3, 2, '大学英语', '@外语楼302'), (3, 1, 2, '线性代数', '@教2-108'),
    (4, 7, 2, '程序设计', '@机房C3'), (5, 9, 3, '形势与政策', '@大礼堂'),
]
colors = [(255, 214, 196), (198, 230, 206), (210, 220, 245), (250, 232, 180), (230, 210, 240), (200, 235, 235)]
for k, (day, s, n, name, room) in enumerate(courses):
    x0 = left + (day - 1) * colw + 4; y0 = top + (s - 1) * rowh + 4
    d.rounded_rectangle([x0, y0, x0 + colw - 8, y0 + n * rowh - 8], 14, fill=colors[k])
    # 课名太长就折行，像真实课表
    lines = [name[i:i + 4] for i in range(0, len(name), 4)]
    yy = y0 + 14
    for ln in lines:
        d.text((x0 + 10, yy), ln, font=f(30), fill=(40, 40, 40)); yy += 40
    d.text((x0 + 10, yy + 6), room, font=f(22), fill=(80, 80, 80))
img.save(sys.argv[1])
print('saved', sys.argv[1])
