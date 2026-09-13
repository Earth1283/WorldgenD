import io.github.eath1283.worldgend.ServerRuntime;
import java.io.File;
import java.io.PrintWriter;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class DensityBatchBench {
    private static volatile double checksum;

    public static void main(String[] args) throws Throwable {
        String outputPath = args[0];
        int iterations = Integer.getInteger("bench.iterations", 100_000);
        try (URLClassLoader loader = ServerRuntime.INSTANCE.discover(new File("servers")).newClassLoader();
             PrintWriter output = new PrintWriter(outputPath)) {
            loader.loadClass("net.minecraft.SharedConstants").getMethod("tryDetectVersion").invoke(null);
            loader.loadClass("net.minecraft.server.Bootstrap").getMethod("bootStrap").invoke(null);
            Class<?> density = loader.loadClass("net.minecraft.world.level.levelgen.DensityFunction");
            Class<?> providerType = loader.loadClass("net.minecraft.world.level.levelgen.DensityFunction$ContextProvider");
            double[] source = new Random(69).doubles(1024, -2.0, 2.0).toArray();
            Object input = Proxy.newProxyInstance(loader, new Class<?>[]{density}, (proxy, method, callArgs) -> {
                if (method.getName().equals("fillArray")) {
                    double[] values = (double[]) callArgs[0];
                    System.arraycopy(source, 0, values, 0, values.length);
                    return null;
                }
                throw new AssertionError(method.getName());
            });
            List<Object> transforms = new ArrayList<>();
            String prefix = "net.minecraft.world.level.levelgen.DensityFunctions$";
            for (String name : List.of("Mapped", "MulOrAdd")) {
                Constructor<?> constructor = loader.loadClass(prefix + name).getDeclaredConstructors()[0];
                constructor.setAccessible(true);
                for (Object operation : loader.loadClass(prefix + name + "$Type").getEnumConstants()) {
                    transforms.add(name.equals("Mapped")
                        ? constructor.newInstance(operation, input, -1.0, 1.0)
                        : constructor.newInstance(operation, input, -1.0, 1.0, 0.25));
                }
            }
            Constructor<?> clamp = loader.loadClass(prefix + "Clamp").getDeclaredConstructors()[0];
            clamp.setAccessible(true);
            transforms.add(clamp.newInstance(input, -0.5, 0.5));
            MethodHandle[] fills = new MethodHandle[transforms.size()];
            for (int i = 0; i < fills.length; i++) {
                Object transform = transforms.get(i);
                Method method = transform.getClass().getMethod("fillArray", double[].class, providerType);
                method.setAccessible(true);
                MethodHandle bound = MethodHandles.lookup().unreflect(method).bindTo(transform);
                fills[i] = MethodHandles.insertArguments(bound, 1, (Object) null)
                    .asType(MethodType.methodType(void.class, double[].class));
            }
            output.println("mode,length,round,batches,total_ns,ns_per_element,checksum");
            String mode = System.getProperty("bench.mode", "vanilla");
            for (int length : new int[]{49, 128, 256, 1024}) {
                double[] values = new double[length];
                for (int warmup = 0; warmup < 8; warmup++) measure(fills, values, iterations / 2);
                for (int round = 1; round <= 5; round++) {
                    long elapsed = measure(fills, values, iterations);
                    output.printf(java.util.Locale.ROOT, "%s,%d,%d,%d,%d,%.6f,%.17g%n",
                        mode, length, round, iterations, elapsed, (double) elapsed / iterations / length, checksum);
                    output.flush();
                }
            }
        }
    }

    private static long measure(MethodHandle[] fills, double[] values, int iterations) throws Throwable {
        double sum = 0.0;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            fills[i % fills.length].invokeExact(values);
            sum += values[i % values.length];
        }
        long elapsed = System.nanoTime() - start;
        checksum = sum;
        return elapsed;
    }
}
