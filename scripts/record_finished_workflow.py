#!/usr/bin/env python3
"""Record the real connected acceptance workflow; keep native timing and all screens."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import threading
import time

parser = argparse.ArgumentParser()
parser.add_argument('--adb', required=True)
parser.add_argument('--app-apk', required=True)
parser.add_argument('--test-apk', required=True)
parser.add_argument('--output', required=True)
parser.add_argument('--test-class', default='com.videoeditor.app.FinishedWorkflowUiTest')
parser.add_argument('--resolutions', nargs='+', default=['720p', '1080p'])
args = parser.parse_args()
output = Path(args.output).resolve()
output.mkdir(parents=True, exist_ok=True)
repo = Path(__file__).resolve().parents[1]
source_head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip()
source_patch = subprocess.check_output(['git', 'diff', 'HEAD', '--binary'], cwd=repo)
(output / 'source.patch').write_bytes(source_patch)
(output / 'source-head.txt').write_text(source_head + '\n')
artifact_hashes = {name: hashlib.sha256(Path(path).read_bytes()).hexdigest()
    for name, path in [('app_apk_sha256', args.app_apk), ('test_apk_sha256', args.test_apk)]}


def adb(*arguments, **kwargs):
    return subprocess.run([args.adb, *arguments], check=True, **kwargs)

adb('install', '-r', args.app_apk)
adb('install', '-r', args.test_apk)
adb('shell', 'mkdir', '-p', '/sdcard/Download/VideoEditorEvidence')
adb('shell', 'touch', '/sdcard/Download/VideoEditorEvidence/.nomedia')
adb('shell', 'am', 'start', '-W', '-n', 'com.videoeditor/com.videoeditor.app.MainActivity', stdout=subprocess.DEVNULL)
time.sleep(1)
stop = threading.Event()
segments = []
recording_errors = []

def record_segments():
    index = 1
    while not stop.is_set():
        remote = f'/sdcard/Download/VideoEditorEvidence/finished-workflow-{index:02d}.mp4'
        command = f'screenrecord --size 540x1200 --bit-rate 1500000 --time-limit 0 {remote} & workflow_record_pid=$!; echo $workflow_record_pid; wait $workflow_record_pid'
        with subprocess.Popen([args.adb, 'shell', command], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True) as process:
            device_pid = process.stdout.readline().strip()
            if not device_pid.isdecimal():
                raise RuntimeError(f'Screen recorder did not expose its owned PID: {device_pid}')
            while process.poll() is None and not stop.wait(.25):
                pass
            if process.poll() is None:
                adb('shell', 'kill', '-2', device_pid)
            process.communicate(timeout=30)
        local = output / f'finished-workflow-{index:02d}.mp4'
        adb('pull', remote, str(local))
        segments.append(local.name)
        index += 1

def record():
    try:
        record_segments()
    except Exception as error:
        recording_errors.append(str(error))

record_thread = threading.Thread(target=record, daemon=False)
record_thread.start()
started = time.time()
evidence_run = f"recording-{time.time_ns()}"
log_file = (output / 'logcat.txt').open('w')
log_process = subprocess.Popen([args.adb, 'logcat', '-v', 'threadtime', '-T', '1'], stdout=log_file, stderr=subprocess.STDOUT)
try:
    result = subprocess.run([args.adb, 'shell', 'am', 'instrument', '-w', '-e', 'evidenceExportRun', evidence_run, '-e', 'class',
        args.test_class, 'com.videoeditor.test/com.videoeditor.app.BenchmarkFixtureRunner'],
        capture_output=True, text=True, check=False)
    (output / 'instrumentation.txt').write_text(result.stdout + result.stderr)
finally:
    stop.set()
    record_thread.join()
    log_process.terminate()
    log_process.wait(timeout=10)
    log_file.close()
passed = result.returncode == 0 and 'OK (1 test)' in result.stdout and 'FAILURES!!!' not in result.stdout and len(segments) == 1 and not recording_errors
(output / 'recording.json').write_text(json.dumps({
    'source_head': source_head, 'source_patch_sha256': hashlib.sha256(source_patch).hexdigest(),
    **artifact_hashes,
    'passed': passed, 'elapsed_seconds': round(time.time() - started, 3),
    'segments': segments, 'export_evidence_run': evidence_run, 'recording_errors': recording_errors, 'recording_size': '540x1200', 'screenrecord_bit_rate': 1500000,
    'test': args.test_class, 'native_timing_preserved': True, 'screenrecord_time_limit': 0,
    'continuous_capture': len(segments) == 1,
    'audio_note': 'Android screenrecord records the screen; exported MP4 audio is verified separately.'
}, indent=2) + '\n')
if not passed:
    raise SystemExit('The recorded workflow failed; inspect instrumentation.txt. Do not present it as passing evidence.')
for resolution in args.resolutions:
    adb('pull', f'/sdcard/Download/VideoEditorEvidence/{evidence_run}/workflow-export-{resolution}.mp4', str(output / f'workflow-export-{resolution}.mp4'))
print(f'Passing workflow recorded in {len(segments)} original native segments.')
