package androidx.annotation

import kotlin.reflect.KClass

/** androidx.annotation.OptIn (annotation 1.8): lint opt-in for Java-style RequiresOptIn markers. */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.LOCAL_VARIABLE, AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.CONSTRUCTOR, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.FILE, AnnotationTarget.TYPEALIAS)
annotation class OptIn(vararg val markerClass: KClass<out Annotation>)
