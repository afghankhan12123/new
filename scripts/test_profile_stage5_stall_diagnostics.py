"""Fault-path checks without importing the collector's command-line entry point."""
import ast
import json
from pathlib import Path
import subprocess
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch


def diagnostic_function(output):
    source = Path(__file__).with_name('profile_stage5_seek.py')
    node = next(n for n in ast.parse(source.read_text()).body
                if isinstance(n, ast.FunctionDef) and n.name == 'capture_stall_diagnostics')
    namespace = dict(a=SimpleNamespace(output=output), subprocess=subprocess, time=time, json=json)
    exec(compile(ast.Module(body=[node], type_ignores=[]), str(source), 'exec'), namespace)
    return namespace['capture_stall_diagnostics']


class StallDiagnosticTest(unittest.TestCase):
    def run_capture(self, timeout_native=False):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        output = Path(temporary.name)
        commands = []

        def run(command, **kwargs):
            commands.append(command)
            self.assertEqual(kwargs['timeout'], 25)
            if command[-3:] == ['-A', '-o', 'PID,NAME']:
                stdout = ('PID NAME\n12 com.videoeditor\n'
                          '34 android.hardware.media.c2@1.0-service-goldfish\n56 unrelated\n')
            else:
                stdout = 'observed\n'
            if timeout_native and 'debuggerd' in command:
                raise subprocess.TimeoutExpired(command, 25)
            return SimpleNamespace(returncode=0, stdout=stdout, stderr='')

        with patch.object(subprocess, 'run', side_effect=run):
            diagnostic_function(output)('three-videos')
        return output / 'three-videos-stall-diagnostics', commands

    def test_stacks_precede_service_dumps_and_have_clock_brackets(self):
        directory, commands = self.run_capture()
        tails = [command[4:] for command in commands]
        self.assertEqual(tails[:5], [
            ['ps', '-A', '-o', 'PID,NAME'], ['cat', '/proc/uptime'],
            ['su', '0', 'debuggerd', '-b', '12'],
            ['su', '0', 'debuggerd', '-b', '34'], ['cat', '/proc/uptime']])
        self.assertNotIn(['dumpsys', 'media.codec'], tails)
        self.assertTrue((directory / 'surfaceflinger.json').exists())
        self.assertFalse(any(command[-1] == '56' for command in commands))

    def test_scheduler_events_are_opt_in_and_periodic_flush_is_preserved(self):
        source = Path(__file__).with_name('profile_stage5_seek.py')
        nodes = ast.parse(source.read_text()).body
        config = next(n for n in nodes if isinstance(n, ast.Assign)
                      and any(isinstance(t, ast.Name) and t.id == 'config' for t in n.targets))
        opt_in = next(n for n in nodes if isinstance(n, ast.If)
                      and isinstance(n.test, ast.Attribute) and n.test.attr == 'scheduler_trace')
        for enabled in (False, True):
            namespace = dict(a=SimpleNamespace(scheduler_trace=enabled))
            exec(compile(ast.Module(body=[config, opt_in], type_ignores=[]),
                         str(source), 'exec'), namespace)
            self.assertEqual('sched/sched_switch' in namespace['config'], enabled)
            self.assertEqual('sched/sched_waking' in namespace['config'], enabled)
            self.assertIn('flush_period_ms: 1000', namespace['config'])

    def test_native_timeout_is_retained_and_later_observations_continue(self):
        directory, commands = self.run_capture(timeout_native=True)
        for pid in ('12', '34'):
            record = json.loads((directory / f'native-stack-{pid}.json').read_text())
            self.assertIn('TimeoutExpired', record['error'])
            self.assertGreaterEqual(record['completed_host_ns'], record['started_host_ns'])
        self.assertEqual(commands[-1][-2:], ['dumpsys', 'thermalservice'])
        self.assertTrue((directory / 'resources.json').exists())


if __name__ == '__main__':
    unittest.main()
