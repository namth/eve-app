package com.example.facedetector.tts

class GreetingTracker(
    private val debounceGracePeriodMs: Long = 2500L,
    private val listener: Listener
) {

    interface Listener {
        fun onGreet(name: String)
        fun onFarewell(name: String)
    }

    var currentPerson: String? = null
        private set

    private var lastSeenTimestamp: Long = 0L

    /**
     * Called whenever a face is recognized in a camera frame.
     * Pass null if no registered face is recognized in this frame.
     */
    @Synchronized
    fun onFaceObserved(recognizedName: String?) {
        val now = System.currentTimeMillis()

        if (recognizedName != null) {
            when (currentPerson) {
                null -> {
                    // New person arrived
                    currentPerson = recognizedName
                    lastSeenTimestamp = now
                    listener.onGreet(recognizedName)
                }
                recognizedName -> {
                    // Same person still present - keep alive
                    lastSeenTimestamp = now
                }
                else -> {
                    // Different recognized person replaced the previous one
                    val previous = currentPerson!!
                    listener.onFarewell(previous)

                    currentPerson = recognizedName
                    lastSeenTimestamp = now
                    listener.onGreet(recognizedName)
                }
            }
        }
    }

    /**
     * Periodically checks if the tracked person has been absent longer than the grace period.
     */
    @Synchronized
    fun checkTimeout() {
        val person = currentPerson ?: return
        val now = System.currentTimeMillis()

        if (now - lastSeenTimestamp > debounceGracePeriodMs) {
            currentPerson = null
            listener.onFarewell(person)
        }
    }

    /**
     * Resets tracker state.
     */
    @Synchronized
    fun reset() {
        currentPerson = null
        lastSeenTimestamp = 0L
    }
}
