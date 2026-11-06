package io.unilitix.sdk

/**
 * Annotate a View **class** with @UnilitixPrivate to prevent it from being
 * tracked: taps on it are not recorded and it is masked in screenshots and
 * snapshots.
 *
 * ```kotlin
 * @UnilitixPrivate
 * class CardNumberView(context: Context) : AppCompatEditText(context)
 * ```
 *
 * Only the class annotation is honoured. FIELD and PROPERTY stay in [Target]
 * for source compatibility (code annotating a field keeps compiling), but the
 * SDK inspects the view object, not the field holding it, so annotating a
 * field or property has no effect: use `maskedViewIds` for single views.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@MustBeDocumented
annotation class UnilitixPrivate
