package vn.giovang.child

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max

// Bộ não của app trên máy con: nghe quy tắc từ ba mẹ, cộng giờ chơi, gửi yêu cầu.
object Engine {
    enum class Cat { NEUTRAL, PLAY, STUDY, PROTECTED }

    data class Req(val id: String, val minutes: Long, val status: String, val grantedMin: Long, val createdMs: Long)

    private lateinit var ctx: Context
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())

    var started = false
        private set
    var cfg = Config()
        private set
    var day = Day()
        private set
    var apps: Map<String, String> = emptyMap()
        private set
    var requests: List<Req> = emptyList()
        private set
    var zone: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")
        private set
    var childName = "con"
        private set
    var configLoaded = false
        private set

    private var appsFromServer = false
    private val regs = mutableListOf<ListenerRegistration>()
    private var dayReg: ListenerRegistration? = null
    private var dayKey: String? = null
    private var launchers: Set<String> = emptySet()
    private var flushing = false
    private var lastFlushFail = 0L
    private var lastClockSync = 0L
    private val seenPending = mutableSetOf<String>()
    private val listeners = mutableSetOf<() -> Unit>()

    // Ba mẹ vừa bấm cấp quyền: cho mở Cài đặt trong 2 phút
    var graceUntil = 0L

    val uid: String? get() = try { Fb.auth.currentUser?.uid } catch (e: Exception) { null }
    private val fid: String? get() = prefs.familyId

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun changed() { main.post { listeners.toList().forEach { it() } } }

    fun start(c: Context): Boolean {
        if (started) return true
        ctx = c.applicationContext
        prefs = Prefs(ctx)
        if (!prefs.paired || !Fb.ensure(ctx)) return false
        val u = uid ?: return false
        val f = fid ?: return false
        started = true
        childName = prefs.childName ?: "con"
        launchers = Apps.launchers(ctx)
        val db = Fb.db

        regs.add(db.document("families/$f").addSnapshotListener { s, _ ->
            if (s != null && s.exists()) {
                s.getString("tz")?.let { tz -> try { zone = ZoneId.of(tz) } catch (e: Exception) { Log.w(TAG, "tz", e) } }
                s.getString("childName")?.let { childName = it; prefs.childName = it }
                changed()
            }
        })
        regs.add(db.document("families/$f/config/main").addSnapshotListener { s, _ ->
            if (s != null && s.exists()) {
                cfg = Config.from(s.data)
                configLoaded = true
                changed()
            }
        })
        regs.add(db.collection("families/$f/apps").addSnapshotListener { s, _ ->
            if (s != null) {
                apps = s.documents.associate { it.id to (it.getString("category") ?: "pending") }
                if (!s.metadata.isFromCache) appsFromServer = true
                changed()
            }
        })
        regs.add(db.collection("families/$f/requests").whereEqualTo("deviceUid", u).addSnapshotListener { s, _ ->
            if (s != null) onRequests(s)
        })
        subscribeDay()
        return true
    }

    fun stop() {
        regs.forEach { it.remove() }
        regs.clear()
        dayReg?.remove()
        dayReg = null
        dayKey = null
        started = false
        configLoaded = false
        appsFromServer = false
    }

    fun todayKey(): String = Instant.ofEpochMilli(Clock.now()).atZone(zone).toLocalDate().toString()

    private fun subscribeDay() {
        val f = fid ?: return
        val key = todayKey()
        if (key == dayKey && dayReg != null) return
        dayReg?.remove()
        dayKey = key
        day = Day()
        dayReg = Fb.db.document("families/$f/days/$key").addSnapshotListener { s, _ ->
            if (s != null) {
                day = Day.from(s.data)
                changed()
            }
        }
    }

    private fun onRequests(s: QuerySnapshot) {
        val list = s.documents.map { d ->
            Req(
                id = d.id,
                minutes = d.getLong("minutes") ?: 0L,
                status = d.getString("status") ?: "pending",
                grantedMin = d.getLong("grantedMin") ?: 0L,
                createdMs = d.getTimestamp("createdAt")?.toDate()?.time ?: Long.MAX_VALUE,
            )
        }.sortedByDescending { it.createdMs }
        for (r in list) {
            if (r.status == "pending") seenPending.add(r.id)
            else if (seenPending.remove(r.id)) Notifier.decision(ctx, r)
        }
        requests = list
        changed()
    }

    // Gọi mỗi nhịp từ GuardService
    fun maintain() {
        if (!started) return
        if (todayKey() != dayKey) {
            if (prefs.pendingSec > 0) flush(force = true)
            subscribeDay()
        }
    }

    private fun pendingToday(): Long = if (prefs.pendingDate == dayKey) prefs.pendingSec else 0L

    fun status(): Status =
        Policy.evaluate(Clock.now(), zone, cfg, day, if (started) pendingToday() else 0L, if (started) prefs.unlockUntil else 0L)

    fun pending(): Long = if (started) prefs.pendingSec else 0L

    fun addPlay(sec: Long) {
        if (!started) return
        val key = todayKey()
        if (prefs.pendingDate != key) {
            // sang ngày mới: phần cũ đã được đẩy lên trong maintain()
            prefs.pendingDate = key
            prefs.pendingSec = 0
        }
        prefs.pendingSec = prefs.pendingSec + sec
    }

    fun flush(force: Boolean = false) {
        if (!started) return
        val delta = prefs.pendingSec
        val date = prefs.pendingDate ?: return
        val u = uid ?: return
        val f = fid ?: return
        if (delta <= 0 || flushing) return
        if (!force && SystemClock.elapsedRealtime() - lastFlushFail < 30_000) return
        flushing = true
        val ref = Fb.db.document("families/$f/days/$date")
        val c = cfg
        Fb.db.runTransaction { tx ->
            val snap = tx.get(ref)
            tx.set(ref, Policy.applyPlay(snap.data, delta, Clock.now(), c, u), SetOptions.merge())
            null
        }.addOnSuccessListener {
            flushing = false
            if (prefs.pendingDate == date) prefs.pendingSec = max(0L, prefs.pendingSec - delta)
        }.addOnFailureListener { e ->
            flushing = false
            lastFlushFail = SystemClock.elapsedRealtime()
            if (prefs.pendingDate != todayKey()) {
                // không đẩy được phần của hôm qua: bỏ qua để không tính nhầm sang hôm nay
                prefs.pendingSec = 0
                prefs.pendingDate = todayKey()
            }
            Log.w(TAG, "flush", e)
        }
    }

    fun requestExtra(minutes: Int, message: String, cb: (Boolean, String) -> Unit) {
        if (!started) return cb(false, "Chưa kết nối với ba mẹ")
        val st = status()
        if (!cfg.requestsEnabled) return cb(false, "Ba mẹ đang tắt chức năng xin thêm giờ")
        if (requests.any { it.status == "pending" }) return cb(false, "Con đang có một yêu cầu chờ ba mẹ duyệt")
        if (st.requestsLeft <= 0) return cb(false, "Hôm nay con đã xin đủ ${cfg.maxPerDay} lần rồi")
        if (minutes < 1 || minutes > 600) return cb(false, "Số phút từ 1 đến 600")
        val u = uid ?: return cb(false, "Chưa kết nối")
        val f = fid ?: return cb(false, "Chưa ghép cặp")
        val db = Fb.db
        val b = db.batch()
        b.set(
            db.collection("families/$f/requests").document(),
            hashMapOf<String, Any>(
                "minutes" to minutes.toLong(),
                "message" to message.trim().take(200),
                "deviceUid" to u,
                "deviceName" to (prefs.deviceName ?: Build.MODEL),
                "status" to "pending",
                "createdAt" to FieldValue.serverTimestamp(),
                "day" to st.dateKey,
            ),
        )
        b.set(db.document("families/$f/days/${st.dateKey}"), hashMapOf<String, Any>("requestCount" to FieldValue.increment(1)), SetOptions.merge())
        b.commit().addOnFailureListener { e ->
            Log.w(TAG, "request", e)
            main.post { Toast.makeText(ctx, "Chưa gửi được yêu cầu: ${e.message}", Toast.LENGTH_LONG).show() }
        }
        cb(true, if (Net.online(ctx)) "Đã gửi, chờ ba mẹ duyệt" else "Đã lưu, sẽ gửi khi có mạng")
    }

    fun heartbeat() {
        if (!started) return
        val u = uid ?: return
        val f = fid ?: return
        if (!Net.online(ctx)) return
        val ref = Fb.db.document("families/$f/devices/$u")
        ref.update(
            hashMapOf<String, Any>(
                "lastSeen" to FieldValue.serverTimestamp(),
                "perms" to Perms.all(ctx),
                "appVersion" to BuildConfig.VERSION_NAME,
            ),
        ).addOnSuccessListener {
            if (!Clock.synced || SystemClock.elapsedRealtime() - lastClockSync > 10 * 60_000) {
                ref.get(Source.SERVER).addOnSuccessListener { s ->
                    s.getTimestamp("lastSeen")?.let {
                        Clock.sync(ctx, it.toDate().time)
                        lastClockSync = SystemClock.elapsedRealtime()
                    }
                }
            }
        }.addOnFailureListener { e ->
            if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.NOT_FOUND) {
                // Ba mẹ đã gỡ máy này khỏi gia đình
                main.post { Pairing.reset(ctx) }
            }
        }
    }

    fun syncApps() {
        if (!started || !appsFromServer) return
        val f = fid ?: return
        launchers = Apps.launchers(ctx)
        for ((pkg, label) in Apps.launchable(ctx)) {
            if (pkg == ctx.packageName || apps.containsKey(pkg) || isNeutral(pkg) || pkg in Apps.PROTECTED) continue
            Fb.db.document("families/$f/apps/$pkg").set(
                hashMapOf<String, Any>(
                    "label" to label,
                    "category" to "pending",
                    "suggested" to Apps.suggest(ctx, pkg),
                    "deviceName" to (prefs.deviceName ?: Build.MODEL),
                    "firstSeen" to FieldValue.serverTimestamp(),
                ),
            ).addOnFailureListener { e -> Log.w(TAG, "app $pkg", e) }
        }
    }

    private fun isNeutral(pkg: String) = pkg in Apps.NEUTRAL || pkg in launchers

    fun categorize(pkg: String?): Cat = when {
        pkg == null || !started -> Cat.NEUTRAL
        pkg == ctx.packageName -> Cat.NEUTRAL
        pkg in Apps.PROTECTED -> Cat.PROTECTED
        isNeutral(pkg) -> Cat.NEUTRAL
        apps[pkg] == "study" -> Cat.STUDY
        else -> Cat.PLAY
    }

    fun studyApps(): List<Pair<String, String>> {
        if (!started) return emptyList()
        return Apps.launchable(ctx).filter { apps[it.key] == "study" }.map { it.key to it.value }.sortedBy { it.second }
    }

    fun inGrace(): Boolean = SystemClock.elapsedRealtime() < graceUntil

    fun verifyPin(pin: String): Boolean {
        val h = cfg.pinHash ?: return false
        val s = cfg.pinSalt ?: return false
        return sha256("$s:$pin") == h
    }

    fun unlock(minutes: Int) {
        prefs.unlockUntil = Clock.now() + minutes * 60_000L
        changed()
    }

    fun lockAgain() {
        prefs.unlockUntil = 0
        changed()
    }

    private fun sha256(t: String): String =
        MessageDigest.getInstance("SHA-256").digest(t.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

object Notifier {
    const val CH_GUARD = "guard"
    const val CH_EVENTS = "events"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_GUARD, "Giờ Vàng đang chạy", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_EVENTS, "Tin từ ba mẹ", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openApp(ctx: Context): PendingIntent =
        PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    fun guard(ctx: Context, text: String): Notification =
        Notification.Builder(ctx, CH_GUARD)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Giờ Vàng")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .build()

    fun decision(ctx: Context, r: Engine.Req) {
        val text = if (r.status == "approved") "Ba mẹ đã cho thêm ${r.grantedMin} phút" else "Ba mẹ chưa đồng ý cho thêm giờ lần này"
        val n = Notification.Builder(ctx, CH_EVENTS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Giờ Vàng")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(2, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "notify", e)
        }
    }
}
