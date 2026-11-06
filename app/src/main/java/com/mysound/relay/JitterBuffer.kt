package com.mysound.relay

/**
 * Джиттер-буфер: принимает пакеты по номерам (в любом порядке), отдаёт строго по порядку.
 * Пропавший пакет возвращается как LOST (проигрываем тишину), задержка не копится:
 * если накопилось слишком много — лишнее сбрасывается через trimTo().
 */
class JitterBuffer(private val cap: Int = 1024) {

    companion object {
        val LOST = ByteArray(0)
    }

    private val slots = arrayOfNulls<ByteArray>(cap)
    private val tags = LongArray(cap) { -1L }
    private var next = -1L      // номер пакета, который играть следующим (-1 = ещё не начали)
    private var high = -1L      // наибольший полученный номер

    @Synchronized fun reset() {
        slots.fill(null); tags.fill(-1L)
        next = -1L; high = -1L
    }

    @Synchronized fun put(seq: Long, data: ByteArray) {
        if (next < 0) { next = seq; high = seq - 1 }
        if (seq < next) { Stats.late++; return }
        if (seq - next >= cap) {            // огромный скачок вперёд — пересинхронизация
            slots.fill(null); tags.fill(-1L)
            next = seq; high = seq - 1
        }
        val i = (seq % cap).toInt()
        slots[i] = data
        tags[i] = seq
        if (seq > high) high = seq
    }

    /** Сколько пакетов лежит между «играть следующим» и последним полученным (с учётом дыр). */
    @Synchronized fun depth(): Int =
        if (next < 0 || high < next) 0 else (high - next + 1).toInt()

    /** null — буфер пуст; LOST — пакета нет, но есть более поздние; иначе данные пакета. */
    @Synchronized fun poll(): ByteArray? {
        if (next < 0 || high < next) return null
        val i = (next % cap).toInt()
        val d = if (tags[i] == next) slots[i] else null
        slots[i] = null; tags[i] = -1L
        next++
        return d ?: LOST
    }

    /** Выбрасывает самые старые пакеты, оставляя targetPackets. */
    @Synchronized fun trimTo(targetPackets: Int) {
        val d = depth()
        if (d <= targetPackets) return
        val drop = d - targetPackets
        for (k in 0 until drop) {
            val i = ((next + k) % cap).toInt()
            slots[i] = null; tags[i] = -1L
        }
        next += drop
        Stats.dropped += drop
    }
}
