package me.cfpq.pointsto.miner

import java.io.File
import java.net.URL
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max

internal var maximumContextNumber = 1_000_000_000

interface IdGenerator<in T> {
    fun generateId(value: T): Int
}

class NonConcurrentIdGenerator<T> : IdGenerator<T> {
    val idCache = mutableMapOf<T, Int>()

    override fun generateId(value: T): Int = idCache.getOrPut(value) { idCache.size }
    fun getIdOrNull(value: T): Int? = idCache[value]
}

class ConcurrentFCallIdGenerator<T> {
    private val lock = ReentrantLock()
    private val idCache = mutableMapOf<T, Int>()
    private var maxNumber = 0

    fun generateId(signature: T): Int = lock.withLock {
        idCache[signature] = (idCache.getOrDefault(signature, 0) + 1) % maximumContextNumber
        maxNumber = max(maxNumber, idCache[signature]!!)
        return idCache[signature]!!
    }
    fun getMaxNumber(): Int = lock.withLock { return maxNumber }
}

class ConcurrentFNameIdGenerator<T> {
    private val lock = ReentrantLock()
    private val idCache = mutableMapOf<T, Int>()

    fun generateId(signature: T): Int = lock.withLock {
        idCache[signature] = idCache.getOrDefault(signature, idCache.size)
        return idCache[signature]!!
    }
}

fun <T> NonConcurrentIdGenerator<T>.writeMappings(file: File, map: (T) -> Any? = { it }) =
    file.printWriter().buffered().use { writer ->
        idCache.entries
            .sortedBy { it.value }
            .forEach {
                writer.append(it.value.toString())
                writer.append(": ")
                writer.append(map(it.key).toString().replace("\n", "\\n"))
                writer.newLine()
            }
    }

fun readMappings(url: URL): Map<Int, String> = url.openStream().bufferedReader().useLines { lines ->
    lines.associate { line ->
        val (key, value) = line.split(": ", limit = 2)
        key.toInt() to value
    }
}
