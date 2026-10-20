package live.hanab.client

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.VibrationEffect
import android.os.Vibrator
import live.hanab.client.game.GameState

/**
 * Sound effects and vibration, following the website: one sound at the start of each turn, chosen
 * by the move that was just made (turn / misplay / blind play / critical discard / ...), and a
 * jingle when the game ends. The files are the website's own (res/raw).
 *
 * Left out for now: the double-discard sounds and the variant-specific ones (moo, oink, quack).
 */
object GameSounds {
    private lateinit var context: Context
    private var pool: SoundPool? = null
    private val ids = HashMap<String, Int>()

    private val names = listOf(
        "turn_us", "turn_other", "turn_fail1", "turn_fail2", "turn_sad", "turn_discard_clued",
        "turn_blind1", "turn_blind2", "turn_blind3", "turn_blind4", "turn_blind5", "turn_blind6",
        "finished_success", "finished_fail", "finished_perfect",
    )

    fun init(context: Context) {
        this.context = context.applicationContext
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val p = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attrs).build()
        for (n in names) {
            val res = this.context.resources.getIdentifier(n, "raw", this.context.packageName)
            if (res != 0) ids[n] = p.load(this.context, res, 1)
        }
        pool = p
    }

    fun play(name: String) {
        if (AppSettings.soundMode == SoundMode.Off || AppSettings.volume == 0) return
        val id = ids[name] ?: return
        val v = AppSettings.volume / 100f
        pool?.play(id, v, v, 1, 0, 1f)
    }

    fun vibrate(ms: Long) {
        if (!AppSettings.haptics) return
        val vib = context.getSystemService(Vibrator::class.java) ?: return
        if (vib.hasVibrator()) vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

/** What the last move was, remembered until the next turn starts (when the sound is played). */
class MoveSoundTracker {
    private enum class Kind { Clue, Play, Discard }

    private var kind: Kind? = null
    private var failed = false
    private var clued = false
    private var maxBefore = Int.MAX_VALUE
    private var blindStreak = 0
    private var misplayStreak = 0

    /** Call with the game before and the action about to be applied. */
    fun onAction(before: GameState?, type: String?, order: Int, failedFlag: Boolean) {
        before ?: return
        when (type) {
            "clue" -> {
                kind = Kind.Clue; blindStreak = 0; misplayStreak = 0
            }
            "play" -> {
                kind = Kind.Play
                clued = before.cards[order]?.clued == true
                failed = false
                maxBefore = before.maxScore
                blindStreak = if (clued) 0 else blindStreak + 1
                misplayStreak = 0
            }
            "discard" -> {
                kind = Kind.Discard
                clued = before.cards[order]?.clued == true
                failed = failedFlag
                maxBefore = before.maxScore
                misplayStreak = if (failedFlag) misplayStreak + 1 else 0
                blindStreak = 0
            }
        }
    }

    /** The sound for the turn that just began (call after the "status" and "turn" actions applied). */
    fun turnSound(after: GameState): String {
        val ours = after.currentPlayer == after.ourIndex
        val standard = if (ours) "turn_us" else "turn_other"
        return when (kind) {
            Kind.Play -> when {
                after.maxScore < maxBefore -> "turn_sad"
                !clued -> "turn_blind${blindStreak.coerceIn(1, 6)}"
                else -> standard
            }
            Kind.Discard -> when {
                failed -> if (misplayStreak >= 2) "turn_fail2" else "turn_fail1"
                after.maxScore < maxBefore -> "turn_sad"
                clued -> "turn_discard_clued"
                else -> standard
            }
            else -> standard
        }
    }

    val lastWasMisplay get() = kind == Kind.Discard && failed
}

/** Name of the jingle for a finished game, as the website picks it. */
fun finishedSound(after: GameState): String = when {
    (after.endCondition ?: 1) > 1 -> "finished_fail"
    after.score == after.perfectScore -> "finished_perfect"
    else -> "finished_success"
}
