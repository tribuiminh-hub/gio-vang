package vn.giovang.child

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

// Biết app nào đang mở trên màn hình, dựa vào dữ liệu sử dụng của Android
class ForegroundDetector(ctx: Context) {
    private val usm = ctx.getSystemService(UsageStatsManager::class.java)
    private var lastQuery = System.currentTimeMillis() - 3_600_000L
    var pkg: String? = null
        private set

    fun poll(): String? {
        val end = System.currentTimeMillis()
        val begin = minOf(lastQuery, end - 5_000L) - 1_000L
        try {
            val events = usm.queryEvents(begin, end)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                // 1 = ACTIVITY_RESUMED (MOVE_TO_FOREGROUND trên Android cũ)
                if (e.eventType == 1) pkg = e.packageName
            }
        } catch (e: Exception) {
            Log.w(TAG, "usage", e)
        }
        lastQuery = end
        return pkg
    }
}

class GuardService : Service() {
    companion object {
        @Volatile var running = false
            private set

        fun start(ctx: Context) {
            if (!Prefs(ctx).paired) return
            try {
                ctx.startForegroundService(Intent(ctx, GuardService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "start service", e)
            }
        }
    }

    private val h = Handler(Looper.getMainLooper())
    private lateinit var det: ForegroundDetector
    private lateinit var power: PowerManager
    private lateinit var keyguard: KeyguardManager
    private var lastTick = 0L
    private var accMs = 0L
    private var lastFlush = 0L
    private var lastBeat = 0L
    private var lastApps = 0L
    private var lastLock = 0L
    private var lastNotif = ""

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            lastApps = 0L // có app mới: đồng bộ danh sách ở nhịp sau
        }
    }

    private val loop = object : Runnable {
        override fun run() {
            try {
                tick()
            } catch (e: Exception) {
                Log.e(TAG, "tick", e)
            }
            h.postDelayed(this, 2_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startFg()
        running = true
        det = ForegroundDetector(this)
        power = getSystemService(PowerManager::class.java)
        keyguard = getSystemService(KeyguardManager::class.java)
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pkgReceiver, f, Context.RECEIVER_EXPORTED) else registerReceiver(pkgReceiver, f)
        Engine.start(this)
        h.post(loop)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        h.removeCallbacksAndMessages(null)
        try { unregisterReceiver(pkgReceiver) } catch (e: Exception) { Log.w(TAG, "unregister", e) }
        Engine.flush(force = true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startFg() {
        Notifier.channels(this)
        val n = Notifier.guard(this, "Đang bảo vệ giờ chơi")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun tick() {
        if (!Prefs(this).paired) {
            stopSelf()
            return
        }
        if (!Engine.started && !Engine.start(this)) return
        Engine.maintain()

        val now = SystemClock.elapsedRealtime()
        val elapsed = if (lastTick == 0L) 0L else (now - lastTick).coerceIn(0L, 10_000L)
        lastTick = now

        val screenOn = power.isInteractive && !keyguard.isKeyguardLocked
        val pkg = if (screenOn) det.poll() else null
        val cat = Engine.categorize(pkg)

        if (screenOn && cat == Engine.Cat.PLAY) {
            accMs += elapsed
            if (accMs >= 1_000L) {
                val sec = accMs / 1_000L
                accMs -= sec * 1_000L
                Engine.addPlay(sec)
            }
        }

        val st = Engine.status()
        if (screenOn && pkg != null) {
            if (cat == Engine.Cat.PROTECTED && Engine.cfg.protectSettings && !st.unlocked && !Engine.inGrace()) {
                showLock("protected", now)
            } else if (cat == Engine.Cat.PLAY && st.blocked) {
                showLock(st.reason.name, now)
            }
        }

        // Đẩy giờ chơi lên máy chủ: mỗi 20 giây, hoặc ngay khi vừa chạm giới hạn
        val crossed = cat == Engine.Cat.PLAY && st.reason != Reason.NONE
        if (Engine.pending() > 0 && (now - lastFlush > 20_000L || crossed)) {
            lastFlush = now
            Engine.flush()
        }
        if (now - lastBeat > 60_000L || lastBeat == 0L) {
            lastBeat = now
            Engine.heartbeat()
        }
        if (lastApps == 0L || now - lastApps > 15 * 60_000L) {
            lastApps = now
            Engine.syncApps()
        }
        updateNotif(st)
    }

    private fun showLock(reason: String, now: Long) {
        if (LockActivity.visible) {
            LockActivity.current?.refresh()
            return
        }
        if (now - lastLock < 1_500L) return
        lastLock = now
        val i = Intent(this, LockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra("reason", reason)
            .putExtra("mode", "lock")
        try {
            startActivity(i)
        } catch (e: Exception) {
            Log.w(TAG, "lock", e)
        }
    }

    private fun updateNotif(st: Status) {
        val text = when {
            st.unlocked -> "Ba mẹ đang mở khóa"
            st.reason == Reason.HOURS -> if (st.beforeOpen) "Mở máy lúc ${st.openAt}" else "Đã qua giờ dùng máy hôm nay"
            st.reason == Reason.QUOTA -> "Hết giờ chơi hôm nay"
            st.reason == Reason.BREAK -> "Đang nghỉ giải lao"
            else -> "Hôm nay còn ${Policy.fmtDur(st.playLeftSec)} chơi"
        }
        if (text == lastNotif) return
        lastNotif = text
        getSystemService(NotificationManager::class.java).notify(1, Notifier.guard(this, text))
    }
}
