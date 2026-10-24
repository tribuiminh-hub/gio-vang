package vn.giovang.child

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

// Quy tắc tính giờ. Làm đúng theo web/policy.js (đã có kiểm thử ở tests/policy.test.mjs).

data class DayRules(val quotaMin: Long, val openAt: String, val cutoff: String)

data class Config(
    val weekday: DayRules = DayRules(120, "07:00", "20:30"),
    val weekend: DayRules = DayRules(180, "07:00", "21:00"),
    val sessionEnabled: Boolean = true,
    val sessionMaxMin: Long = 20,
    val breakMin: Long = 10,
    val requestsEnabled: Boolean = true,
    val maxPerDay: Long = 2,
    val protectSettings: Boolean = true,
    val pinSalt: String? = null,
    val pinHash: String? = null,
) {
    companion object {
        private fun num(v: Any?): Long? = (v as? Number)?.toLong()

        fun from(m: Map<String, Any?>?): Config {
            val d = Config()
            if (m == null) return d
            fun rules(key: String, def: DayRules): DayRules {
                val r = m[key] as? Map<*, *> ?: return def
                return DayRules(
                    quotaMin = num(r["quotaMin"]) ?: def.quotaMin,
                    openAt = r["openAt"] as? String ?: def.openAt,
                    cutoff = r["cutoff"] as? String ?: def.cutoff,
                )
            }
            val s = m["session"] as? Map<*, *>
            val q = m["requests"] as? Map<*, *>
            return Config(
                weekday = rules("weekday", d.weekday),
                weekend = rules("weekend", d.weekend),
                sessionEnabled = s?.get("enabled") as? Boolean ?: d.sessionEnabled,
                sessionMaxMin = num(s?.get("maxMin")) ?: d.sessionMaxMin,
                breakMin = num(s?.get("breakMin")) ?: d.breakMin,
                requestsEnabled = q?.get("enabled") as? Boolean ?: d.requestsEnabled,
                maxPerDay = num(q?.get("maxPerDay")) ?: d.maxPerDay,
                protectSettings = m["protectSettings"] as? Boolean ?: d.protectSettings,
                pinSalt = m["pinSalt"] as? String,
                pinHash = m["pinHash"] as? String,
            )
        }
    }
}

data class Day(
    val playedSec: Long = 0,
    val bonusMin: Long = 0,
    val cutoffExtendMin: Long = 0,
    val requestCount: Long = 0,
    val sessionSec: Long = 0,
    val lastPlayAt: Long = 0,
    val breakUntil: Long = 0,
) {
    companion object {
        fun from(m: Map<String, Any?>?): Day {
            fun n(k: String): Long = (m?.get(k) as? Number)?.toLong() ?: 0L
            return Day(
                playedSec = n("playedSec"),
                bonusMin = n("bonusMin"),
                cutoffExtendMin = n("cutoffExtendMin"),
                requestCount = n("requestCount"),
                sessionSec = n("sessionSec"),
                lastPlayAt = n("lastPlayAt"),
                breakUntil = n("breakUntil"),
            )
        }
    }
}

enum class Reason { NONE, HOURS, QUOTA, BREAK }

data class Status(
    val dateKey: String,
    val weekend: Boolean,
    val blocked: Boolean,
    val reason: Reason,
    val unlocked: Boolean,
    val quotaSec: Long,
    val playedSec: Long,
    val playLeftSec: Long,
    val canPlaySec: Long,
    val sessionLeftSec: Long, // Long.MAX_VALUE = không giới hạn
    val breakLeftSec: Long,
    val openAt: String,
    val cutoff: String,
    val beforeOpen: Boolean,
    val requestsLeft: Long,
)

object Policy {
    private val HM = Regex("""^(\d{1,2}):(\d{2})$""")

    fun hmToMin(hm: String?): Int {
        val m = HM.find(hm?.trim() ?: "") ?: return 0
        return min(23, m.groupValues[1].toInt()) * 60 + min(59, m.groupValues[2].toInt())
    }

    fun minToHm(v0: Int): String {
        val v = ((v0 % 1440) + 1440) % 1440
        return String.format(Locale.US, "%02d:%02d", v / 60, v % 60)
    }

    fun evaluate(nowMs: Long, zone: ZoneId, cfg: Config, day: Day, pendingSec: Long, unlockUntil: Long): Status {
        val z = Instant.ofEpochMilli(nowMs).atZone(zone)
        val weekend = z.dayOfWeek == DayOfWeek.SATURDAY || z.dayOfWeek == DayOfWeek.SUNDAY
        val r = if (weekend) cfg.weekend else cfg.weekday
        val minuteOfDay = z.hour * 60 + z.minute
        val secondOfDay = minuteOfDay * 60L + z.second

        val openMin = hmToMin(r.openAt)
        val cutoffMin = min(1440L, hmToMin(r.cutoff) + day.cutoffExtendMin).toInt()
        val inHours = minuteOfDay >= openMin && minuteOfDay < cutoffMin

        val quotaSec = (r.quotaMin + day.bonusMin) * 60
        val played = day.playedSec + pendingSec
        val playLeft = max(0L, quotaSec - played)
        val untilCutoff = if (inHours) cutoffMin * 60L - secondOfDay else 0L

        var sess = day.sessionSec
        if (day.lastPlayAt > 0 && nowMs - day.lastPlayAt >= cfg.breakMin * 60_000) sess = 0
        sess += pendingSec
        val breakLeft = if (day.breakUntil > nowMs) (day.breakUntil - nowMs + 999) / 1000 else 0L
        val sessionLeft = if (cfg.sessionEnabled) max(0L, cfg.sessionMaxMin * 60 - sess) else Long.MAX_VALUE

        val reason = when {
            !inHours -> Reason.HOURS
            playLeft <= 0 -> Reason.QUOTA
            cfg.sessionEnabled && (breakLeft > 0 || sessionLeft <= 0) -> Reason.BREAK
            else -> Reason.NONE
        }
        val unlocked = unlockUntil > nowMs
        return Status(
            dateKey = z.toLocalDate().toString(),
            weekend = weekend,
            blocked = reason != Reason.NONE && !unlocked,
            reason = reason,
            unlocked = unlocked,
            quotaSec = quotaSec,
            playedSec = played,
            playLeftSec = playLeft,
            canPlaySec = if (reason == Reason.NONE) minOf(playLeft, untilCutoff, sessionLeft) else 0L,
            sessionLeftSec = sessionLeft,
            breakLeftSec = when {
                breakLeft > 0 -> breakLeft
                cfg.sessionEnabled && sessionLeft <= 0 -> cfg.breakMin * 60
                else -> 0L
            },
            openAt = r.openAt,
            cutoff = minToHm(cutoffMin),
            beforeOpen = minuteOfDay < openMin,
            requestsLeft = if (cfg.requestsEnabled) max(0L, cfg.maxPerDay - day.requestCount) else 0L,
        )
    }

    // Cộng dồn thời gian chơi vào bản ghi ngày (chạy trong transaction)
    fun applyPlay(m: Map<String, Any?>?, deltaSec: Long, nowMs: Long, cfg: Config, deviceId: String): Map<String, Any> {
        val d = Day.from(m)
        var sess = if (d.lastPlayAt > 0 && nowMs - d.lastPlayAt >= cfg.breakMin * 60_000) 0L else d.sessionSec
        sess += deltaSec
        var breakUntil = d.breakUntil
        if (cfg.sessionEnabled && sess >= cfg.sessionMaxMin * 60) {
            breakUntil = nowMs + cfg.breakMin * 60_000
            sess = 0L
        }
        val per = HashMap<String, Any>()
        (m?.get("perDevice") as? Map<*, *>)?.forEach { (k, v) ->
            if (k is String && v is Number) per[k] = v.toLong()
        }
        per[deviceId] = ((per[deviceId] as? Long) ?: 0L) + deltaSec
        return mapOf(
            "playedSec" to d.playedSec + deltaSec,
            "sessionSec" to sess,
            "lastPlayAt" to nowMs,
            "breakUntil" to breakUntil,
            "perDevice" to per,
        )
    }

    fun fmtDur(sec: Long): String {
        if (sec == Long.MAX_VALUE) return "không giới hạn"
        val m = max(0L, (sec + 30) / 60)
        val h = m / 60
        val r = m % 60
        return when {
            h > 0 && r > 0 -> "$h giờ $r phút"
            h > 0 -> "$h giờ"
            else -> "$r phút"
        }
    }

    fun fmtClock(sec: Long): String = String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60)
}
