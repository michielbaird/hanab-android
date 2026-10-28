package live.hanab.client

/**
 * The offline table: a small hanab.live "server" with bots (hanab-bot-rs), written in Rust and built from
 * native/offline. It is packaged for 64-bit ARM phones, and for x86_64 emulators in debug builds; [available] is false anywhere else.
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

    /** Starts a replay of a game the table sent as an `offlineRecord`. Returns `{"handle": n}` or `{"error": "..."}`. */
    external fun startReplay(recordJson: String): String

    /**
     * Has the bot go through a finished game (the website's export or an offline record) and comment on the moves it
     * would have played differently. Slow: call it off the main thread. Returns `{"players", "comments"}` or `{"error"}`.
     */
    external fun analyze(variantsJson: String, suitsJson: String, gameJson: String, variant: String, convention: String): String

    /** Passes one command of the app (`getGameInfo1`, `action`, ...) to the table. */
    external fun send(handle: Long, command: String, dataJson: String)

    /** The next message of the table, waiting up to [timeoutMs]: null if none yet, "" once the table is done. */
    external fun poll(handle: Long, timeoutMs: Long): String?

    external fun close(handle: Long)
}
