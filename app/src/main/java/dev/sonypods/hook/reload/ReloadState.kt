package dev.sonypods.hook.reload

/** State of one module generation inside one target process. */
enum class ReloadState {
    NEW,
    BOOTSTRAPPING,
    ACTIVE,
    QUIESCING,
    READY_FOR_RELOAD,
    REPLACING,
    REJECTED,
    FAILED_NEEDS_RESTART,
}
