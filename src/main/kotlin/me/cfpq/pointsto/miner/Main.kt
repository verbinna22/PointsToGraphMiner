package me.cfpq.pointsto.miner

import mu.KotlinLogging
import org.jacodb.api.JcClasspath
import org.jacodb.impl.features.classpaths.UnknownClassMethodsAndFields
import org.jacodb.impl.features.classpaths.UnknownClasses
import org.jacodb.impl.jacodb
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.streams.asSequence

private val logger = KotlinLogging.logger {}

private val libs = listOf(
    "basic" to "basic",
    "collections" to "collections",
    "cornerCases" to "cornerCases",
    "generalJava" to "generalJava",
)

suspend fun main() {
    //getClassesInPackage("basic")
    useJacoDb { cp ->
        val outFolder = File("graphs")
        libs.forEach { (name, prefix) ->
            logger.info { "Processing $name..." }
            minePtGraph(cp, "$prefix.", outFolder.resolve(name))
        }
    }
}

suspend fun useJacoDb(block: (JcClasspath) -> Unit) = jacodb {}.use { db ->
    db.classpath(getRuntimeClasspath(), listOf(UnknownClassMethodsAndFields, UnknownClasses)).use(block)
}

private fun getRuntimeClasspath(): List<File> {
    val classpath = System.getProperty("java.class.path") + ":" + "/mnt/data/MyOwnFolder/entertaiment/java/classes"
    println(classpath)
    val classpathFiles = classpath.split(File.pathSeparator)
        .filter { it.isNotEmpty() }
        .map { File(it) }
    return classpathFiles
}
