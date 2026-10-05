import contextlib
import io
import tempfile
import unittest
from pathlib import Path

from check_test_container_limits import main, violations


class ContainerLimitCheckTest(unittest.TestCase):
    def test_rejects_original_failure_and_multiline_calls(self):
        self.assertEqual(violations('cmd.withMemory(256L * 1024 * 1024)\n'
                                    ' .withNanoCPUs\n (500_000_000L);'),
                         [(1, 'withMemory'), (2, 'withNanoCPUs')])

    def test_ignores_comments_and_string_literals(self):
        self.assertEqual(violations('// withMemory(1)\n/* withCpuQuota(1) */\n'
                                    'String s = "withNanoCPUs(1)";\n'
                                    'String t = """\nwithMemory(1)\n""";'), [])

    def test_scans_test_sources_and_returns_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            production = root / 'module/src/main/java/App.java'
            production.parent.mkdir(parents=True)
            production.write_text('withMemory(1);')
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(main(root), 0)
            test = root / 'module/src/integrationTest/java/DbTest.java'
            test.parent.mkdir(parents=True)
            test.write_text('withMemory(1);')
            error = io.StringIO()
            with contextlib.redirect_stderr(error):
                self.assertEqual(main(root), 1)
            self.assertIn('DbTest.java:1: withMemory', error.getvalue())


if __name__ == '__main__':
    unittest.main()
