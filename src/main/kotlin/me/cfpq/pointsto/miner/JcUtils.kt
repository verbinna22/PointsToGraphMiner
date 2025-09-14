package me.cfpq.pointsto.miner

import org.jacodb.api.jvm.JcArrayType
import org.jacodb.api.jvm.JcClassOrInterface
import org.jacodb.api.jvm.JcClasspath
import org.jacodb.api.jvm.JcMethod
import org.jacodb.api.jvm.JcRefType
import org.jacodb.api.jvm.JcType
import org.jacodb.api.jvm.ext.allSuperHierarchySequence
import org.jacodb.api.jvm.ext.findDeclaredMethodOrNull
import org.jacodb.api.jvm.ext.objectType
import org.jacodb.api.jvm.ext.toType
import org.jacodb.impl.features.classpaths.JcUnknownMethod
import java.util.stream.Stream
import org.jacodb.api.jvm.ext.findMethodOrNull
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ConcurrentTypeToSubtypesMap {
    private val lock = ReentrantLock()
    private val idCache = mutableMapOf<JcClassOrInterface, MutableSet<JcClassOrInterface>>()

    fun writeSubType(basic: JcClassOrInterface, subclass: JcClassOrInterface) = lock.withLock {
        if (!idCache.containsKey(basic)) {
            idCache[basic] = mutableSetOf()
        }
        idCache[basic]!!.add(subclass)
    }

    fun getSubTypes(basic: JcClassOrInterface): Set<JcClassOrInterface> = lock.withLock {
        return idCache[basic] ?: setOf()
    }

    fun getKeyTypes(): Set<JcClassOrInterface> = lock.withLock {
        return idCache.keys
    }
}

internal var typeToSubtypesMap = ConcurrentTypeToSubtypesMap()

fun JcType.toRaw(): JcType {
    return when (this) {
        is JcArrayType -> this.classpath.arrayTypeOf(elementType.toRaw())
        is JcRefType -> this.jcClass.toType()
        else -> this
    }
}

fun JcType.allRawSuperHierarchySequence(): Sequence<JcType> = toRaw().run {
    return when (this) {
        is JcRefType -> (sequenceOf(this, classpath.objectType)
                + this.jcClass.allSuperHierarchySequence.distinct().map { it.toType() }).distinct()
        else -> sequenceOf(this)
    }
}

val JcMethod.overriddenMethodsOfSubclasses
    get() =
        if (isPrivate || isStatic || isConstructor) emptyList()
        else typeToSubtypesMap.getSubTypes(enclosingClass).mapNotNull { subtype ->
            subtype.findMethodOrNull(name, description)?.takeIf { it !is JcUnknownMethod }
        }

fun JcMethod.overriddenMethodsOfSubclassesWithFilter(f: Set<String>?) =
        if (isPrivate || isStatic || isConstructor) emptyList()
        else typeToSubtypesMap.getSubTypes(enclosingClass).filter { subtype ->
            f?.contains(subtype.toString()) ?: true
        }.mapNotNull { subtype ->
            subtype.findMethodOrNull(name, description)?.takeIf { it !is JcUnknownMethod }
        }

val JcMethod.overriddenMethodsOfSuperclasses
    get() =
        if (isPrivate || isStatic || isConstructor) emptyList()
        // NOTE: deep hierarch isn't needed here, because `findMethodOrNull` already searches across entire hierarchy
        else (enclosingClass.interfaces + listOfNotNull(enclosingClass.superClass)).mapNotNull { superType ->
            superType.findMethodOrNull(name, description)?.takeIf { it !is JcUnknownMethod }
        }

fun JcClasspath.allClasses(): Stream<JcClassOrInterface> =
    locations.stream()
        .sequential() // make parallel when better performance is needed, requires `ConcurrentIdGenerator`
        .flatMap { it.classNames.orEmpty().stream() }
        .flatMap { findClasses(it).stream() }
