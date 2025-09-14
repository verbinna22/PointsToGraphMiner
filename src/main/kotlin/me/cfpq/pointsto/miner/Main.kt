package me.cfpq.pointsto.miner

import mu.KotlinLogging
import org.jacodb.api.jvm.JcClasspath
import org.jacodb.impl.features.classpaths.UnknownClassMethodsAndFields
import org.jacodb.impl.features.classpaths.UnknownClasses
import org.jacodb.impl.jacodb

import java.io.File

private val logger = KotlinLogging.logger {}

private var libs : List<Pair<String, List<String>>> = listOf()//= listOf(
//    "basic" to "basic",
//    "collections" to "collections",
//    "cornerCases" to "cornerCases",
//    "generalJava" to "generalJava",
//)

suspend fun main(args: Array<String>) {
//    showFunId = false to old version
    if (args.size == 1 && args[0] == "useTI") {
        useTypeInfoMode = true
    } else if (args.size > 1) {
        throw IllegalArgumentException("Must be 1 or 0 arguments")
    }
    maximumContextNumber = File("maximumContextNumber.txt").bufferedReader().readLine().toInt()
    libs = File("libs.txt").bufferedReader().readLines().map { line ->
        val libs = line.split(" ")
        libs[0].replace(".", "_") to libs
    }
    val outFolder = File("graphs")
    if (useTypeInfoMode) {
        vertexToTypesStringMap = ConcurrentVertexToTypesStringMap()
    }
    useJacoDb { cp ->
        libs.forEach { (name, prefixes) ->
            logger.info { "Processing $name..." }
            if (useTypeInfoMode) {
                outFolder.resolve("$name/type_analysis_results.txt").bufferedReader().forEachLine { line ->
                    val items = line.split("\t")
                    val vertex = items[0]
                    items.drop(1).forEach { type ->
                        vertexToTypesStringMap.writeSubType(vertex, type)
                    }
                }
            }
            minePtGraph(cp, prefixes.map { "$it." }, outFolder.resolve(name))
        }
    }
}

suspend fun useJacoDb(block: (JcClasspath) -> Unit) = jacodb { keepLocalVariableNames() }.use { db ->
    db.classpath(getRuntimeClasspath(), listOf(UnknownClassMethodsAndFields, UnknownClasses,
    )).use(block)
}

private fun getRuntimeClasspath(): List<File> {
    val paths = File("path.txt").bufferedReader().readLines().joinToString(":")
    val classpath = "${System.getProperty("java.class.path")}:${paths}"
//    println(classpath)
    val classpathFiles = classpath.split(File.pathSeparator)
        .filter { it.isNotEmpty() }
        .map { File(it) }
    return classpathFiles
}
