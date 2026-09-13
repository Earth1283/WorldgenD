package io.github.eath1283.worldgend;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.TreeMap;

public final class BlockStateSnapshot {
    private final MethodHandle getBlockState;
    private final MessageDigest digest;
    private final MessageDigest stateDigest;
    private final IdentityHashMap<Object, State> states = new IdentityHashMap<>();

    private static final class State {
        final String name;
        final byte[] fingerprint;
        int count;

        State(Object state, MessageDigest digest) {
            String canonical = state.toString();
            int open = canonical.indexOf('{');
            int close = canonical.indexOf('}', open + 1);
            name = open >= 0 && close > open ? canonical.substring(open + 1, close) : canonical;
            fingerprint = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
        }
    }

    public BlockStateSnapshot(Method getter) throws IllegalAccessException, NoSuchAlgorithmException {
        getBlockState = MethodHandles.lookup().unreflect(getter).asType(
            MethodType.methodType(Object.class, Object.class, int.class, int.class, int.class));
        digest = MessageDigest.getInstance("SHA-256");
        stateDigest = MessageDigest.getInstance("SHA-256");
    }

    public String describe(Object[] sections) throws Throwable {
        digest.reset();
        ArrayList<State> counted = new ArrayList<>();
        try {
            for (Object section : sections) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            Object block = (Object) getBlockState.invokeExact(section, x, y, z);
                            State state = states.get(block);
                            if (state == null) {
                                state = new State(block, stateDigest);
                                states.put(block, state);
                            }
                            if (state.count++ == 0) counted.add(state);
                            digest.update(state.fingerprint);
                        }
                    }
                }
            }
            TreeMap<String, Integer> byName = new TreeMap<>();
            for (State state : counted) byName.merge(state.name, state.count, Integer::sum);
            StringBuilder result = new StringBuilder(HexFormat.of().formatHex(digest.digest())).append(' ');
            byName.forEach((name, count) -> result.append(name).append('=').append(count).append(','));
            if (!byName.isEmpty()) result.setLength(result.length() - 1);
            return result.toString();
        } finally {
            for (State state : counted) state.count = 0;
        }
    }
}
