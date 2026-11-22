from pathlib import Path
from hashlib import sha256
from PIL import Image, ImageOps, ImageDraw
import csv
import sys

audit = Path("D:/Work/code/iris-codex-audit-round2")
out = audit / "standards-evidence" / "visual-sheets"
out.mkdir(exist_ok=True)
old = Path("D:/Work/code/iris-codex-audit-7dc3422b/freeze-screenshots")
new = audit / "checkout/target/ui-freeze"
supplement = audit / "standards-evidence/candidate-visual"
workbench = audit / "standards-evidence/workbench"
states = audit / "standards-evidence/system-states"

def sheet(name, files, cols, tw=500, th=290, crop_height=None):
    if not files or not all(p.exists() for p in files):
        return
    rows = (len(files)+cols-1)//cols
    img = Image.new("RGB", (cols*tw, rows*(th+22)), "#263041")
    draw = ImageDraw.Draw(img)
    for i, path in enumerate(files):
        with Image.open(path) as source:
            if crop_height:
                source = source.crop((0, 0, source.width, min(source.height,crop_height)))
            im = ImageOps.contain(source.convert("RGB"), (tw-8, th-4))
            x, y = i%cols*tw, i//cols*(th+22)
            img.paste(im, (x+(tw-im.width)//2,y+22))
            draw.text((x+7,y+4), path.stem, fill="white")
    img.save(out / (name + ".jpg"), quality=90)

groups = [
    ["home", "agent", "news"],
    ["diagnosis", "records", "profile"],
    ["admin-dashboard", "admin-data-analysis", "admin-users"],
    ["admin-diagnosis", "admin-knowledge", "admin-news"],
]
themes = ["light","dark"]
for tag, folder in [("round1",old),("round2",new)]:
    files = sorted(folder.glob("*.png"))
    if files:
        rows = []
        for path in files:
            with Image.open(path) as image:
                image.verify()
            with Image.open(path) as image:
                rows.append([path.name,*image.size,sha256(path.read_bytes()).hexdigest()])
        with (out / (tag+"-inventory.csv")).open("w", newline="") as f:
            writer=csv.writer(f)
            writer.writerow(["path","png_width","png_height","sha256"])
            writer.writerows(rows)
    for i, routes in enumerate(groups,1):
        for theme in themes:
            paths = [folder/f"{route}-{theme}-{width}.png" for route in routes for width in (390,900,1440)]
            sheet(f"{tag}-freeze-{i}-{theme}", paths, 3, tw=445, th=300, crop_height=900)

for theme in themes:
    sheet("round2-hero-"+theme, [supplement/f"home-hero-{theme}-{w}.png" for w in (390,820,1440,1920)], 2, tw=672, th=445)
    sheet("round2-provenance-"+theme, [supplement/f"admin-provenance-{theme}-{w}.png" for w in (390,768,1440,1920)], 2, tw=625, th=470)
    for state in ["completed", "inspector"]:
        sheet(f"round2-workbench-{state}-{theme}", [workbench/f"{state}-{theme}-{w}.png" for w in (390,768,820,900,1440,1920)], 3, tw=485, th=420, crop_height=1080)
    state_names = ["login-5xx","news-first-502","news-empty","news-cached-refresh-502"]
    state_files=[states/f"{name}-{theme}-{w}.png" for name in state_names for w in (375,1440)]
    sheet("round2-system-states-"+theme, [p for p in state_files if p.exists()], 2, tw=652, th=460, crop_height=900)
print("Old freeze PNGs:", len(list(old.glob("*.png"))))
print("Candidate freeze PNGs:", len(list(new.glob("*.png"))))
print("Visual review sheets:", len(list(out.glob("*.jpg"))))
