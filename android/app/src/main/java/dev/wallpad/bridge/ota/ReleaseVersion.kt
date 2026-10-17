package dev.wallpad.bridge.ota

object ReleaseVersion {
    fun isNewer(candidate: String, current: String): Boolean {
        fun parse(value: String): List<Int>? {
            if (!value.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) return null
            return value.split('.').map { it.toIntOrNull() ?: return null }
        }
        val next = requireNotNull(parse(candidate)) { "잘못된 릴리스 버전" }
        val previous = parse(current) ?: return true
        for (index in 0..2) {
            if (next[index] != previous[index]) return next[index] > previous[index]
        }
        return false
    }
}
