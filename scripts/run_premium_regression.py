#!/usr/bin/env python3
"""Required sequential suites with immutable external logs and XML snapshots."""
import pathlib,subprocess,argparse,shutil,json,time,hashlib
from check_instrumentation_compatibility import check
import os
serial = os.environ.get("ANDROID_SERIAL", "emulator-5554")
os.environ["ANDROID_SERIAL"] = serial
p=argparse.ArgumentParser();p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--start',default='host');a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
identity = dict(source_sha=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
    branch=subprocess.check_output(['git','branch','--show-current'],text=True).strip())
(a.output/'source-identity.json').write_text(json.dumps(identity,indent=2))
commands=[('host',['test','--rerun-tasks']),('media',[':core:media:connectedDebugAndroidTest']),('editor',[':feature:editor:connectedDebugAndroidTest']),('app-debug',[':app:connectedDebugAndroidTest']),('app-release',['-Pvideoeditor.instrumentRelease=true',':app:connectedReleaseAndroidTest']),('builds',[':app:assembleDebug',':app:assembleRelease',':app:bundleRelease'])]
start=next(i for i,(name,_) in enumerate(commands) if name==a.start)
results=json.loads((a.output/'commands.json').read_text())[:start] if start else []
for name,args in commands[start:]:
    if name in ('media', 'editor', 'app-debug', 'app-release'):
        # Replace both known self-instrumenting library APKs before preflight.
        # install -r preserves data; never uninstall/clear the consumer app.
        with (a.output / f'{name}-test-target-build.txt').open('w') as log:
            subprocess.run(['./gradlew', ':core:media:assembleDebugAndroidTest', ':feature:editor:assembleDebugAndroidTest'], stdout=log, stderr=subprocess.STDOUT, check=True)
        for apk in ('core/media/build/outputs/apk/androidTest/debug/media-debug-androidTest.apk', 'feature/editor/build/outputs/apk/androidTest/debug/editor-debug-androidTest.apk'):
            subprocess.run(['adb', '-s', serial, 'install', '-r', apk], check=True)
        check(a.output / 'compatibility' / name, serial)
    if name in ('app-debug', 'app-release') and subprocess.run(['adb','-s',serial,'shell','pm','path','com.videoeditor'],capture_output=True,text=True).stdout.strip():
        # Restore the existing microphone-denial test's stated precondition,
        # retaining all project documents and source media.
        subprocess.run(['adb','-s',serial,'shell','pm','revoke','com.videoeditor','android.permission.RECORD_AUDIO'],check=True)
        subprocess.run(['adb','-s',serial,'shell','pm','clear-permission-flags','com.videoeditor','android.permission.RECORD_AUDIO','user-set','user-fixed'],check=True)
    if name in ('media', 'editor', 'app-debug', 'app-release'):
        args = ['-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true', *args]
    started=time.monotonic()
    with (a.output/f'{name}.txt').open('w') as log:
        result=subprocess.Popen(['./gradlew',*args],stdout=log,stderr=subprocess.STDOUT)
        while result.poll() is None:
            try:
                result.wait(timeout=15)
            except subprocess.TimeoutExpired:
                if name in ('media', 'editor', 'app-debug', 'app-release'):
                    windows = subprocess.run(['adb', '-s', serial, 'shell', 'dumpsys', 'window', 'windows'], capture_output=True, text=True, check=True).stdout
                    if 'DeprecatedTargetSdkVersionDialog' in windows or 'AppNotRespondingDialog' in windows or 'AppErrorDialog' in windows:
                        evidence = a.output / 'compatibility' / name / 'blocked'
                        evidence.mkdir(parents=True, exist_ok=True)
                        (evidence / 'windows.txt').write_text(windows)
                        try:
                            check(evidence, serial)
                        finally:
                            result.terminate()
                            result.wait(timeout=60)
                        raise RuntimeError(f'System dialog blocked {name}; see {evidence}')
    if name in ('media', 'editor', 'app-debug', 'app-release'):
        subprocess.run(['adb', '-s', serial, 'logcat', '-d'], stdout=(a.output/f'{name}-logcat.txt').open('w'), check=True)
    snapshot=a.output/'xml'/name
    for pattern in (['**/build/test-results/**/TEST-*.xml'] if name=='host' else ['**/build/outputs/androidTest-results/connected/**/*.xml']):
        for file in (file for root in ('app', 'core', 'editing', 'feature')
                     for file in pathlib.Path(root).glob(pattern)):
            # Snapshot all existing results; totals must be filtered to the suite's module/variant.
            dest=snapshot/file;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(file,dest)
    apk_paths = {
        'media':['core/media/build/outputs/apk/androidTest/debug/media-debug-androidTest.apk'],
        'editor':['feature/editor/build/outputs/apk/androidTest/debug/editor-debug-androidTest.apk'],
        'app-debug':['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'],
        'app-release':['app/build/outputs/apk/release/app-release.apk','app/build/outputs/apk/androidTest/release/app-release-androidTest.apk'],
        'builds':['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/release/app-release-unsigned.apk',
                  'app/build/outputs/bundle/release/app-release.aab'],
    }.get(name,[])
    hashes={}
    for path in apk_paths:
        artifact=pathlib.Path(path)
        if artifact.is_file():
            hashes[path]=hashlib.sha256(artifact.read_bytes()).hexdigest()
            target=a.output/'artifacts'/name/artifact.name
            target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(artifact,target)
    (a.output/f'{name}-artifact-hashes.json').write_text(json.dumps(hashes,indent=2))
    results.append(dict(name=name,command=['./gradlew',*args],exit_code=result.returncode,elapsed_seconds=time.monotonic()-started,
        source_sha=identity['source_sha'],artifact_hashes=hashes))
    (a.output/'commands.json').write_text(json.dumps(results,indent=2));print(name,result.returncode,flush=True)
    if result.returncode:raise SystemExit(result.returncode)
