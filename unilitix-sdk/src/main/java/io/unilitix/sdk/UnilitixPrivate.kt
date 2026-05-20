package io.unilitix.sdk

/**
 * Annotate any View with @UnilitixPrivate to prevent it from being tracked.
 * Works for both tap events and screen captures.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
annotation class UnilitixPrivate
