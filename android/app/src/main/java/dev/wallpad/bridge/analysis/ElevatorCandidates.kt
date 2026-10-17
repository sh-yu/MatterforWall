package dev.wallpad.bridge.analysis

enum class ElevatorOption(val title: String, val length: Int, val sample: String) {
    LONG("옵션 1 · 21바이트", 21, "AA5530BC0001004400010000000000000000320D0D"),
    SHORT("옵션 2 · 12바이트", 12, "AA550B010D013101417C0D0D"),
}

/** HARDWARE_TEST_PENDING: framing/checksum inspection never asserts call or arrival. */
class ElevatorCandidates(private val option: ElevatorOption) {
    private val window = ArrayDeque<Int>()
    fun feed(bytes: ByteArray): List<String> {
        val results = mutableListOf<String>()
        for (byte in bytes) {
            window.addLast(byte.toInt() and 0xff)
            if (window.size > option.length) window.removeFirst()
            if (window.size != option.length) continue
            val frame = window.toList()
            if (frame[0] != 0xaa || frame[1] != 0x55 || frame.takeLast(2) != listOf(0x0d, 0x0d)) continue
            val verdict = if (option == ElevatorOption.SHORT) "체크섬 미검증"
                else if ((frame.subList(2, 18).sum() and 0xff) == frame[18]) "후보 체크섬 일치"
                else "체크섬 불일치"
            results += frame.joinToString("") { "%02X".format(it) } + " · $verdict"
        }
        return results
    }
}
