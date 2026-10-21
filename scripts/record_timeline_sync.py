#!/usr/bin/env python3
"""Record the real editor playback regression test, preserving native timing."""
import argparse, pathlib, subprocess, threading, time, json
p=argparse.ArgumentParser(); p.add_argument('--app-apk',required=True); p.add_argument('--test-apk',required=True); p.add_argument('--output',type=pathlib.Path,required=True)
a=p.parse_args(); a.output.mkdir(parents=True,exist_ok=True)
def adb(*args): return subprocess.check_output(['adb',*args],text=True,stderr=subprocess.STDOUT)
adb('install','-r',a.app_apk); adb('install','-r',a.test_apk)
adb('shell','mkdir','-p','/sdcard/Download/VideoEditorEvidence')
adb('shell','touch','/sdcard/Download/VideoEditorEvidence/.nomedia')
remote='/sdcard/Download/VideoEditorEvidence/timeline-sync.mp4'
record=subprocess.Popen(['adb','shell',f'screenrecord --size 540x1200 --bit-rate 2000000 --time-limit 180 {remote} & recorder_pid=$!; echo $recorder_pid; wait $recorder_pid'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
pid=record.stdout.readline().strip()
if not pid.isdecimal(): raise RuntimeError('Missing owned recorder PID')
logfile=(a.output/'logcat.txt').open('w')
logs=subprocess.Popen(['adb','logcat','-v','threadtime','-T','1'],stdout=logfile,stderr=subprocess.STDOUT)
started=time.monotonic()
try:
    result=subprocess.run(['adb','shell','am','instrument','-w','-e','class','com.videoeditor.app.TimelinePlaybackSyncUiTest#continuous_playback_pause_scrub_zoom_end_and_replay_are_visible','com.videoeditor.test/com.videoeditor.app.BenchmarkFixtureRunner'],capture_output=True,text=True)
    (a.output/'instrumentation.txt').write_text(result.stdout+result.stderr)
finally:
    adb('shell','kill','-2',pid); record.communicate(timeout=30)
    logs.terminate(); logs.wait(timeout=10); logfile.close()
adb('pull',remote,str(a.output/'timeline-sync.mp4'))
for name in ('start','playing-eight-seconds','paused','scrub-back','scrub-forward-boundary','pinch-zoom','resumed','end','replay'):
    adb('pull',f'/sdcard/Download/VideoEditorEvidence/timeline-{name}.png',str(a.output/f'{name}.png'))
passed=result.returncode==0 and 'OK (1 test)' in result.stdout and 'FAILURES!!!' not in result.stdout
(a.output/'recording.json').write_text(json.dumps(dict(passed=passed,elapsed_seconds=time.monotonic()-started,native_timing=True,physical_device=False,test='TimelinePlaybackSyncUiTest',audio='screenrecord has no audio; existing media instrumentation verifies audio'),indent=2))
if not passed: raise RuntimeError('Recorded workflow assertions failed; do not present as passing evidence')
print(a.output/'timeline-sync.mp4')
