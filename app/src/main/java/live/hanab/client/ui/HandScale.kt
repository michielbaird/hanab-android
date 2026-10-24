package live.hanab.client.ui

/** Hands are never scaled below this; on a very small screen the hands then scroll instead. */
const val MIN_HAND_SCALE = 0.62f

/** The scale moves in steps of this size, so rounding noise in the measured height cannot make it jitter. */
const val SCALE_STEP = 0.04f

/**
 * The size factor for the hands, given how much of their natural size fits ([fit]: 1 or more means
 * everything fits). Full size whenever it fits; otherwise the next step down, so the hands always fit
 * (never larger than needed), but never below [MIN_HAND_SCALE].
 *
 * The small epsilon matters: in floating point 1.0 / 0.04 is 24.999998, which without it would round a
 * perfect fit down to 0.96 and shrink the cards when nothing needed to shrink.
 */
fun snapHandScale(fit: Float): Float =
    if (fit >= 1f) 1f
    else (kotlin.math.floor(fit / SCALE_STEP + 0.001f) * SCALE_STEP).coerceIn(MIN_HAND_SCALE, 1f)