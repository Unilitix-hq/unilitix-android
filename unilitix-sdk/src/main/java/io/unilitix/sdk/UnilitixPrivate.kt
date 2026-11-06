package io.unilitix.sdk

/**
 * Hides views from Unilitix: taps on them are not recorded and they are
 * masked in screenshots and snapshots (with everything inside them).
 *
 * On a View **class** — every instance is private:
 * ```kotlin
 * @UnilitixPrivate
 * class CardNumberView(context: Context) : AppCompatEditText(context)
 * ```
 *
 * On a **field** of an Activity or Fragment (1.11.0) — the view it holds is
 * private. The SDK reads these fields when the Activity resumes and when a
 * Fragment's view is created or resumed:
 * ```kotlin
 * class CheckoutActivity : AppCompatActivity() {
 *     @UnilitixPrivate lateinit var cardNumber: EditText
 * }
 * ```
 * In Kotlin the annotation goes on the property's backing field (the default
 * for this annotation); a property without a backing field cannot carry it.
 * For any other view, call `Unilitix.mask(view)`.
 *
 * Up to 1.10.2 `PROPERTY` was also a target but had no effect; code that
 * annotates a property with a backing field now applies to that field.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FIELD)
@MustBeDocumented
annotation class UnilitixPrivate
