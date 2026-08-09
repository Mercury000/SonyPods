package dev.sonypods.hook.reload

/**
 * Owns every cancellable resource created by a module generation.
 * Disposers are deliberately small and idempotent; teardown continues after an
 * individual failure so the caller can make a fail-closed decision with the
 * complete failure list.
 */
class ResourceRegistry {
    private val resources = linkedMapOf<String, () -> Unit>()

    @Synchronized
    fun register(key: String, disposer: () -> Unit) {
        require(key.isNotBlank()) { "resource key must not be blank" }
        check(key !in resources) { "duplicate resource key: $key" }
        resources[key] = disposer
    }

    @Synchronized
    fun remove(key: String) {
        resources.remove(key)
    }

    @Synchronized
    fun keys(): List<String> = resources.keys.toList()

    @Synchronized
    fun isEmpty(): Boolean = resources.isEmpty()

    fun closeAll(): List<String> {
        val snapshot = synchronized(this) { resources.toList().asReversed() }
        val failures = mutableListOf<String>()
        val closed = mutableSetOf<String>()
        snapshot.forEach { (key, disposer) ->
            runCatching { disposer() }
                .onSuccess { closed += key }
                .onFailure { failures += "$key: ${it.message ?: it.javaClass.simpleName}" }
        }
        // Keep failed disposers registered. A rejected reload may retry the
        // cleanup; clearing them here would make the registry claim that the
        // generation is clean while the resource is still live.
        synchronized(this) { closed.forEach(resources::remove) }
        return failures
    }
}
