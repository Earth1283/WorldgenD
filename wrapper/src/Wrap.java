import java.io.BufferedReader;
import java.io.Console;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Flag and stdin/stdout front end for worldgend. Runs the bundled launcher (Main) as a child JVM. */
public final class Wrap {
    static final int EXIT_CONFIG = 2, EXIT_ENV = 3, EXIT_FAILED = 4, MIN_JAVA = 25;
    static final List<String> SCHEDULERS = Arrays.asList("orion5.7", "orion5", "mosaic");
    static final List<String> KEYS = Arrays.asList(
        "seed", "size", "scheduler", "heap", "workers", "save", "verbose", "mc_version", "home");
    static final List<String> JAR_COMMANDS = Arrays.asList(
        "run", "generate", "set", "show", "fetch", "help", "?", "quit", "exit", "q");
    static final Pattern PROGRESS = Pattern.compile("WGD_PROGRESS completed=(\\d+) total=(\\d+) elapsedMs=(\\d+)");
    static final Pattern RESULT = Pattern.compile("ok=(\\d+) failed=(\\d+) totalMs=(\\d+)");
    static final String USAGE = String.join("\n",
        "usage: java -jar worldgend-wrap.jar [options]",
        "  --seed N|random  --size N  --scheduler orion5.7|orion5|mosaic  --heap auto|N[g]",
        "  --workers auto|N  --save|--no-save  --verbose|--no-verbose  --mc-version X  --home DIR",
        "  --config FILE  --format text|json  --no-progress  --dump-config",
        "  --run      one run from flags (default when any setting flag is given)",
        "  --session  line protocol on stdin/stdout (default when stdin is piped)",
        "no arguments on a terminal opens the interactive launcher");

    static PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true);

    static final class WrapError extends RuntimeException {
        final int code;
        WrapError(String msg) { this(msg, EXIT_CONFIG); }
        WrapError(String msg, int code) { super(msg); this.code = code; }
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    public static void main(String[] args) throws Exception {
        try {
            out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        } catch (java.io.UnsupportedEncodingException ignored) { }
        if (javaMajor() < MIN_JAVA) {
            System.err.println("worldgend needs Java " + MIN_JAVA + "+ (running " + System.getProperty("java.version") + ").");
            System.exit(EXIT_ENV);
        }
        boolean passthrough = System.getProperty("worldgend.worker") != null
            || (args.length > 0 && JAR_COMMANDS.contains(args[0]));
        if (passthrough) {
            Main.main(args);
            return;
        }
        System.exit(new Wrap().run(args));
    }

    static int javaMajor() {
        String v = System.getProperty("java.specification.version");
        return Integer.parseInt(v.startsWith("1.") ? v.substring(2) : v);
    }

    static boolean terminal() {
        Console c = System.console();
        if (c == null) return false;
        try {
            return (Boolean) Console.class.getMethod("isTerminal").invoke(c);
        } catch (ReflectiveOperationException e) {
            return true;
        }
    }

    // ---- config ----

    static final class Config {
        final Map<String, Object> values = new LinkedHashMap<>();

        Config() {
            values.put("seed", 69L);
            values.put("size", 96);
            values.put("scheduler", "orion5.7");
            values.put("heap", "auto");
            values.put("workers", "auto");
            values.put("save", true);
            values.put("verbose", false);
            values.put("mc_version", "26.1.1");
            values.put("home", Paths.get("worldgend-data").toAbsolutePath().toString());
            String home = System.getenv("WORLDGEND_HOME");
            if (home != null && !home.isEmpty()) set("home", home);
        }

        static String canonical(String key) {
            String k = key.replace('-', '_');
            if (!KEYS.contains(k)) throw new WrapError("unknown setting '" + key + "' (known: " + String.join(", ", KEYS) + ")");
            return k;
        }

        String set(String key, Object raw) {
            String k = canonical(key);
            Object v;
            switch (k) {
                case "seed": v = seed(raw); break;
                case "size": v = (int) number(k, raw, 16, 1 << 20, 16); break;
                case "scheduler":
                    if (!SCHEDULERS.contains(String.valueOf(raw))) throw new WrapError("scheduler: must be one of " + SCHEDULERS);
                    v = String.valueOf(raw);
                    break;
                case "heap": v = autoOr(k, raw, true); break;
                case "workers": v = autoOr(k, raw, false); break;
                case "save": case "verbose": v = bool(k, raw); break;
                case "mc_version":
                    if (!String.valueOf(raw).matches("[0-9A-Za-z._-]+")) throw new WrapError("mc_version: invalid value '" + raw + "'");
                    v = String.valueOf(raw);
                    break;
                default:
                    if (String.valueOf(raw).trim().isEmpty()) throw new WrapError("home: empty path");
                    v = Paths.get(String.valueOf(raw)).toAbsolutePath().normalize().toString();
            }
            values.put(k, v);
            return k;
        }

        static long seed(Object raw) {
            if ("random".equals(raw)) return new SecureRandom().nextLong();
            return number("seed", raw, Long.MIN_VALUE, Long.MAX_VALUE, 1);
        }

        static Object autoOr(String name, Object raw, boolean gigabytes) {
            String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
            if (s.equals("auto")) return "auto";
            if (gigabytes && s.endsWith("g")) s = s.substring(0, s.length() - 1);
            return (int) number(name, s, 1, Integer.MAX_VALUE, 1);
        }

        static long number(String name, Object raw, long lo, long hi, long multiple) {
            if (raw instanceof Boolean) throw new WrapError(name + ": expected a number, got " + raw);
            long n;
            try {
                n = raw instanceof Number ? ((Number) raw).longValue() : Long.parseLong(String.valueOf(raw).trim());
            } catch (NumberFormatException e) {
                throw new WrapError(name + ": expected a number, got '" + raw + "'");
            }
            if (n < lo || n > hi) throw new WrapError(name + ": out of range");
            if (n % multiple != 0) throw new WrapError(name + ": must be a multiple of " + multiple);
            return n;
        }

        static boolean bool(String name, Object raw) {
            if (raw instanceof Boolean) return (Boolean) raw;
            String s = String.valueOf(raw).toLowerCase(Locale.ROOT);
            if (s.equals("on") || s.equals("true") || s.equals("yes") || s.equals("1")) return true;
            if (s.equals("off") || s.equals("false") || s.equals("no") || s.equals("0")) return false;
            throw new WrapError(name + ": expected on/off, got '" + raw + "'");
        }

        void loadToml(Path path) {
            String text;
            try {
                text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new WrapError("cannot read config " + path + ": " + e.getMessage());
            }
            int n = 0;
            for (String line : text.split("\r?\n")) {
                n++;
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                int eq = t.indexOf('=');
                if (t.startsWith("[") || eq < 0) throw new WrapError(path + ":" + n + ": expected 'key = value'");
                String key = t.substring(0, eq).trim();
                if (!KEYS.contains(key)) throw new WrapError(path + ":" + n + ": unknown key '" + key + "'");
                set(key, tomlValue(t.substring(eq + 1).trim(), path + ":" + n));
            }
        }

        static Object tomlValue(String v, String where) {
            if (v.startsWith("\"")) {
                StringBuilder sb = new StringBuilder();
                int i = 1;
                for (; i < v.length() && v.charAt(i) != '"'; i++) {
                    char c = v.charAt(i);
                    if (c != '\\') { sb.append(c); continue; }
                    char e = ++i < v.length() ? v.charAt(i) : '?';
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case '"': case '\\': sb.append(e); break;
                        case 'u':
                            if (i + 4 >= v.length()) throw new WrapError(where + ": bad escape");
                            sb.append((char) Integer.parseInt(v.substring(i + 1, i + 5), 16));
                            i += 4;
                            break;
                        default: throw new WrapError(where + ": bad escape");
                    }
                }
                if (i >= v.length()) throw new WrapError(where + ": unterminated string");
                tomlTail(v.substring(i + 1), where);
                return sb.toString();
            }
            if (v.startsWith("'")) {
                int end = v.indexOf('\'', 1);
                if (end < 0) throw new WrapError(where + ": unterminated string");
                tomlTail(v.substring(end + 1), where);
                return v.substring(1, end);
            }
            int hash = v.indexOf('#');
            String bare = (hash >= 0 ? v.substring(0, hash) : v).trim();
            if (bare.equals("true")) return true;
            if (bare.equals("false")) return false;
            try {
                return Long.parseLong(bare.replace("_", ""));
            } catch (NumberFormatException e) {
                throw new WrapError(where + ": unsupported value '" + bare + "'");
            }
        }

        static void tomlTail(String tail, String where) {
            String t = tail.trim();
            if (!t.isEmpty() && !t.startsWith("#")) throw new WrapError(where + ": trailing text");
        }

        String toToml() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Object> e : values.entrySet()) {
                sb.append(e.getKey()).append(" = ");
                Json.write(sb, e.getValue());
                sb.append('\n');
            }
            return sb.toString();
        }
    }

    // ---- json ----

    static final class Json {
        private final String s;
        private int i;

        private Json(String s) { this.s = s; }

        static Object parse(String s) {
            Json j = new Json(s);
            Object v = j.value();
            j.ws();
            if (j.i != s.length()) throw j.err("trailing text");
            return v;
        }

        static String toString(Object o) {
            StringBuilder sb = new StringBuilder();
            write(sb, o);
            return sb.toString();
        }

        @SuppressWarnings("unchecked")
        static void write(StringBuilder sb, Object o) {
            if (o == null) sb.append("null");
            else if (o instanceof Map) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    write(sb, e.getKey());
                    sb.append(':');
                    write(sb, e.getValue());
                }
                sb.append('}');
            } else if (o instanceof List) {
                sb.append('[');
                boolean first = true;
                for (Object x : (List<Object>) o) {
                    if (!first) sb.append(',');
                    first = false;
                    write(sb, x);
                }
                sb.append(']');
            } else if (o instanceof Double) {
                double d = (Double) o;
                sb.append(Double.isNaN(d) || Double.isInfinite(d) ? "null" : o.toString());
            } else if (o instanceof Number || o instanceof Boolean) sb.append(o);
            else {
                sb.append('"');
                for (char c : o.toString().toCharArray()) {
                    switch (c) {
                        case '"': sb.append("\\\""); break;
                        case '\\': sb.append("\\\\"); break;
                        case '\n': sb.append("\\n"); break;
                        case '\r': sb.append("\\r"); break;
                        case '\t': sb.append("\\t"); break;
                        default:
                            if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
                    }
                }
                sb.append('"');
            }
        }

        private WrapError err(String m) { return new WrapError("bad json: " + m + " at " + i); }

        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        private char peek() {
            ws();
            if (i >= s.length()) throw err("unexpected end");
            return s.charAt(i);
        }

        private Object value() {
            char c = peek();
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                if (peek() == '}') { i++; return m; }
                while (true) {
                    if (peek() != '"') throw err("expected key");
                    String k = string();
                    if (peek() != ':') throw err("expected ':'");
                    i++;
                    m.put(k, value());
                    char d = peek();
                    i++;
                    if (d == '}') return m;
                    if (d != ',') throw err("expected ',' or '}'");
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                if (peek() == ']') { i++; return l; }
                while (true) {
                    l.add(value());
                    char d = peek();
                    i++;
                    if (d == ']') return l;
                    if (d != ',') throw err("expected ',' or ']'");
                }
            }
            if (c == '"') return string();
            for (String lit : new String[] {"true", "false", "null"}) {
                if (s.startsWith(lit, i)) {
                    i += lit.length();
                    return lit.equals("null") ? null : (Object) Boolean.valueOf(lit);
                }
            }
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String num = s.substring(start, i);
            try {
                return num.matches("-?\\d+") ? (Object) Long.valueOf(num) : (Object) Double.valueOf(num);
            } catch (NumberFormatException e) {
                throw err("unexpected token");
            }
        }

        private String string() {
            StringBuilder sb = new StringBuilder();
            i++;
            while (i < s.length() && s.charAt(i) != '"') {
                char c = s.charAt(i++);
                if (c != '\\') { sb.append(c); continue; }
                if (i >= s.length()) break;
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw err("bad escape");
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: sb.append(e);
                }
            }
            if (i >= s.length()) throw err("unterminated string");
            i++;
            return sb.toString();
        }
    }

    // ---- running jobs ----

    static final class Outcome {
        final Map<String, Object> summary;
        final int code;
        Outcome(Map<String, Object> summary, int code) { this.summary = summary; this.code = code; }
    }

    static final class ProgressTail {
        private final Path path;
        private long offset;

        ProgressTail(Path path) { this.path = path; }

        long[] poll() throws IOException {
            if (!Files.exists(path)) { offset = 0; return null; }
            long size = Files.size(path);
            if (size < offset) offset = 0;
            if (size == offset) return null;
            byte[] data = new byte[(int) (size - offset)];
            try (RandomAccessFile f = new RandomAccessFile(path.toFile(), "r")) {
                f.seek(offset);
                f.readFully(data);
            }
            int end = data.length;
            while (end > 0 && data[end - 1] != '\n') end--;
            offset += end;
            Matcher last = null, m;
            for (String line : new String(data, 0, end, StandardCharsets.UTF_8).split("\n")) {
                m = PROGRESS.matcher(line);
                if (m.find()) last = m;
            }
            return last == null ? null
                : new long[] {Long.parseLong(last.group(1)), Long.parseLong(last.group(2)), Long.parseLong(last.group(3))};
        }
    }

    static List<String> engineCommand(String... jarArgs) {
        List<String> cmd = new ArrayList<>();
        String override = System.getenv("WORLDGEND_ENGINE_CMD");
        if (override != null && !override.trim().isEmpty()) {
            cmd.addAll(Arrays.asList(override.trim().split("\\s+")));
        } else {
            cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
            cmd.add("-jar");
            try {
                cmd.add(Paths.get(Wrap.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
            } catch (java.net.URISyntaxException e) {
                throw new WrapError("cannot locate own jar", EXIT_ENV);
            }
        }
        cmd.addAll(Arrays.asList(jarArgs));
        return cmd;
    }

    static String[] runArgs(Config cfg) {
        Map<String, Object> v = cfg.values;
        return new String[] {"run", "seed=" + v.get("seed"), "size=" + v.get("size"), "scheduler=" + v.get("scheduler"),
            "heap=" + v.get("heap"), "workers=" + v.get("workers"), "save=" + ((Boolean) v.get("save") ? "on" : "off"),
            "verbose=" + ((Boolean) v.get("verbose") ? "on" : "off"), "version=" + v.get("mc_version")};
    }

    static Process spawn(Config cfg, String... jarArgs) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(engineCommand(jarArgs)).redirectErrorStream(true);
        pb.environment().put("WORLDGEND_HOME", (String) cfg.values.get("home"));
        Process p = pb.start();
        p.getOutputStream().close();
        return p;
    }

    static Thread pump(InputStream in) {
        Thread t = new Thread(() -> {
            try {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) { System.err.write(buf, 0, n); System.err.flush(); }
            } catch (IOException ignored) { }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    static int await(Process proc, Consumer<Map<String, Object>> onProgress, Path progress) throws Exception {
        Thread killer = new Thread(proc::destroy);
        Runtime.getRuntime().addShutdownHook(killer);
        Thread drain = pump(proc.getInputStream());
        ProgressTail tail = new ProgressTail(progress);
        try {
            long[] pending = null;
            long lastEmit = 0;
            while (!proc.waitFor(200, TimeUnit.MILLISECONDS)) {
                long[] p = tail.poll();
                if (p != null) pending = p;
                if (onProgress != null && pending != null && System.nanoTime() - lastEmit >= 1_000_000_000L) {
                    long ms = pending[2];
                    onProgress.accept(map("event", "progress", "completed", pending[0], "total", pending[1],
                        "elapsed_ms", ms, "percent", round(100.0 * pending[0] / Math.max(pending[1], 1), 1),
                        "chunks_per_s", ms == 0 ? null : (Object) round(pending[0] * 1000.0 / ms, 2)));
                    pending = null;
                    lastEmit = System.nanoTime();
                }
            }
        } finally {
            if (proc.isAlive()) {
                proc.destroy();
                if (!proc.waitFor(15, TimeUnit.SECONDS)) proc.destroyForcibly();
            }
            try { Runtime.getRuntime().removeShutdownHook(killer); } catch (IllegalStateException ignored) { }
        }
        drain.join(2000);
        return proc.exitValue();
    }

    static double round(double x, int places) {
        double f = Math.pow(10, places);
        return Math.round(x * f) / f;
    }

    static List<Object> tailLines(Path path, int n) {
        try {
            List<String> all = Files.readAllLines(path, StandardCharsets.UTF_8);
            return new ArrayList<Object>(all.subList(Math.max(0, all.size() - n), all.size()));
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    static Outcome execute(Config cfg, Consumer<Map<String, Object>> onProgress) throws Exception {
        Path home = Paths.get((String) cfg.values.get("home"));
        Files.createDirectories(home);
        Files.deleteIfExists(home.resolve("progress.txt"));
        Files.deleteIfExists(home.resolve("orion_result.txt"));
        long start = System.nanoTime();
        int code = await(spawn(cfg, runArgs(cfg)), onProgress, home.resolve("progress.txt"));
        Path log = home.resolve("last-run.log");
        Map<String, Object> s = map("ok", true, "event", "result", "exit", code,
            "elapsed_s", round((System.nanoTime() - start) / 1e9, 1), "log", log.toString(),
            "config", new LinkedHashMap<>(cfg.values));
        if (code != 0) {
            s.put("ok", false);
            s.put("error", "engine exited " + code);
            s.put("log_tail", tailLines(log, 15));
            return new Outcome(s, code);
        }
        Path res = home.resolve("orion_result.txt");
        List<String> lines = Files.exists(res) ? Files.readAllLines(res, StandardCharsets.UTF_8) : new ArrayList<String>();
        Matcher m = lines.isEmpty() ? null : RESULT.matcher(lines.get(0));
        if (m == null || !m.find()) {
            s.put("ok", false);
            s.put("error", "engine produced no result");
            s.put("log_tail", tailLines(log, 15));
            return new Outcome(s, EXIT_FAILED);
        }
        long ok = Long.parseLong(m.group(1)), failed = Long.parseLong(m.group(2)), ms = Long.parseLong(m.group(3));
        List<Object> mspc = new ArrayList<>();
        for (String l : lines) if (l.startsWith("MSPC")) mspc.add(l);
        s.put("chunks", ok);
        s.put("failed", failed);
        s.put("total_ms", ms);
        s.put("chunks_per_s", ms == 0 ? null : (Object) round(ok * 1000.0 / ms, 2));
        s.put("ms_per_chunk", ok == 0 ? null : (Object) round((double) ms / ok, 3));
        s.put("mspc", mspc);
        if (failed > 0) {
            s.put("ok", false);
            s.put("error", failed + " chunks failed");
            return new Outcome(s, EXIT_FAILED);
        }
        return new Outcome(s, 0);
    }

    @SuppressWarnings("unchecked")
    static String renderText(Map<String, Object> s) {
        StringBuilder sb = new StringBuilder();
        if (!(Boolean) s.get("ok")) {
            sb.append("FAILED: ").append(s.get("error"));
            Object tail = s.get("log_tail");
            if (tail != null) for (Object l : (List<Object>) tail) sb.append('\n').append(l);
            return sb.toString();
        }
        sb.append(s.get("chunks")).append(" chunks, ").append(s.get("failed")).append(" failed, ")
          .append(s.get("chunks_per_s")).append(" chunks/s (").append(s.get("ms_per_chunk"))
          .append(" ms/chunk effective) in ").append(s.get("elapsed_s")).append("s");
        for (Object l : (List<Object>) s.get("mspc")) sb.append('\n').append(l);
        return sb.toString();
    }

    // ---- session ----

    static void emit(Map<String, Object> m) {
        out.println(Json.toString(m));
        out.flush();
    }

    static final class Session {
        final Config cfg;
        final boolean progress;
        boolean configured, ran, commanded;
        int exitCode;

        Session(Config cfg, boolean configured, boolean progress) {
            this.cfg = cfg;
            this.configured = configured;
            this.progress = progress;
        }

        int serve(BufferedReader in) throws Exception {
            for (String raw; (raw = in.readLine()) != null; ) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                try {
                    if (handle(line)) return exitCode;
                } catch (WrapError e) {
                    emit(map("ok", false, "error", e.getMessage(), "code", e.code));
                }
            }
            if (!ran && !commanded && configured) runJob();
            return exitCode;
        }

        @SuppressWarnings("unchecked")
        boolean handle(String line) throws Exception {
            String cmd;
            Map<String, Object> pairs = new LinkedHashMap<>();
            List<String> pos = new ArrayList<>();
            if (line.startsWith("{")) {
                Object parsed = Json.parse(line);
                if (!(parsed instanceof Map) || !((Map<String, Object>) parsed).containsKey("cmd")) {
                    throw new WrapError("json command needs a 'cmd' field");
                }
                pairs = (Map<String, Object>) parsed;
                cmd = String.valueOf(pairs.remove("cmd")).toLowerCase(Locale.ROOT);
                if (cmd.equals("set") && pairs.containsKey("key")) {
                    Object k = pairs.remove("key");
                    Object v = pairs.remove("value");
                    pairs = new LinkedHashMap<>();
                    pairs.put(String.valueOf(k), v);
                }
                for (String k : new String[] {"key", "path"}) {
                    if (pairs.containsKey(k)) pos.add(String.valueOf(pairs.remove(k)));
                }
            } else {
                String[] t = line.split("\\s+");
                int from = 1;
                cmd = t[0].toLowerCase(Locale.ROOT);
                if (t[0].indexOf('=') > 0) { cmd = "set"; from = 0; }
                for (int i = from; i < t.length; i++) {
                    int eq = t[i].indexOf('=');
                    if (eq > 0) pairs.put(t[i].substring(0, eq), t[i].substring(eq + 1)); else pos.add(t[i]);
                }
                if (cmd.equals("set") && pos.size() == 2) {
                    pairs.put(pos.get(0), pos.get(1));
                    pos.clear();
                }
            }
            if (cmd.equals("set")) {
                if (pairs.isEmpty()) throw new WrapError("usage: set <key> <value> | set key=value ...");
                emit(map("ok", true, "cmd", "set", "set", apply(pairs)));
                return false;
            }
            commanded = true;
            switch (cmd) {
                case "get": {
                    if (pos.isEmpty()) throw new WrapError("usage: get <key>");
                    String key = Config.canonical(pos.get(0));
                    emit(map("ok", true, "cmd", "get", "key", key, "value", cfg.values.get(key)));
                    break;
                }
                case "show": emit(map("ok", true, "cmd", "show", "config", new LinkedHashMap<>(cfg.values))); break;
                case "load":
                    cfg.loadToml(Paths.get(pos.isEmpty() ? "worldgend.toml" : pos.get(0)));
                    emit(map("ok", true, "cmd", "load", "config", new LinkedHashMap<>(cfg.values)));
                    break;
                case "dump": emit(map("ok", true, "cmd", "dump", "toml", cfg.toToml())); break;
                case "ping": emit(map("ok", true, "cmd", "ping")); break;
                case "fetch": {
                    int code = await(spawn(cfg, "fetch"), null, Paths.get((String) cfg.values.get("home"), "progress.txt"));
                    emit(map("ok", code == 0, "cmd", "fetch", "exit", code));
                    break;
                }
                case "run":
                    apply(pairs);
                    runJob();
                    break;
                case "quit": case "exit": case "q":
                    emit(map("ok", true, "cmd", "quit"));
                    return true;
                default: throw new WrapError("unknown command '" + cmd + "'");
            }
            return false;
        }

        Map<String, Object> apply(Map<String, Object> pairs) {
            Map<String, Object> changed = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : pairs.entrySet()) {
                String k = cfg.set(e.getKey(), e.getValue());
                changed.put(k, cfg.values.get(k));
            }
            if (!pairs.isEmpty()) configured = true;
            return changed;
        }

        void runJob() throws Exception {
            ran = true;
            Outcome o = execute(cfg, progress ? Wrap::emit : null);
            exitCode = o.code;
            Map<String, Object> reply = map("cmd", "run");
            reply.putAll(o.summary);
            emit(reply);
        }
    }

    // ---- cli ----

    int run(String[] args) {
        String format = null;
        try {
            Map<String, Object> given = new LinkedHashMap<>();
            String config = null;
            boolean noProgress = false, dump = false, runFlag = false, sessionFlag = false;
            for (int i = 0; i < args.length; i++) {
                String a = args[i], val = null;
                int eq = a.indexOf('=');
                if (a.startsWith("--") && eq > 0) { val = a.substring(eq + 1); a = a.substring(0, eq); }
                switch (a) {
                    case "-h": case "--help": System.out.println(USAGE); return 0;
                    case "--save": case "--verbose": given.put(a.substring(2), true); continue;
                    case "--no-save": case "--no-verbose": given.put(a.substring(5), false); continue;
                    case "--no-progress": noProgress = true; continue;
                    case "--dump-config": dump = true; continue;
                    case "--run": runFlag = true; continue;
                    case "--session": sessionFlag = true; continue;
                    default:
                }
                if (!a.startsWith("--")) throw new WrapError("unexpected argument '" + a + "' (try --help)");
                String name = a.substring(2);
                if (!name.equals("config") && !name.equals("format")) Config.canonical(name);
                if (val == null) {
                    if (i + 1 >= args.length) throw new WrapError(a + " needs a value");
                    val = args[++i];
                }
                if (name.equals("config")) config = val;
                else if (name.equals("format")) {
                    if (!val.equals("text") && !val.equals("json")) throw new WrapError("format: must be text or json");
                    format = val;
                } else given.put(name, val);
            }
            Config cfg = new Config();
            if (config != null) cfg.loadToml(Paths.get(config));
            else if (Files.isRegularFile(Paths.get("worldgend.toml"))) cfg.loadToml(Paths.get("worldgend.toml"));
            String home = System.getenv("WORLDGEND_HOME");
            if (home != null && !home.isEmpty()) cfg.set("home", home);
            for (Map.Entry<String, Object> e : given.entrySet()) cfg.set(e.getKey(), e.getValue());
            boolean flags = !given.isEmpty();
            if (format == null) format = terminal() ? "text" : "json";

            if (dump) {
                out.print(cfg.toToml());
                return 0;
            }
            if (sessionFlag) {
                return new Session(cfg, flags, !noProgress).serve(stdin());
            }
            if (runFlag || flags) {
                Outcome o = execute(cfg, null);
                out.println(format.equals("json") ? Json.toString(o.summary) : renderText(o.summary));
                return o.code;
            }
            if (!terminal()) return new Session(cfg, false, !noProgress).serve(stdin());
            System.setProperty("worldgend.home", (String) cfg.values.get("home"));
            Main.main(new String[0]);
            return 0;
        } catch (WrapError e) {
            System.err.println("error: " + e.getMessage());
            if ("json".equals(format) || (format == null && !terminal())) {
                emit(map("ok", false, "error", e.getMessage(), "code", e.code));
            }
            return e.code;
        } catch (Exception e) {
            e.printStackTrace();
            return 1;
        }
    }

    static BufferedReader stdin() {
        return new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    }
}
