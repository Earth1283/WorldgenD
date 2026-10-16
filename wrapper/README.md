# worldgend-wrap

Flag and stdin/stdout front end for worldgend, shipped as one jar. Needs only Java 25+.
It is the quick-launch jar plus a `Wrap` front end, so the engine's `Launcher-Agent-Class`
self-attach still applies and no `-javaagent` flag is needed. It re-launches itself as the
engine child, so there is nothing else to locate or install.

```
java -jar worldgend-wrap.jar --size 64 --seed 123      # one-shot run
printf 'size=64\n' | java -jar worldgend-wrap.jar      # piped stdin: line protocol, implicit run at EOF
java -jar worldgend-wrap.jar --session                  # line protocol
java -jar worldgend-wrap.jar                            # no args on a terminal: the interactive launcher
java -jar worldgend-wrap.jar run size=64                # the quick-launch commands still work
```

Build: `./build.sh` (JDK 25; builds `../../quick-launch-jar` first if its jar is missing).

## Settings

| flag | toml key | default |
| --- | --- | --- |
| `--seed N\|random` | `seed` | 69 |
| `--size N` (multiple of 16) | `size` | 96 |
| `--scheduler orion5.7\|orion5\|mosaic` | `scheduler` | orion5.7 |
| `--heap auto\|N[g]` | `heap` | auto |
| `--workers auto\|N` | `workers` | auto |
| `--save` / `--no-save` | `save` | true |
| `--verbose` / `--no-verbose` | `verbose` | false |
| `--mc-version X` | `mc_version` | 26.1.1 |
| `--home DIR` (env `WORLDGEND_HOME`) | `home` | ./worldgend-data |

Other flags: `--config PATH`, `--format text|json` (text when interactive), `--no-progress`, `--run`, `--session`, `--dump-config`.

Precedence, later wins: defaults, `worldgend.toml`, env, flags, then stdin `set` commands.
`./worldgend.toml` loads automatically; an explicit `--config` that is missing is an error.
The toml is flat and strict (unknown keys are errors). Save one with
`./worldgend-wrap --size 64 --dump-config > worldgend.toml`.

## Modes

- Any setting flag, or `--run`: one run from flags, stdin ignored. Summary on stdout, engine output on stderr.
- `--session`, or no arguments with piped stdin: line protocol below. With `--session`, flags set the initial config.
- If stdin ends with no `run` and no commands other than `set`/`key=value`, one run happens automatically.

## Line protocol

One command per line; blank lines and `#` comments are ignored. Every reply is one JSON line.
A line starting with `{` is JSON: `{"cmd":"set","size":64}`, `{"cmd":"set","key":"size","value":64}`, `{"cmd":"get","key":"size"}`, `{"cmd":"load","path":"x.toml"}`.

| command | effect |
| --- | --- |
| `set <key> <value>`, `set k=v ...`, or bare `k=v ...` | change config |
| `get <key>`, `show` | read config |
| `load [path]` | apply a toml file (default `./worldgend.toml`) |
| `dump` | `{"toml":"..."}` of the effective config |
| `run [k=v ...]` | generate, blocking; config persists afterward |
| `fetch` | download Mojang's server jar (the jar's default version) |
| `ping`, `quit` | |

Replies are `{"ok":true,"cmd":...}` or `{"ok":false,"error":"...","code":N}`; a bad command never ends the session.
During `run`, about once per second: `{"event":"progress","completed","total","elapsed_ms","percent","chunks_per_s"}`
(off with `--no-progress`), then `{"ok":true,"event":"result","cmd":"run","chunks","failed","total_ms","chunks_per_s","ms_per_chunk","mspc":[...],"elapsed_s","log","config"}`.

## Exit codes

| code | meaning |
| --- | --- |
| 0 | ok |
| 2 | bad config, flag, or toml |
| 3 | Java 25+ is missing |
| 4 | generation finished with failed chunks, or no result |
| other | the engine's own exit code |

A session exits with the code of its last run (0 if none ran).

## Test

`./build.sh && python3 -m unittest test_wrapper`. Python is only for the tests; they swap the engine child for a fake via `WORLDGEND_ENGINE_CMD`, so no chunks are generated.
