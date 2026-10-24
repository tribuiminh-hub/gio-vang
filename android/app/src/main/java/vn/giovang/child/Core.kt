package vn.giovang.child

import android.app.AppOpsManager
import android.app.Application
import android.app.admin.DevicePolicyManager
import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import org.json.JSONObject

const val TAG = "GioVang"

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Clock.restore(this)
        Fb.ensure(this)
        Notifier.channels(this)
    }
}

// ---------- Lưu trữ cục bộ ----------
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("giovang", Context.MODE_PRIVATE)

    var setup: String?
        get() = sp.getString("setup", null)
        set(v) = sp.edit().putString("setup", v).apply()
    var familyId: String?
        get() = sp.getString("familyId", null)
        set(v) = sp.edit().putString("familyId", v).apply()
    var deviceName: String?
        get() = sp.getString("deviceName", null)
        set(v) = sp.edit().putString("deviceName", v).apply()
    var childName: String?
        get() = sp.getString("childName", null)
        set(v) = sp.edit().putString("childName", v).apply()
    var paired: Boolean
        get() = sp.getBoolean("paired", false)
        set(v) = sp.edit().putBoolean("paired", v).apply()
    var unlockUntil: Long
        get() = sp.getLong("unlockUntil", 0L)
        set(v) = sp.edit().putLong("unlockUntil", v).apply()
    var pendingSec: Long
        get() = sp.getLong("pendingSec", 0L)
        set(v) = sp.edit().putLong("pendingSec", v).apply()
    var pendingDate: String?
        get() = sp.getString("pendingDate", null)
        set(v) = sp.edit().putString("pendingDate", v).apply()
    val clockServer: Long get() = sp.getLong("clockServer", 0L)
    val clockElapsed: Long get() = sp.getLong("clockElapsed", 0L)
    val clockBoot: Int get() = sp.getInt("clockBoot", -2)

    fun saveClock(server: Long, elapsed: Long, boot: Int) {
        sp.edit().putLong("clockServer", server).putLong("clockElapsed", elapsed).putInt("clockBoot", boot).apply()
    }

    fun clear() = sp.edit().clear().apply()
}

// ---------- Giờ theo máy chủ, không tin giờ trên máy ----------
object Clock {
    @Volatile private var server = 0L
    @Volatile private var elapsed = 0L

    val synced: Boolean get() = server != 0L

    fun now(): Long =
        if (server == 0L) System.currentTimeMillis() else server + (SystemClock.elapsedRealtime() - elapsed)

    fun sync(ctx: Context, serverMs: Long) {
        server = serverMs
        elapsed = SystemClock.elapsedRealtime()
        Prefs(ctx).saveClock(server, elapsed, bootCount(ctx))
    }

    fun restore(ctx: Context) {
        val p = Prefs(ctx)
        val boot = bootCount(ctx)
        if (boot >= 0 && p.clockBoot == boot && p.clockServer > 0 && SystemClock.elapsedRealtime() >= p.clockElapsed) {
            server = p.clockServer
            elapsed = p.clockElapsed
        }
    }

    private fun bootCount(ctx: Context): Int =
        try { Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT) } catch (e: Exception) { -1 }
}

// ---------- Mã cài đặt từ web ba mẹ ----------
data class SetupCode(
    val apiKey: String,
    val projectId: String,
    val appId: String,
    val familyId: String,
    val pairCode: String,
    val childName: String,
) {
    companion object {
        fun parse(text: String?): SetupCode? = try {
            var t = (text ?: "").trim()
            val i = t.indexOf("GV1.")
            if (i >= 0) t = t.substring(i + 4)
            t = t.replace(Regex("\\s"), "")
            val j = JSONObject(String(Base64.decode(t, Base64.DEFAULT), Charsets.UTF_8))
            SetupCode(
                apiKey = j.getString("k"),
                projectId = j.getString("p"),
                appId = j.getString("a"),
                familyId = j.getString("f"),
                pairCode = j.getString("c"),
                childName = j.optString("n", "con"),
            )
        } catch (e: Exception) {
            null
        }
    }
}

// ---------- Firebase khởi tạo theo mã cài đặt (không cần google-services.json) ----------
object Fb {
    fun init(ctx: Context, s: SetupCode): FirebaseApp {
        val existing = FirebaseApp.getApps(ctx).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
        if (existing != null) {
            if (existing.options.projectId == s.projectId) return existing
            existing.delete()
        }
        val o = FirebaseOptions.Builder()
            .setApiKey(s.apiKey)
            .setApplicationId(s.appId)
            .setProjectId(s.projectId)
            .build()
        return FirebaseApp.initializeApp(ctx, o)
    }

    fun ensure(ctx: Context): Boolean {
        val s = SetupCode.parse(Prefs(ctx).setup) ?: return false
        return try {
            init(ctx, s)
            true
        } catch (e: Exception) {
            Log.w(TAG, "firebase init", e)
            false
        }
    }

    val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
}

// ---------- Ghép cặp và hủy ghép cặp ----------
object Pairing {
    fun pair(ctx: Context, raw: String, name: String, cb: (Boolean, String) -> Unit) {
        val code = SetupCode.parse(raw) ?: return cb(false, "Mã cài đặt chưa đúng. Ba mẹ sao chép lại toàn bộ mã nhé.")
        val app = try {
            Fb.init(ctx, code)
        } catch (e: Exception) {
            return cb(false, "Cấu hình Firebase trong mã không hợp lệ.")
        }
        val auth = FirebaseAuth.getInstance(app)
        val db = FirebaseFirestore.getInstance(app)
        auth.signInAnonymously().addOnSuccessListener { r ->
            val uid = r.user?.uid ?: return@addOnSuccessListener cb(false, "Không đăng nhập được.")
            val data = hashMapOf<String, Any>(
                "name" to name,
                "platform" to "android",
                "pairCode" to code.pairCode,
                "appVersion" to BuildConfig.VERSION_NAME,
                "createdAt" to FieldValue.serverTimestamp(),
                "lastSeen" to FieldValue.serverTimestamp(),
            )
            db.document("families/${code.familyId}/devices/$uid").set(data)
                .addOnSuccessListener {
                    val p = Prefs(ctx)
                    p.setup = raw.trim()
                    p.familyId = code.familyId
                    p.deviceName = name
                    p.childName = code.childName
                    p.pendingSec = 0
                    p.paired = true
                    cb(true, "Đã ghép cặp")
                }
                .addOnFailureListener { e ->
                    val denied = e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
                    cb(false, if (denied) "Mã đã hết hạn hoặc không đúng. Ba mẹ tạo mã mới nhé." else "Lỗi kết nối: ${e.message}")
                }
        }.addOnFailureListener { e ->
            cb(false, "Không đăng nhập được: ${e.message}. Kiểm tra đã bật đăng nhập Ẩn danh (Anonymous) trong Firebase.")
        }
    }

    fun reset(ctx: Context) {
        Engine.stop()
        ctx.stopService(Intent(ctx, GuardService::class.java))
        try {
            val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
            dpm.removeActiveAdmin(ComponentName(ctx, AdminReceiver::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "remove admin", e)
        }
        try { Fb.auth.signOut() } catch (e: Exception) { Log.w(TAG, "signout", e) }
        Prefs(ctx).clear()
    }
}

// ---------- Danh sách app ----------
object Apps {
    private val KNOWN_STUDY = setOf(
        "com.google.android.apps.classroom",
        "com.duolingo",
        "org.khanacademy.android",
        "com.microblink.photomath",
        "com.quizlet.quizletandroid",
        "com.google.android.apps.docs.editors.docs",
        "com.google.android.apps.docs.editors.sheets",
        "com.google.android.apps.docs.editors.slides",
        "com.microsoft.office.word",
        "com.microsoft.teams",
        "com.google.android.apps.translate",
        "com.socratic.android",
    )

    // App luôn được dùng, không tính giờ, không bị khóa
    val NEUTRAL = setOf(
        "android", "com.android.systemui",
        "com.android.phone", "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer",
        "com.android.incallui", "com.samsung.android.incallui", "com.android.server.telecom",
        "com.android.contacts", "com.google.android.contacts", "com.samsung.android.app.contacts",
        "com.android.emergency", "com.google.android.apps.safetyhub",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.cellbroadcastreceiver", "com.google.android.cellbroadcastreceiver",
        "com.google.android.inputmethod.latin", "com.samsung.android.honeyboard",
        "com.android.deskclock", "com.google.android.deskclock", "com.sec.android.app.clockpackage",
    )

    // Cần PIN ba mẹ mới mở được (khi bật "Khóa Cài đặt bằng PIN")
    val PROTECTED = setOf(
        "com.android.settings",
        "com.google.android.packageinstaller", "com.android.packageinstaller",
        "com.miui.securitycenter", "com.coloros.safecenter", "com.oplus.safecenter",
        "com.huawei.systemmanager", "com.samsung.android.lool", "com.samsung.android.sm.devicesecurity",
    )

    @Suppress("DEPRECATION")
    fun launchers(ctx: Context): Set<String> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return ctx.packageManager.queryIntentActivities(i, 0).map { it.activityInfo.packageName }.toSet()
    }

    @Suppress("DEPRECATION")
    fun launchable(ctx: Context): Map<String, String> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0).associate { it.activityInfo.packageName to it.loadLabel(pm).toString() }
    }

    fun suggest(ctx: Context, pkg: String): String {
        if (pkg in KNOWN_STUDY) return "study"
        return try {
            val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
            if (ai.category == ApplicationInfo.CATEGORY_PRODUCTIVITY) "study" else "play"
        } catch (e: PackageManager.NameNotFoundException) {
            "play"
        }
    }
}

// ---------- Quyền ----------
object Perms {
    fun usage(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        if (mode == AppOpsManager.MODE_DEFAULT) {
            return ctx.checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun overlay(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun admin(ctx: Context): Boolean =
        ctx.getSystemService(DevicePolicyManager::class.java).isAdminActive(ComponentName(ctx, AdminReceiver::class.java))

    fun battery(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    fun notif(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun all(ctx: Context): Map<String, Boolean> = mapOf(
        "usage" to usage(ctx),
        "overlay" to overlay(ctx),
        "admin" to admin(ctx),
        "battery" to battery(ctx),
        "notif" to notif(ctx),
    )
}

object Net {
    fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

// ---------- Receivers ----------
class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Tắt quyền này sẽ cho phép gỡ Giờ Vàng. Cần ba mẹ đồng ý."
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        GuardService.start(context)
    }
}
