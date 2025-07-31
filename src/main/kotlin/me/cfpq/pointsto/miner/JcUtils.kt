package me.cfpq.pointsto.miner

import org.jacodb.api.jvm.JcArrayType
import org.jacodb.api.jvm.JcClassOrInterface
import org.jacodb.api.jvm.JcClasspath
import org.jacodb.api.jvm.JcMethod
import org.jacodb.api.jvm.JcRefType
import org.jacodb.api.jvm.JcType
import org.jacodb.api.jvm.ext.allSuperHierarchySequence
import org.jacodb.api.jvm.ext.objectType
import org.jacodb.api.jvm.ext.toType
import org.jacodb.impl.features.classpaths.JcUnknownMethod
import java.util.stream.Stream
import org.jacodb.api.jvm.ext.findMethodOrNull

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

val JcMethod.overriddenMethods
    get() =
        if (isPrivate || isStatic || isConstructor) emptyList()
        // NOTE: deep hierarch isn't needed here, because `findMethodOrNull` already searches across entire hierarchy
        else (enclosingClass.interfaces + listOfNotNull(enclosingClass.superClass)).mapNotNull { superType ->
            superType.findMethodOrNull(name, description)?.takeIf { it !is JcUnknownMethod }
        }

fun JcClasspath.allClasses(): Stream<JcClassOrInterface> =
    locations.stream()
        .sequential() // TODO make parallel when better performance is needed, requires `ConcurrentIdGenerator`
        .flatMap { it.classNames.orEmpty().stream() }
        .flatMap { findClasses(it).stream() }
