#!/usr/bin/env python3
"""Package native captures without comparing footage pixels or stretching UI geometry.
Requires Pillow. Geometry JSON comes from EditorRedesignCaptureTest.
"""
import argparse, hashlib, json
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

p = argparse.ArgumentParser()
p.add_argument('--reference', type=Path, required=True)
p.add_argument('--actual', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
for folder in ('comparisons', 'normalized', 'annotated'):
    (a.output / folder).mkdir(parents=True, exist_ok=True)
font = ImageFont.truetype('/System/Library/Fonts/Menlo.ttc', 18)
small = ImageFont.truetype('/System/Library/Fonts/Menlo.ttc', 12)
colors = {'editor-header':'#bfcddd','editor-preview':'#ffbe63','editor-transport':'#92d398',
          'timeline':'#50d9ff','editor-panel':'#c4a0ff','editor-footer':'#ffd36b','editor-tools':'#f090b6'}
records = []
pairs = []
for ref in sorted(a.reference.glob('[0-1][0-9]_*.png')):
    actual = a.actual / ref.name
    r, s = Image.open(ref).convert('RGB'), Image.open(actual).convert('RGB')
    full = Image.new('RGB', (r.width + s.width + 48, max(r.height, s.height) + 56), '#06131f')
    d = ImageDraw.Draw(full)
    d.text((16, 12), 'APPROVED REFERENCE', font=font, fill='white')
    d.text((r.width+32, 12), 'NATIVE ANDROID — '+ref.stem, font=font, fill='white')
    full.paste(r, (8, 48)); full.paste(s, (r.width+32, 48))
    full.save(a.output / 'comparisons' / ref.name)
    # Explicit crops exclude hardware/system bars. Uniform width normalization preserves aspect.
    rc = (40, 125, 1040, 2295)
    # Android root is edge-to-edge and metadata includes its system insets.
    density = json.loads((a.actual / (ref.stem+'.json')).read_text())['density']
    sc = (0, round(52*density), s.width, s.height-round(24*density))
    def normalize(im, crop):
        im = im.crop(crop)
        return im.resize((480, round(im.height * 480 / im.width)), Image.Resampling.LANCZOS)
    nr, ns = normalize(r, rc), normalize(s, sc)
    normalized = Image.new('RGB', (984, max(nr.height, ns.height)+50), '#06131f')
    normalized.paste(nr, (4, 46)); normalized.paste(ns, (500, 46))
    nd = ImageDraw.Draw(normalized)
    nd.text((8, 12), 'REFERENCE | app region, uniform width', font=small, fill='white')
    nd.text((504, 12), 'ANDROID | app region, uniform width', font=small, fill='white')
    normalized.save(a.output / 'normalized' / ref.name)
    annotated = normalized.copy(); ad = ImageDraw.Draw(annotated)
    geometry = json.loads((a.actual / (ref.stem+'.json')).read_text())
    measures = {}
    for tag, color in colors.items():
        if tag not in geometry: continue
        x1,y1,x2,y2 = geometry[tag]
        # Capture root starts at the physical display origin (verified against preview pixels).
        box = (500+x1*480/s.width, 46+(y1-sc[1])*480/s.width,
               500+x2*480/s.width, 46+(y2-sc[1])*480/s.width)
        ad.rectangle(box, outline=color, width=2)
        ad.text((box[0]+4, box[1]+2), tag.replace('editor-', ''), font=small, fill=color, stroke_width=1, stroke_fill='#06131f')
        measures[tag] = {'bounds_px':geometry[tag], 'width_dp':round((x2-x1)/density,2),
                         'height_dp':round((y2-y1)/density,2), 'top_fraction_app':round((y1-sc[1])/(sc[3]-sc[1]),4)}
    # Measured approved landmarks: reference video content is not used as artwork.
    reference_boxes = {'preview':(64,240,1016,817 if ref.stem.startswith('01_') else 778),
                       'toolbar':(64,2132,1016,2295)}
    if not ref.stem.startswith('01_'): reference_boxes['panel']=(64,1254,1016,2046)
    for tag, box in reference_boxes.items():
        x1,y1,x2,y2 = box
        box = (4+(x1-rc[0])*480/(rc[2]-rc[0]),46+(y1-rc[1])*480/(rc[2]-rc[0]),
               4+(x2-rc[0])*480/(rc[2]-rc[0]),46+(y2-rc[1])*480/(rc[2]-rc[0]))
        color = colors.get('editor-'+tag, '#c4a0ff')
        ad.rectangle(box, outline=color, width=2); ad.text((box[0]+4, box[1]+2), tag, font=small, fill=color)
    annotated.save(a.output / 'annotated' / ref.name)
    def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
    records.append({'screen':ref.name, 'actual_dimensions':s.size, 'reference_dimensions':r.size,
                    'actual_sha256':sha(actual), 'reference_sha256':sha(ref), 'reference_path':str(ref),
                    'reference_app_crop':rc,'android_app_crop':sc,'measurements':measures})
    pairs.append(normalized)
for name, images in [('reference-comparison-contact-sheet.jpg',pairs),
                     ('actual-contact-sheet.jpg',[Image.open(a.actual / rec['screen']).convert('RGB') for rec in records])]:
    row_height = 500 if name.startswith('reference') else 920
    sheet = Image.new('RGB',(1600,4*row_height),'#06131f')
    sd = ImageDraw.Draw(sheet)
    for i,im in enumerate(images):
        im = im.copy(); im.thumbnail((392,row_height-40),Image.Resampling.LANCZOS)
        x=(i%4)*400; y=(i//4)*row_height
        sheet.paste(im,(x,y+28)); sd.text((x+4,y+4),records[i]['screen'],font=small,fill='white')
    sheet.save(a.output/name,quality=92)
(a.output/'screenshots.json').write_text(json.dumps(records,indent=2))
print(f'Packaged {len(records)} full-resolution pairs, normalized pairs, and annotated layouts.')
