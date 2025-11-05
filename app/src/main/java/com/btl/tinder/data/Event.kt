package com.btl.tinder.data

/**notes
 * A wrapper class for representing one-time events in MVVM architecture.
 *
 * This class is commonly used to handle events that should be consumed only once,
 * such as navigation actions, showing Toast/Snackbar messages, or displaying dialogs.
 * It helps prevent the same event from being re-triggered when the UI is recreated
 * (for example, during configuration changes like screen rotation).
 *
 * @param T The type of the content wrapped inside the event.
 *
 * @property content The actual data of the event (e.g., message, navigation target).
 */
open class Event<out T>(private val content: T) {

    var hasBeenHandled = false
        private set

    fun getContentOrNull(): T? {
        return if (hasBeenHandled)
            null
        else {
            hasBeenHandled = true
            content
        }
    }
}