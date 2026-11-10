// Finding #82: prints the reference chains that keep instances of one class alive in a heap dump.
// Usage: java -Xmx10g findings/HprofPath.java <dump.hprof> <class/name/with/slashes> <max instances>
import java.lang.foreign.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

public class HprofPath {
    static MemorySegment m;
    static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(java.nio.ByteOrder.BIG_ENDIAN);
    static final ValueLayout.OfInt I = ValueLayout.JAVA_INT_UNALIGNED.withOrder(java.nio.ByteOrder.BIG_ENDIAN);
    static final ValueLayout.OfShort SH = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(java.nio.ByteOrder.BIG_ENDIAN);
    static long u4(long p) { return m.get(I, p) & 0xFFFFFFFFL; }
    static int u2(long p) { return m.get(SH, p) & 0xFFFF; }
    static int u1(long p) { return m.get(ValueLayout.JAVA_BYTE, p) & 0xFF; }
    static long id(long p) { return m.get(L, p); }
    static int tsize(int t) { return switch (t) { case 2 -> 8; case 4, 8 -> 1; case 5, 9 -> 2; case 6, 10 -> 4; default -> 8; }; }

    static class Cls { long id, sup; String name; int[] ftypes; long[] fnames; long[] staticRefs; long[] staticNames; }
    static Map<Long, String> strings = new HashMap<>();
    static Map<Long, Long> classNameId = new HashMap<>();
    static Map<Long, Cls> classes = new HashMap<>();

    // open addressing id -> value
    static long[] keys, vals; static int cap;
    static void init(int c) { cap = c; keys = new long[c]; vals = new long[c]; }
    static int slot(long k) { int h = (int) ((k * 0x9E3779B97F4A7C15L) >>> 36) & (cap - 1); while (keys[h] != 0 && keys[h] != k) h = (h + 1) & (cap - 1); return h; }
    static void put(long k, long v) { int s = slot(k); keys[s] = k; vals[s] = v; }
    static long get(long k) { int s = slot(k); return keys[s] == k ? vals[s] : -1; }

    public static void main(String[] a) throws Exception {
        String target = a[1]; int want = Integer.parseInt(a[2]);
        Set<String> skip = new HashSet<>(Arrays.asList(a).subList(3, a.length));
        FileChannel ch = FileChannel.open(Path.of(a[0]));
        m = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size(), Arena.global());
        long p = 0; while (u1(p) != 0) p++; p += 1 + 4 + 8;
        init(1 << 27);
        List<Long> roots = new ArrayList<>();
        long n = m.byteSize();
        while (p < n) {
            int tag = u1(p); long len = u4(p + 5); long b = p + 9;
            if (tag == 1) { byte[] s = new byte[(int) len - 8]; MemorySegment.copy(m, ValueLayout.JAVA_BYTE, b + 8, s, 0, s.length); strings.put(id(b), new String(s)); }
            else if (tag == 2) classNameId.put(id(b + 4), id(b + 16));
            else if (tag == 0x0C || tag == 0x1C) {
                long q = b, e = b + len;
                while (q < e) {
                    int st = u1(q); q++;
                    switch (st) {
                        case 0xFF, 0x05, 0x07 -> { roots.add(id(q)); q += 8; }
                        case 0x01 -> { roots.add(id(q)); q += 16; }
                        case 0x02, 0x03, 0x08 -> { roots.add(id(q)); q += 16; }
                        case 0x04, 0x06 -> { roots.add(id(q)); q += 12; }
                        case 0x20 -> {
                            Cls c = new Cls(); c.id = id(q); c.sup = id(q + 12); q += 8 + 4 + 8 * 6 + 4;
                            int cp = u2(q); q += 2; for (int i = 0; i < cp; i++) { int t = u1(q + 2); q += 3 + tsize(t); }
                            int ns = u2(q); q += 2; List<Long> sr = new ArrayList<>(), sn = new ArrayList<>();
                            for (int i = 0; i < ns; i++) { long nm = id(q); int t = u1(q + 8); q += 9; if (t == 2) { sr.add(id(q)); sn.add(nm); } q += tsize(t); }
                            c.staticRefs = sr.stream().mapToLong(x -> x).toArray(); c.staticNames = sn.stream().mapToLong(x -> x).toArray();
                            int nf = u2(q); q += 2; c.ftypes = new int[nf]; c.fnames = new long[nf];
                            for (int i = 0; i < nf; i++) { c.fnames[i] = id(q); c.ftypes[i] = u1(q + 8); q += 9; }
                            classes.put(c.id, c);
                        }
                        case 0x21 -> { put(id(q), q - 1); q += 8 + 4 + 8; q += 4 + u4(q); }
                        case 0x22 -> { put(id(q), q - 1); long cnt = u4(q + 12); q += 8 + 4 + 4 + 8 + cnt * 8; }
                        case 0x23 -> { long cnt = u4(q + 12); int t = u1(q + 16); q += 8 + 4 + 4 + 1 + cnt * tsize(t); }
                        default -> throw new IllegalStateException("subtag " + st + " at " + q);
                    }
                }
            }
            p = b + len;
        }
        for (Cls c : classes.values()) c.name = strings.getOrDefault(classNameId.getOrDefault(c.id, 0L), "?");
        System.err.println("classes " + classes.size() + " roots " + roots.size());
        // BFS; parent stored in a second table
        long[] pk = new long[cap], pv = new long[cap];
        java.util.function.LongBinaryOperator setParent = (k, v) -> { int h = (int) ((k * 0x9E3779B97F4A7C15L) >>> 36) & (cap - 1); while (pk[h] != 0 && pk[h] != k) h = (h + 1) & (cap - 1); if (pk[h] == k) return 0; pk[h] = k; pv[h] = v; return 1; };
        java.util.function.LongUnaryOperator parent = k -> { int h = (int) ((k * 0x9E3779B97F4A7C15L) >>> 36) & (cap - 1); while (pk[h] != 0 && pk[h] != k) h = (h + 1) & (cap - 1); return pk[h] == k ? pv[h] : 0; };
        long[] queue = new long[1 << 26]; int qh = 0, qt = 0;
        for (long r : roots) if (r != 0 && setParent.applyAsLong(r, -1) == 1) queue[qt++] = r;
        List<Long> found = new ArrayList<>();
        Map<String, Integer> tops = new HashMap<>();
        while (qh < qt) {
            long o = queue[qh++];
            Cls asClass = classes.get(o);
            if (asClass != null) { for (long r : asClass.staticRefs) if (r != 0 && setParent.applyAsLong(r, o) == 1) queue[qt++] = r; continue; }
            long off = get(o); if (off < 0) continue;
            if (u1(off) == 0x22) {
                long cnt = u4(off + 13);
                for (long i = 0; i < cnt; i++) { long r = id(off + 25 + i * 8); if (r != 0 && setParent.applyAsLong(r, o) == 1) queue[qt++] = r; }
            } else {
                Cls c = classes.get(id(off + 13));
                if (c.name.equals(target)) { found.add(o); if (found.size() >= want) break; continue; }
                long q = off + 25;
                boolean weak = false;
                for (Cls k = c; k != null; k = classes.get(k.sup)) if (k.name.equals("java/lang/ref/Reference")) weak = true;
                for (Cls k = c; k != null; k = classes.get(k.sup)) {
                    for (int i = 0; i < k.ftypes.length; i++) {
                        if (k.ftypes[i] == 2) {
                            long r = id(q);
                            boolean sk = (weak && strings.get(k.fnames[i]).equals("referent")) || skip.contains(k.name + "." + strings.get(k.fnames[i]));
                            if (!sk && r != 0 && setParent.applyAsLong(r, o) == 1) queue[qt++] = r;
                        }
                        q += tsize(k.ftypes[i]);
                    }
                }
            }
        }
        System.err.println("visited " + qt + " found " + found.size());
        int shown = 0;
        Map<String, Integer> sigs = new LinkedHashMap<>(); Map<String, String> example = new HashMap<>();
        for (long f : found) {
            StringBuilder sb = new StringBuilder(); StringBuilder sig = new StringBuilder();
            long cur = f; int depth = 0;
            while (cur != -1 && cur != 0 && depth++ < 80) {
                long par = parent.applyAsLong(cur);
                String d = describe(cur, par > 0 ? par : 0);
                sb.append("  ").append(d).append('\n'); sig.append(d.replaceAll("\\[\\d+\\]", "[]")).append('|');
                cur = par;
            }
            String s = sig.toString(); sigs.merge(s, 1, Integer::sum); example.putIfAbsent(s, sb.toString());
        }
        sigs.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(6).forEach(en -> System.out.println("== " + en.getValue() + " paths like:\n" + example.get(en.getKey())));
    }

    static String name(long o) {
        Cls c = classes.get(o); if (c != null) return "class " + c.name;
        long off = get(o); if (off < 0) return "?";
        if (u1(off) == 0x22) { Cls e = classes.get(id(off + 17)); return (e == null ? "?" : e.name); }
        return classes.get(id(off + 13)).name;
    }

    // describe cur and which field of par points to it
    static String describe(long cur, long par) {
        String via = "";
        if (par != 0) {
            Cls pc = classes.get(par);
            if (pc != null) { for (int i = 0; i < pc.staticRefs.length; i++) if (pc.staticRefs[i] == cur) via = " <- static " + strings.get(pc.staticNames[i]); }
            else {
                long off = get(par);
                if (u1(off) == 0x22) { long cnt = u4(off + 13); for (long i = 0; i < cnt; i++) if (id(off + 25 + i * 8) == cur) { via = " <- [" + i + "]"; break; } }
                else {
                    long q = off + 25;
                    for (Cls k = classes.get(id(off + 13)); k != null; k = classes.get(k.sup))
                        for (int i = 0; i < k.ftypes.length; i++) { if (k.ftypes[i] == 2 && id(q) == cur) via = " <- ." + strings.get(k.fnames[i]); q += tsize(k.ftypes[i]); }
                }
            }
        }
        return name(cur) + via;
    }
}
