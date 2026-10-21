#!/usr/bin/env python3
"""Read-only independent MP4 report. Pass the actual exported MP4, not a screen recording.
Requires ffprobe on PATH. Reports structure; does not certify a third-party player's behavior.
"""
import argparse
import collections
import json
from pathlib import Path
import struct
import subprocess


def boxes(path):
    entries = []
    with path.open('rb') as file:
        def walk(start, end, parent=''):
            cursor = start
            while cursor + 8 <= end:
                file.seek(cursor)
                size, name = struct.unpack('>I4s', file.read(8))
                name = name.decode('ascii', 'replace')
                header = 8
                if size == 1:
                    size = struct.unpack('>Q', file.read(8))[0]
                    header = 16
                elif size == 0:
                    size = end - cursor
                if size < header or cursor + size > end:
                    raise ValueError(f'Invalid box size at {cursor}')
                entry = dict(path=parent + '/' + name, offset=cursor, size=size)
                entries.append(entry)
                if name in {'stss', 'stts', 'ctts', 'stsc', 'stsz', 'stco', 'co64'}:
                    file.seek(cursor + header + 4)
                    entry['fixed_sample_size' if name == 'stsz' else 'entry_count'] = struct.unpack('>I', file.read(4))[0]
                    # stsz first field is the fixed sample size; other listed boxes use entry count.
                    if name == 'stsz':
                        entry['sample_count'] = struct.unpack('>I', file.read(4))[0]
                if name in {'mvhd', 'mdhd'}:
                    file.seek(cursor + header)
                    version = file.read(1)[0]
                    file.seek(cursor + header + (20 if version == 1 else 12))
                    entry['timescale'] = struct.unpack('>I', file.read(4))[0]
                if name in {'moov', 'trak', 'mdia', 'minf', 'stbl', 'edts', 'mvex', 'moof', 'traf'}:
                    walk(cursor + header, cursor + size, entry['path'])
                cursor += size
        walk(0, path.stat().st_size)
    return entries


def inspect(path):
    raw = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-show_streams', '-show_format',
        '-show_packets', '-show_frames', '-show_entries',
        'format=duration,size,format_name:stream=index,codec_type,codec_name,width,height,pix_fmt,profile,level,r_frame_rate,avg_frame_rate,time_base,start_time,duration,nb_frames,has_b_frames,sample_rate,channels:stream_tags=rotate:stream_side_data=rotation:packet=stream_index,pts_time,dts_time,duration_time,flags,pos:frame=stream_index,key_frame,best_effort_timestamp_time,pict_type',
        '-of', 'json', str(path)], text=True))
    # ffprobe combines frames and packets when both switches are requested.
    items = raw.get('packets_and_frames', [])
    packets = raw.get('packets', []) + [x for x in items if x.get('type') == 'packet']
    frames = raw.get('frames', []) + [x for x in items if x.get('type') == 'frame']
    reports = []
    for stream in raw.get('streams', []):
        index = stream['index']
        track = [p for p in packets if p['stream_index'] == index]
        dts = [float(p['dts_time']) for p in track if 'dts_time' in p]
        pts = [float(p['pts_time']) for p in track if 'pts_time' in p]
        keys = sorted(float(p['pts_time']) for p in track if stream.get('codec_type') == 'video' and 'K' in p.get('flags', '') and 'pts_time' in p)
        cadence = sorted(float(f['best_effort_timestamp_time']) for f in frames
            if f['stream_index'] == index and 'best_effort_timestamp_time' in f)
        intervals = collections.Counter(round(b-a, 6) for a, b in zip(cadence, cadence[1:]))
        reports.append(dict(stream=stream, packet_count=len(track),
            backward_dts=sum(b<a for a,b in zip(dts,dts[1:])),
            packet_pts_reordering=sum(b<a for a,b in zip(pts,pts[1:])),
            keyframe_count=len(keys), keyframe_times_seconds=keys,
            max_keyframe_gap_seconds=max((b-a for a,b in zip(keys,keys[1:])), default=None),
            decoded_frame_intervals_seconds=dict(intervals.most_common(12))))
    positioned = sorted((p for p in packets if 'pos' in p), key=lambda p: int(p['pos']))
    interleave = []
    for index in sorted({p['stream_index'] for p in positioned}):
        positions = [int(p['pos']) for p in positioned if p['stream_index'] == index]
        longest = current = 0
        for packet in positioned:
            current = current + 1 if packet['stream_index'] == index else 0
            longest = max(longest, current)
        interleave.append(dict(stream_index=index, max_consecutive_packets_in_file=longest,
            max_byte_gap_between_samples=max((b-a for a,b in zip(positions,positions[1:])), default=None)))
    return dict(file=str(path.resolve()), format=raw.get('format'), tracks=reports, interleave=interleave, boxes=boxes(path),
        limits='PTS reordering can be legal with B frames; GOP requests need measured output. Structural inspection alone does not establish accurate external-player seeking.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mp4', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = json.dumps(inspect(args.mp4), indent=2)
    if args.output:
        args.output.write_text(result + '\n')
    else:
        print(result)
