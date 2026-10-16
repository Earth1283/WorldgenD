import json
import os
import stat
import subprocess
import tempfile
import tomllib
import unittest
from pathlib import Path

JAR = Path(__file__).with_name("worldgend-wrap.jar")

FAKE_ENGINE = """#!/bin/sh
mkdir -p "$WORLDGEND_HOME"
echo "$@" > "$WORLDGEND_HOME/args.txt"
echo "WGD_PROGRESS completed=5 total=10 elapsedMs=500" > "$WORLDGEND_HOME/progress.txt"
sleep 0.6
echo "ok=100 failed=${FAKE_FAILED:-0} totalMs=2000" > "$WORLDGEND_HOME/orion_result.txt"
echo "MSPC fake 1.0" >> "$WORLDGEND_HOME/orion_result.txt"
exit ${FAKE_EXIT:-0}
"""


class WrapperTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.engine = self.dir / "engine"
        self.engine.write_text(FAKE_ENGINE)
        self.engine.chmod(self.engine.stat().st_mode | stat.S_IEXEC)
        self.home = self.dir / "data"

    def tearDown(self):
        self.tmp.cleanup()

    def wrap(self, *args, stdin="", **env):
        e = {k: v for k, v in os.environ.items() if not k.startswith("WORLDGEND_")}
        e.update(WORLDGEND_ENGINE_CMD=str(self.engine), WORLDGEND_HOME=str(self.home), **env)
        return subprocess.run(["java", "-jar", str(JAR), *args], input=stdin, capture_output=True,
                              text=True, cwd=self.dir, env=e, timeout=60)

    def lines(self, r):
        return [json.loads(l) for l in r.stdout.splitlines()]

    def test_flags_run_and_pass_args(self):
        r = self.wrap("--run", "--size", "64", "--seed", "7", "--no-save", "--heap", "8g")
        self.assertEqual(r.returncode, 0, r.stderr)
        out = json.loads(r.stdout)
        self.assertTrue(out["ok"])
        self.assertEqual(out["chunks"], 100)
        self.assertEqual(out["mspc"], ["MSPC fake 1.0"])
        args = (self.home / "args.txt").read_text()
        for want in ("run", "seed=7", "size=64", "heap=8", "save=off", "scheduler=orion5.7"):
            self.assertIn(want, args)

    def test_invalid_size_exits_2_before_jvm(self):
        r = self.wrap("--run", "--size", "50")
        self.assertEqual(r.returncode, 2)
        self.assertFalse(self.home.exists())

    def test_bad_scheduler_and_seed(self):
        self.assertEqual(self.wrap("--run", "--scheduler", "nope").returncode, 2)
        self.assertEqual(self.wrap("--run", "--seed", "abc").returncode, 2)

    def test_failed_chunks_exit_4(self):
        r = self.wrap("--run", FAKE_FAILED="3")
        self.assertEqual(r.returncode, 4)
        self.assertFalse(json.loads(r.stdout)["ok"])

    def test_engine_exit_passthrough(self):
        self.assertEqual(self.wrap("--run", FAKE_EXIT="1").returncode, 1)

    def test_toml_precedence(self):
        (self.dir / "worldgend.toml").write_text('size = 32\nseed = 1\nsave = false\n')
        r = self.wrap("--dump-config", "--seed", "2")
        cfg = tomllib.loads(r.stdout)
        self.assertEqual((cfg["size"], cfg["seed"], cfg["save"]), (32, 2, False))
        r = self.wrap("--dump-config", "--home", "/x/y")
        self.assertEqual(tomllib.loads(r.stdout)["home"], "/x/y")

    def test_toml_env_beats_file(self):
        (self.dir / "worldgend.toml").write_text(f'home = "{self.dir}/other"\n')
        cfg = tomllib.loads(self.wrap("--dump-config").stdout)
        self.assertEqual(cfg["home"], str(self.home))

    def test_toml_unknown_key_and_missing_explicit(self):
        (self.dir / "worldgend.toml").write_text("sise = 32\n")
        self.assertEqual(self.wrap("--dump-config").returncode, 2)
        self.assertEqual(self.wrap("--dump-config", "--config", "nope.toml").returncode, 2)

    def test_session_protocol(self):
        script = "ping\nset size 64\nset seed=9 save=off\nget size\nbogus\nset size 7\nshow\nrun\nquit\n"
        r = self.wrap("--session", stdin=script)
        self.assertEqual(r.returncode, 0, r.stderr)
        out = self.lines(r)
        self.assertEqual(out[0], {"ok": True, "cmd": "ping"})
        self.assertEqual(out[1]["set"], {"size": 64})
        self.assertEqual(out[2]["set"], {"seed": 9, "save": False})
        self.assertEqual(out[3]["value"], 64)
        self.assertFalse(out[4]["ok"])
        self.assertFalse(out[5]["ok"])
        self.assertEqual(out[6]["config"]["size"], 64)
        events = [o for o in out if o.get("event") == "progress"]
        self.assertEqual(events[0]["completed"], 5)
        result = [o for o in out if o.get("event") == "result"][0]
        self.assertTrue(result["ok"])
        self.assertEqual(out[-1]["cmd"], "quit")

    def test_session_implicit_run_on_eof(self):
        r = self.wrap(stdin="size=48\nseed=3\n")
        out = self.lines(r)
        self.assertEqual(out[-1]["event"], "result")
        self.assertIn("size=48", (self.home / "args.txt").read_text())

    def test_session_no_implicit_run_after_command(self):
        out = self.lines(self.wrap(stdin="size=48\nshow\n"))
        self.assertFalse(any(o.get("event") == "result" for o in out))

    def test_session_json_commands_and_dump(self):
        r = self.wrap("--session", stdin='{"cmd":"set","key":"size","value":80}\n{"cmd":"get","key":"size"}\n'
                                         '{"cmd":"set","seed":5}\n{"cmd":"dump"}\n')
        out = self.lines(r)
        self.assertEqual(out[1]["value"], 80)
        self.assertEqual(tomllib.loads(out[3]["toml"])["seed"], 5)

    def test_session_run_failure_sets_exit(self):
        r = self.wrap("--session", "--no-progress", stdin="run\n", FAKE_FAILED="2")
        self.assertEqual(r.returncode, 4)
        out = self.lines(r)
        self.assertFalse(any(o.get("event") == "progress" for o in out))

    def test_session_flags_configure_implicit_run(self):
        r = self.wrap("--session", "--size", "32", stdin="")
        self.assertEqual(self.lines(r)[-1]["config"]["size"], 32)


if __name__ == "__main__":
    unittest.main()
