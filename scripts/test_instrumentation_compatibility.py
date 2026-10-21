"""Regression checks for SDK/dialog preflight using deterministic adb responses."""
import pathlib
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from check_instrumentation_compatibility import check


class CompatibilityCheckTest(unittest.TestCase):
    def run_check(self, sdk=36, text='', package='com.videoeditor.core.media.test'):
        def response(command, **kwargs):
            args = command[3:]
            if args == ['shell', 'pm', 'list', 'instrumentation']:
                data = f'instrumentation:{package}/androidx.test.runner.AndroidJUnitRunner (target={package})\n'.encode()
            elif args[:3] == ['shell', 'dumpsys', 'package']:
                data = f'versionCode=0 minSdk=26 targetSdk={sdk}\n'.encode()
            elif args[:2] == ['shell', 'cat']:
                data = f'<hierarchy><node text="{text}" package="android"/></hierarchy>'.encode()
            else:
                data = b'fixture'
            return subprocess.CompletedProcess(command, 0, data, b'')
        with tempfile.TemporaryDirectory() as directory, patch('subprocess.run', side_effect=response) as run:
            try:
                check(pathlib.Path(directory))
            finally:
                self.assertTrue((pathlib.Path(directory) / 'result.json').exists())
                self.assertTrue((pathlib.Path(directory) / 'screen.png').exists())
                for call in run.call_args_list:
                    self.assertNotIn('uninstall', call.args[0])
                    self.assertNotIn('settings', call.args[0])

    def test_modern_target_passes(self):
        self.run_check()

    def test_old_target_fails(self):
        with self.assertRaisesRegex(RuntimeError, 'effective targetSdk=26'):
            self.run_check(sdk=26)

    def test_dialog_fails_even_with_modern_target(self):
        with self.assertRaisesRegex(RuntimeError, 'System compatibility/crash dialog'):
            self.run_check(text='This app was built for an older version of Android.')

    def test_unrelated_instrumentation_is_inventory_only(self):
        self.run_check(sdk=26, package='example.unrelated.test')


if __name__ == '__main__':
    unittest.main()
