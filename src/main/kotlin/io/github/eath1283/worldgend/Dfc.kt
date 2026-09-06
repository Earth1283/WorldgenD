package io.github.eath1283.worldgend

import javassist.ClassPool
import javassist.CtNewConstructor
import javassist.CtNewMethod
import javassist.LoaderClassPath
import java.util.concurrent.atomic.AtomicInteger

// Stage 1 of the DFC port (scientific-findings-41-80.md #56's follow-up): prove the
// "compile the DensityFunction tree into one flat method instead of walking it" idea
// actually pays off on this jar/JDK, before investing in full node coverage or real
// NoiseChunk/NoiseRouter integration. Covers exactly two node types out of vanilla's
// dozens (DensityFunctions$Constant, DensityFunctions$Ap2's ADD/MUL) — anything else
// is a Leaf, called through vanilla's own compute() unchanged. Deliberately narrow.
sealed interface DfcNode {
    data class Const(val value: Double) : DfcNode
    data class Add(val a: DfcNode, val b: DfcNode) : DfcNode
    data class Mul(val a: DfcNode, val b: DfcNode) : DfcNode
    data class Leaf(val index: Int) : DfcNode
}

// Converts a real vanilla DensityFunction into a DfcNode tree. `fn` and everything it
// references live in the target classloader — handled entirely by class-name string
// matching and reflection, same discipline as the rest of this project.
class DfcConverter(private val mc: Mc) {
    val leaves = mutableListOf<Any>()

    fun toAst(fn: Any): DfcNode {
        val className = fn.javaClass.name
        return when {
            className.endsWith("DensityFunctions\$Constant") -> {
                val value = mc.publicMethod(fn.javaClass, "value").call(fn) as Double
                DfcNode.Const(value)
            }
            className.endsWith("DensityFunctions\$Ap2") -> {
                val type = mc.publicMethod(fn.javaClass, "type").call(fn)!!
                val typeName = mc.publicMethod(type.javaClass, "name").call(type) as String
                val arg1 = mc.publicMethod(fn.javaClass, "argument1").call(fn)!!
                val arg2 = mc.publicMethod(fn.javaClass, "argument2").call(fn)!!
                when (typeName) {
                    "ADD" -> DfcNode.Add(toAst(arg1), toAst(arg2))
                    "MUL" -> DfcNode.Mul(toAst(arg1), toAst(arg2))
                    else -> leaf(fn)
                }
            }
            else -> leaf(fn)
        }
    }

    private fun leaf(fn: Any): DfcNode.Leaf {
        val index = leaves.size
        leaves.add(fn)
        return DfcNode.Leaf(index)
    }
}

// Emits one Java source expression for the whole tree, then javassist-compiles a real
// class implementing DfcCompiled around it — a genuinely flat method, not an
// interpreter loop over DfcNode. Leaf calls go straight through vanilla's own
// `compute()`, cast directly (the generated class loads into the same classloader as
// the vanilla classes, so no reflection is needed inside the compiled method itself).
object DfcCompilerGen {
    private val counter = AtomicInteger()
    private const val DENSITY_FUNCTION = "net.minecraft.world.level.levelgen.DensityFunction"
    private const val FUNCTION_CONTEXT = "net.minecraft.world.level.levelgen.DensityFunction\$FunctionContext"

    private fun emit(node: DfcNode): String = when (node) {
        is DfcNode.Const -> "(${node.value}d)"
        is DfcNode.Add -> "(${emit(node.a)} + ${emit(node.b)})"
        is DfcNode.Mul -> "(${emit(node.a)} * ${emit(node.b)})"
        is DfcNode.Leaf -> "((($DENSITY_FUNCTION) leaves[${node.index}]).compute(($FUNCTION_CONTEXT) ctx))"
    }

    fun compile(loader: ClassLoader, root: DfcNode): DfcCompiled {
        val pool = ClassPool(false).apply {
            appendSystemPath()
            appendClassPath(LoaderClassPath(loader))
        }
        val cc = pool.makeClass("io.github.eath1283.worldgend.dfc.Generated${counter.incrementAndGet()}")
        cc.addInterface(pool.get(DfcCompiled::class.java.name))
        val body = "public double eval(Object[] leaves, Object ctx) { return ${emit(root)}; }"
        cc.addMethod(CtNewMethod.make(body, cc))
        val cls = cc.toClass(loader, null)
        cc.detach()
        return cls.getDeclaredConstructor().newInstance() as DfcCompiled
    }

    // Finding #60: one bespoke class per distinct tree shape put 241971 distinct
    // implementations through a single shared call site, degrading the JIT's inline
    // cache to megamorphic — a real regression despite near-free cache lookups. This
    // is the fix that idea called for: ONE class (compiled once, lazily, cached),
    // instantiated per tree with its flattened op-array as constructor data instead of
    // baked into bytecode. Every instance shares the same concrete type, so the call
    // site stays monomorphic no matter how many distinct trees get compiled. Leaf calls
    // are still real, direct, cast-and-call — no reflection — same as the bespoke path.
    @Volatile private var interpretedCtor: java.lang.reflect.Constructor<*>? = null
    private val interpretedLock = Any()

    private fun interpretedCtor(loader: ClassLoader): java.lang.reflect.Constructor<*> {
        interpretedCtor?.let { return it }
        synchronized(interpretedLock) {
            interpretedCtor?.let { return it }
            val pool = ClassPool(false).apply {
                appendSystemPath()
                appendClassPath(LoaderClassPath(loader))
            }
            val cc = pool.makeClass("io.github.eath1283.worldgend.dfc.Interpreted")
            cc.addInterface(pool.get(DfcCompiled::class.java.name))
            cc.addField(javassist.CtField.make("private final int[] kind;", cc))
            cc.addField(javassist.CtField.make("private final int[] a;", cc))
            cc.addField(javassist.CtField.make("private final int[] b;", cc))
            cc.addField(javassist.CtField.make("private final double[] constVal;", cc))
            cc.addConstructor(CtNewConstructor.make(
                "public Interpreted(int[] kind, int[] a, int[] b, double[] constVal) { " +
                    "this.kind = kind; this.a = a; this.b = b; this.constVal = constVal; }",
                cc,
            ))
            val body = """
                public double eval(Object[] leaves, Object ctx0) {
                    $FUNCTION_CONTEXT ctx = ($FUNCTION_CONTEXT) ctx0;
                    int n = kind.length;
                    double[] r = new double[n];
                    for (int i = 0; i < n; i++) {
                        int k = kind[i];
                        if (k == 0) { r[i] = constVal[i]; }
                        else if (k == 1) { r[i] = r[a[i]] + r[b[i]]; }
                        else if (k == 2) { r[i] = r[a[i]] * r[b[i]]; }
                        else { r[i] = (($DENSITY_FUNCTION) leaves[a[i]]).compute(ctx); }
                    }
                    return r[n - 1];
                }
            """.trimIndent()
            cc.addMethod(CtNewMethod.make(body, cc))
            val cls = cc.toClass(loader, null)
            cc.detach()
            val ctor = cls.getDeclaredConstructor(IntArray::class.java, IntArray::class.java, IntArray::class.java, DoubleArray::class.java)
            interpretedCtor = ctor
            return ctor
        }
    }

    fun compileInterpreted(loader: ClassLoader, root: DfcNode): DfcCompiled {
        val lin = linearize(root)
        val ctor = interpretedCtor(loader)
        return ctor.newInstance(lin.kind, lin.a, lin.b, lin.constVal) as DfcCompiled
    }
}

interface DfcCompiled {
    fun eval(leaves: Array<Any>, ctx: Any): Double
}

// Flattened, post-order form of a DfcNode tree: kind[i]/a[i]/b[i]/constVal[i] describe
// node i; ADD/MUL's operands are indices of earlier positions in the same arrays
// (post-order guarantees children come before parents), the root is always last.
private const val KIND_CONST = 0
private const val KIND_ADD = 1
private const val KIND_MUL = 2
private const val KIND_LEAF = 3

class DfcLinearized(val kind: IntArray, val a: IntArray, val b: IntArray, val constVal: DoubleArray)

fun linearize(root: DfcNode): DfcLinearized {
    val kinds = mutableListOf<Int>()
    val aList = mutableListOf<Int>()
    val bList = mutableListOf<Int>()
    val constList = mutableListOf<Double>()
    fun push(kind: Int, a: Int, b: Int, const: Double): Int {
        kinds.add(kind); aList.add(a); bList.add(b); constList.add(const)
        return kinds.size - 1
    }
    fun emit(node: DfcNode): Int = when (node) {
        is DfcNode.Const -> push(KIND_CONST, 0, 0, node.value)
        is DfcNode.Add -> push(KIND_ADD, emit(node.a), emit(node.b), 0.0)
        is DfcNode.Mul -> push(KIND_MUL, emit(node.a), emit(node.b), 0.0)
        is DfcNode.Leaf -> push(KIND_LEAF, node.index, 0, 0.0)
    }
    emit(root)
    return DfcLinearized(kinds.toIntArray(), aList.toIntArray(), bList.toIntArray(), constList.toDoubleArray())
}
