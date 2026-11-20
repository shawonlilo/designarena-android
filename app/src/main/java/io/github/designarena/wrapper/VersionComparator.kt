package io.github.designarena.wrapper

/**
 * Compares semantic version tags (e.g., "v1.2.0" vs "1.1.9") for GitHub Releases updates.
 */
object VersionComparator {

    fun isNewer(remoteTag: String, currentVersion: String): Boolean {
        val remote = parse(remoteTag) ?: return false
        val current = parse(currentVersion) ?: return false

        val maxLen = maxOf(remote.size, current.size)
        for (i in 0 until maxLen) {
            val r = remote.getOrElse(i) { 0 }
            val c = current.getOrElse(i) { 0 }
            if (r != c) return r > c
        }
        return false
    }

    internal fun parse(version: String): List<Int>? {
        val cleaned = version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore("-")
            .substringBefore("+")

        if (cleaned.isEmpty()) return null
        val parts = cleaned.split(".")
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        return if (numbers.any { it < 0 }) null else numbers
    }
}
