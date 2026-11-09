package dev.rishikesh.deja.scan

/** What [watchSignal] reports while a device page is open. */
sealed interface WatchEvent {
    data class Heard(
        val rssi: Int,
    ) : WatchEvent

    data class Failed(
        val reason: String,
    ) : WatchEvent
}
