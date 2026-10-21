#!/usr/bin/env python3
"""Independent FFmpeg marker/PTS verification. Fails on any frame mismatch."""
import argparse
from pathlib import Path
from io import BytesIO
import json
import re
import subprocess
from PIL import Image, ImageStat, ImageDraw

p = argparse.ArgumentParser()
p.add_argument("run", type=Path)
p.add_argument("--preview-frames", action="store_true", help="Verify retained paused and continuous preview PNG identities")
p.add_argument("--resolution", choices=("RES_720P", "RES_1080P"), help="Verify a single retained export from an incomplete failed attempt")
p.add_argument("--layers", action="store_true", help="Verify the dedicated fixed-position moving-PiP/caption fixture")
p.add_argument("--all-frames", action="store_true", help="Verify all 120 encoded frame identities")
p.add_argument("--output", type=Path, required=True, help="New immutable verification report")
args = p.parse_args()
if args.output.exists():
    raise SystemExit(f"Refusing to overwrite {args.output}")
assets = Path(__file__).resolve().parents[1] / "core/media/src/androidTest/assets"

source_checks = {}
# Retain compressed PNG frames, rather than decoded RGB surfaces. This performs
# a complete FFmpeg decode once per file without holding hundreds of HD bitmaps.
decoded_pngs = {}

def decode(path, index):
    key = str(path)
    if key not in decoded_pngs:
        data = subprocess.check_output(["ffmpeg", "-v", "error", "-i", key,
            "-map", "0:v:0", "-fps_mode", "passthrough", "-f", "image2pipe", "-vcodec", "png", "-"])
        frames = []
        cursor = 0
        while cursor < len(data):
            start = cursor
            assert data[cursor:cursor+8] == b"\x89PNG\r\n\x1a\n"
            cursor += 8
            while True:
                length = int.from_bytes(data[cursor:cursor+4], "big")
                kind = data[cursor+4:cursor+8]
                cursor += length + 12
                assert cursor <= len(data), (key, "truncated PNG stream")
                if kind == b"IEND":
                    break
            frames.append(data[start:cursor])
        decoded_pngs[key] = frames
    image = Image.open(BytesIO(decoded_pngs[key][index])).convert("RGB")
    if path.name.startswith("transition_identity_") and (str(path),index) not in source_checks:
        lane = int(path.stem.rsplit("_",1)[1])
        actual = identity(image,lane,1)
        assert actual == index, ("fixture frame identity",str(path),index,actual)
        assert identity(image,1-lane,0) is None
        source_checks[(str(path),index)] = dict(source=path.name,frame_index=index,identity=actual)
    return image

from transition_pixels import sample, identity

project = json.loads((args.run / "project.json").read_text())
clips = project["timeline"]["videoClips"]
transition = project["timeline"]["transitions"][0]
join_us = clips[1]["timelineStartMicros"]
duration_us = transition["durationMicros"]
start_us = join_us - duration_us // 2
outgoing_offset = clips[0]["sourceStartMicros"] / 1e6
incoming_offset = (clips[1]["sourceStartMicros"] - join_us) / 1e6
results = []
failures = []
for resolution, dimensions in (("RES_720P", (1280,720)), ("RES_1080P", (1920,1080))):
    if args.resolution and args.resolution != resolution:
        continue
    path = args.run / f"{resolution}.mp4"
    meta = json.loads(subprocess.check_output(["ffprobe", "-v", "error", "-select_streams", "v:0",
        "-show_frames", "-show_streams", "-of", "json", str(path)]))
    stream = meta["streams"][0]
    assert (stream["width"], stream["height"]) == dimensions
    assert abs(float(stream["duration"])-4) < 1/30
    pts = [float(f["best_effort_timestamp_time"]) for f in meta["frames"]]
    assert len(pts) == 120 and all(abs(t-i/30) < .000002 for i,t in enumerate(pts))
    for requested in ([i/30 for i in range(120)] if args.all_frames else (1.2,1.5,1.75,2,2.25,2.466667,2.5,2.8)):
        index = min(range(len(pts)), key=lambda i: abs(pts[i]-requested))
        t = pts[index]
        alpha = max(0, min(1, (t*1e6-start_us)/duration_us))
        actual = decode(path, index)
        identities = [identity(actual,0,1-alpha), identity(actual,1,alpha)]
        expected_ids = [round((outgoing_offset+t)*30) if 1-alpha > 4/255 else None, round((incoming_offset+t)*30) if alpha > 4/255 else None]
        row = dict(resolution=resolution, requested=requested, pts=t, alpha=alpha, identities=identities, expected=expected_ids)
        results.append(row)
        if identities != expected_ids:
            failures.append(row)
        if alpha == 0:
            reference = decode(assets / "transition_identity_0.mp4", round((outgoing_offset+t)*30)).resize(actual.size)
        elif alpha == 1:
            reference = decode(assets / "transition_identity_1.mp4", round((incoming_offset+t)*30)).resize(actual.size)
        else:
            a = decode(assets / "transition_identity_0.mp4", round((outgoing_offset+t)*30)).resize(actual.size)
            b = decode(assets / "transition_identity_1.mp4", round((incoming_offset+t)*30)).resize(actual.size)
            reference = Image.blend(a,b,alpha)
        rgb_actual = actual
        if args.layers:
            for overlay in sorted(project["timeline"]["overlayClips"], key=lambda o: o.get("zIndex", 0)):
                transform = overlay["transform"]
                assert overlay.get("playbackSpeed", 1) == 1 and overlay.get("opacity", 1) == 1
                media = next(m for m in project["mediaRefs"] if m["id"] == overlay["mediaRefId"])
                source = assets / Path(media["uri"]).name
                source_index = round((overlay.get("sourceStartMicros", 0) / 1e6 + t - overlay.get("timelineStartMicros", 0)/1e6) * 30)
                scale = transform["scale"]
                width, height = round(actual.width*scale), round(actual.height*scale)
                left = round(actual.width*((1+transform.get("offsetX",0))/2-scale/2))
                top = round(actual.height*((1+transform.get("offsetY",0))/2-scale/2))
                crop = actual.crop((left,top,left+width,top+height))
                lane = int(source.stem.rsplit("_",1)[1])
                assert identity(crop,lane,1) == source_index, ("moving PiP identity",resolution,index,lane,source_index)
                reference.paste(decode(source,source_index).resize((width,height)), (left,top))
            # The fixed native TOP caption is independently checked for yellow
            # pixels over the PiP. Its declared rectangle is excluded from RGB
            # source comparison because the host does not rasterize Android fonts.
            assert len(project["timeline"]["textOverlays"]) == 1
            caption = project["timeline"]["textOverlays"][0]
            assert caption["text"] == "TOP" and caption["positionX"] == .85 and caption["positionY"] == .8
            rectangle = tuple(round(v*s) for v,s in zip((.7,.65,1,.95),(actual.width,actual.height,actual.width,actual.height)))
            pixels = actual.crop(rectangle).getdata()
            yellow = sum(r>180 and g>180 and b<100 for r,g,b in pixels)
            assert yellow > 200, ("caption above PiP",resolution,index,yellow)
            row["caption_yellow_pixels"] = yellow
            row["caption_rgb_exclusion"] = rectangle
            rgb_actual = actual.copy()
            ImageDraw.Draw(rgb_actual).rectangle(rectangle, fill=(0,0,0))
            ImageDraw.Draw(reference).rectangle(rectangle, fill=(0,0,0))
        aa = list(rgb_actual.resize((128,72)).getdata()); bb = list(reference.resize((128,72)).getdata())
        error = sum(sum(abs(x-y) for x,y in zip(u,v)) for u,v in zip(aa,bb))/len(aa)
        row["rgb_channel_l1"] = error
        if error >= 30:
            failures.append(row)
def verify_preview_layers(actual, t):
    if not args.layers:
        return {}
    checked = []
    for overlay in project["timeline"]["overlayClips"]:
        transform = overlay["transform"]
        media = next(m for m in project["mediaRefs"] if m["id"] == overlay["mediaRefId"])
        source = Path(media["uri"])
        lane = int(source.stem.rsplit("_",1)[1])
        scale = transform["scale"]
        width, height = round(actual.width*scale), round(actual.height*scale)
        left = round(actual.width*((1+transform.get("offsetX",0))/2-scale/2))
        top = round(actual.height*((1+transform.get("offsetY",0))/2-scale/2))
        value = identity(actual.crop((left,top,left+width,top+height)),lane,1)
        expected = round((overlay.get("sourceStartMicros",0)/1e6+t-overlay.get("timelineStartMicros",0)/1e6)*30)
        assert value == expected, ("preview PiP identity",overlay["id"],t,value,expected)
        checked.append(dict(overlay=overlay["id"],identity=value,expected=expected))
    rect = tuple(round(v*s) for v,s in zip((.7,.65,1,.95),(actual.width,actual.height,actual.width,actual.height)))
    yellow = sum(r>180 and g>180 and b<100 for r,g,b in actual.crop(rect).getdata())
    assert yellow > 200, ("preview caption",t,yellow)
    return dict(pip_identities=checked,caption_yellow_pixels=yellow)

if args.preview_frames:
    assert not (args.run / "continuous-mismatch.png").exists(), "Retained continuous identity failure"
    for path in sorted(args.run.glob("preview-*-actual.png")):
        label = path.name.removesuffix("-actual.png")
        time_us = int((args.run / f"{label}.txt").read_text().split("time=")[1].split()[0])
        # The preview controller floors requests to milliseconds. The sink
        # skips decoded frames before that reset time and renders the first
        # available cadence frame at or after it, rather than nearest-frame seek.
        index = ((time_us//1000)*1000*30 + 999999)//1000000
        t = index/30
        alpha = max(0,min(1,(t*1e6-start_us)/duration_us))
        actual = Image.open(path).convert("RGB")
        identities = [identity(actual,0,1-alpha),identity(actual,1,alpha)]
        expected = [round((outgoing_offset+t)*30) if 1-alpha>4/255 else None,
                    round((incoming_offset+t)*30) if alpha>4/255 else None]
        row = dict(preview=path.name,requested_us=time_us,frame_index=index,pts=t,alpha=alpha,identities=identities,expected=expected)
        row.update(verify_preview_layers(actual,t))
        results.append(row)
        if identities != expected: failures.append(row)
    for path in sorted(args.run.glob("continuous-*.png")):
        match = re.fullmatch(r"continuous-(?:layer-)?(\d+)(?:-actual)?\.png", path.name)
        if match is None:
            continue
        index = int(match[1]);t=index/30
        alpha=max(0,min(1,(t*1e6-start_us)/duration_us))
        actual=Image.open(path).convert("RGB")
        identities=[identity(actual,0,1-alpha),identity(actual,1,alpha)]
        expected=[round((outgoing_offset+t)*30) if 1-alpha>4/255 else None,
                  round((incoming_offset+t)*30) if alpha>4/255 else None]
        row=dict(continuous=path.name,frame_index=index,pts=t,alpha=alpha,identities=identities,expected=expected)
        row.update(verify_preview_layers(actual,t))
        results.append(row)
        if identities!=expected:failures.append(row)
with args.output.open("x") as report:
    json.dump(dict(results=results, failures=failures, source_checks=list(source_checks.values())),report,indent=2)
assert not failures, failures
print(f"Verified {len(results)} frame checkpoints and {len(source_checks)} fixture frames")
