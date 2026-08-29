package io.github.eath1283.worldgend

import java.io.File
import java.net.URLClassLoader
import java.util.zip.ZipFile

class DiscoveredServer(val jar: File, val classpath: List<File>) {
    fun newClassLoader(): URLClassLoader {
        val urls = (classpath + jar).map { it.toURI().toURL() }.toTypedArray()
        return URLClassLoader(urls, ClassLoader.getSystemClassLoader())
    }
}

object ServerRuntime {
    fun discover(serversDir: File): DiscoveredServer {
        if (!serversDir.isDirectory) {
            error("No '${serversDir.name}' directory next to the working dir (${serversDir.absolutePath}). Drop the official server jar there.")
        }
        val jars = serversDir.listFiles { f -> f.isFile && f.extension == "jar" }?.sortedBy { it.name }
            ?: emptyList()
        val jar = jars.firstOrNull()
            ?: error("No .jar files found in ${serversDir.absolutePath}")
        if (jars.size > 1) {
            System.err.println("Multiple jars in ${serversDir.absolutePath}, hammering ${jar.name}")
        }
        return if (isBundlerJar(jar)) unpackBundler(jar, serversDir) else DiscoveredServer(jar, emptyList())
    }

    private fun isBundlerJar(jar: File): Boolean =
        ZipFile(jar).use { it.getEntry("META-INF/versions.list") != null }

    private fun unpackBundler(bundlerJar: File, serversDir: File): DiscoveredServer {
        val cacheDir = File(serversDir, ".cache/${bundlerJar.nameWithoutExtension}")
        cacheDir.mkdirs()

        ZipFile(bundlerJar).use { zip ->
            val versionEntry = zip.getInputStream(zip.getEntry("META-INF/versions.list"))
                .bufferedReader().readLine() ?: error("empty versions.list")
            val (_, _, versionRelPath) = versionEntry.split("\t")
            val serverJar = extractOnce(zip, "META-INF/versions/$versionRelPath", File(cacheDir, "server.jar"))

            val libraries = zip.getInputStream(zip.getEntry("META-INF/libraries.list"))
                .bufferedReader().readLines().filter { it.isNotBlank() }
            val libDir = File(cacheDir, "libraries").apply { mkdirs() }
            val libJars = libraries.map { line ->
                val (_, _, relPath) = line.split("\t")
                extractOnce(zip, "META-INF/libraries/$relPath", File(libDir, relPath.substringAfterLast('/')))
            }

            return DiscoveredServer(serverJar, libJars)
        }
    }

    private fun extractOnce(zip: ZipFile, entryName: String, dest: File): File {
        val entry = zip.getEntry(entryName) ?: error("Missing $entryName inside bundler jar")
        if (dest.exists() && dest.length() == entry.size) return dest
        dest.parentFile.mkdirs()
        zip.getInputStream(entry).use { input -> dest.outputStream().use { input.copyTo(it) } }
        return dest
    }
}
