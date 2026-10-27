package live.hanab.client

/**
 * The offline table: a small hanab.live "server" with bots (hanab-bot-rs), written in Rust and built from
 * native/offline. It is only packaged for 64-bit ARM phones; [available] is false anywhere else.
 */
object NativeOffline {
    val available: Boolean = try {
        System.loadLibrary("hanab_offline")
        true
    } catch (_: Throwable) {
        false
    }

    /** Starts a table. Returns `{"handle": n}` or `{"error": "..."}`. */
    external fun start(variantsJson: String, suitsJson: String, configJson: String): String

    /** Passes one command of the app (`getGameInfo1`, `action`, ...) to the table. */
    external fun send(handle: Long, command: String, dataJson: String)

    /** The next message of the table, waiting up to [timeoutMs]: null if none yet, "" once the table is done. */
    external fun poll(handle: Long, timeoutMs: Long): String?

    external fun close(handle: Long)
}
