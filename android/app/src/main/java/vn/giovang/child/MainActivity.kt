package vn.giovang.child

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.Toast

// Màn hình chính: ghép cặp, cấp quyền, xem giờ còn lại, khu vực ba mẹ (cần PIN)
class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var prefs: Prefs
    private var busy = false
    private var message = ""
    private var parentUntil = 0L
    private var askPin = false
    private val onEngine: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        root = Ui.screen(this)
    }

    override fun onResume() {
        super.onResume()
        if (prefs.paired) {
            Engine.start(this)
            GuardService.start(this)
        }
        Engine.addListener(onEngine)
        render()
    }

    override fun onPause() {
        super.onPause()
        Engine.removeListener(onEngine)
    }

    private val parentMode: Boolean get() = SystemClock.elapsedRealtime() < parentUntil

    private fun render() {
        root.removeAllViews()
        if (!prefs.paired) renderPair() else renderHome()
    }

    // ---------- Ghép cặp ----------
    private fun renderPair() {
        root.add(Ui.text(this, "Giờ Vàng", 28f, Ui.TEXT, bold = true), 0)
        root.add(Ui.text(this, "Cài đặt máy của con", 16f, Ui.MUTED), 4)
        val c = Ui.card(this)
        c.add(Ui.text(this, "Ba mẹ mở web Giờ Vàng trên điện thoại, vào Thiết bị → Tạo mã cài đặt, rồi gửi mã sang máy này (Zalo, tin nhắn…). Sau đó dán mã vào ô dưới đây.", 15f, Ui.MUTED), 0)
        val code = Ui.input(this, "Dán mã cài đặt (bắt đầu bằng GV1.)", multiLine = true)
        c.add(code, 14)
        c.add(Ui.button(this, "Dán từ bộ nhớ tạm") {
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
            val t = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            if (t.isNullOrBlank()) toast("Bộ nhớ tạm đang trống") else code.setText(t)
        }, 8)
        c.add(Ui.text(this, "Tên máy này", 14f, Ui.TEXT, bold = true), 16)
        val name = Ui.input(this, "Ví dụ: Máy tính bảng của Sóc").apply { setText("${Build.MANUFACTURER} ${Build.MODEL}") }
        c.add(name, 6)
        c.add(Ui.button(this, if (busy) "Đang ghép cặp…" else "Ghép cặp", Ui.PRIMARY) {
            if (busy) return@button
            busy = true
            message = ""
            render()
            Pairing.pair(this, code.text.toString(), name.text.toString().trim().ifEmpty { Build.MODEL }) { ok, text ->
                busy = false
                message = text
                if (ok) {
                    Engine.start(this)
                    GuardService.start(this)
                    parentUntil = SystemClock.elapsedRealtime() + 15 * 60_000L
                }
                render()
            }
        }, 16)
        root.add(c, 20)
        if (message.isNotEmpty()) root.add(Ui.text(this, message, 15f, Ui.DANGER), 12)
    }

    // ---------- Màn hình chính ----------
    private fun renderHome() {
        val name = Engine.childName
        root.add(Ui.text(this, "Chào $name", 16f, Ui.BRAND, bold = true), 0)
        root.add(Ui.text(this, "Hôm nay con còn", 26f, Ui.TEXT, bold = true), 2)

        val st = Engine.status()
        val s = Ui.card(this)
        val big = when {
            st.unlocked -> "Đang mở khóa"
            st.reason == Reason.HOURS -> if (st.beforeOpen) "Mở lúc ${st.openAt}" else "Hết giờ dùng máy"
            st.reason == Reason.QUOTA -> "Hết giờ chơi"
            st.reason == Reason.BREAK -> "Nghỉ ${Policy.fmtClock(st.breakLeftSec)}"
            else -> Policy.fmtDur(st.playLeftSec)
        }
        s.add(Ui.text(this, big, 34f, Ui.BRAND, bold = true), 0)
        s.add(Ui.text(this, "Đã chơi ${Policy.fmtDur(st.playedSec)} trên ${Policy.fmtDur(st.quotaSec)} · tự khóa lúc ${st.cutoff}", 14f, Ui.MUTED), 6)
        if (Engine.cfg.sessionEnabled && st.reason == Reason.NONE) {
            s.add(Ui.text(this, "Lượt chơi này còn ${Policy.fmtDur(st.sessionLeftSec)}, sau đó nghỉ ${Engine.cfg.breakMin} phút", 14f, Ui.TEXT, bold = true), 10)
        }
        s.add(Ui.text(this, "Học: luôn mở, không tính giờ", 14f, Ui.MUTED), 10)
        if (!Engine.configLoaded) s.add(Ui.text(this, "Đang tải cài đặt của ba mẹ… (cần mạng lần đầu)", 13f, Ui.WARN), 10)
        root.add(s, 16)

        val pend = Engine.requests.firstOrNull { it.status == "pending" }
        if (pend != null) {
            val w = Ui.card(this, Ui.WARN_SOFT, Ui.WARN)
            w.add(Ui.text(this, "Đã gửi xin thêm ${pend.minutes} phút, chờ ba mẹ duyệt", 15f, Ui.WARN_TEXT, bold = true), 0)
            root.add(w, 12)
        }

        if (Engine.cfg.requestsEnabled && pend == null && st.requestsLeft > 0) {
            root.add(Ui.button(this, "Xin thêm giờ", Ui.PRIMARY) {
                startActivity(Intent(this, LockActivity::class.java).putExtra("mode", "request").putExtra("fromApp", true))
            }, 16)
        }
        if (Engine.studyApps().isNotEmpty()) {
            root.add(Ui.button(this, "Mở app học") {
                startActivity(Intent(this, LockActivity::class.java).putExtra("mode", "study").putExtra("fromApp", true))
            }, 12)
        }

        renderPermissions()

        if (parentMode) renderParent() else renderParentGate()
    }

    // ---------- Quyền cần cấp ----------
    private fun renderPermissions() {
        val perms = listOf(
            Triple("Truy cập dữ liệu sử dụng", "Bắt buộc: để biết con đang mở app nào", Perms.usage(this)) to { openSettings(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), !Perms.usage(this)) },
            Triple("Hiển thị trên ứng dụng khác", "Bắt buộc: để hiện màn hình khóa", Perms.overlay(this)) to {
                openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), !Perms.overlay(this))
            },
            Triple("Quản trị thiết bị", "Chống gỡ app khi chưa có ba mẹ", Perms.admin(this)) to {
                val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this, AdminReceiver::class.java))
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Giúp con không tự gỡ Giờ Vàng. Ba mẹ tắt được bằng mã PIN.")
                openSettings(i, !Perms.admin(this))
            },
            Triple("Không tối ưu pin", "Để Android không tự tắt Giờ Vàng", Perms.battery(this)) to {
                @Suppress("BatteryLife")
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                openSettings(i, !Perms.battery(this))
            },
            Triple("Thông báo", "Để con biết khi ba mẹ duyệt", Perms.notif(this)) to {
                if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            },
        )
        val missing = perms.count { !it.first.third }
        if (missing == 0 && !parentMode) return

        val c = Ui.card(this, if (missing > 0) Ui.WARN_SOFT else Ui.CARD, if (missing > 0) Ui.WARN else Ui.LINE)
        c.add(Ui.text(this, if (missing > 0) "Ba mẹ cần cấp $missing quyền" else "Đã cấp đủ quyền", 16f, Ui.TEXT, bold = true), 0)
        for ((info, action) in perms) {
            val (title, sub, ok) = info
            val r = Ui.row(this)
            val label = Ui.col(this)
            label.add(Ui.text(this, (if (ok) "✓ " else "• ") + title, 15f, if (ok) Ui.BRAND else Ui.TEXT, bold = true), 0)
            label.add(Ui.text(this, sub, 13f, Ui.MUTED), 2)
            r.add(label, 0, weight = 1f)
            if (!ok) r.add(Ui.button(this, "Cấp quyền", Ui.PRIMARY) { action() }, 8)
            c.add(r, 14)
        }
        if (missing > 0) {
            c.add(Ui.text(this, "Máy Xiaomi, Oppo, Vivo, Samsung: vào thông tin ứng dụng Giờ Vàng và bật thêm “Tự khởi động” (Autostart) nếu có.", 13f, Ui.MUTED), 14)
        }
        root.add(c, 16)
    }

    private fun openSettings(i: Intent, missing: Boolean) {
        // Chỉ cho mở Cài đặt không cần PIN khi quyền đang thiếu (ba mẹ đang cài đặt)
        if (missing) Engine.graceUntil = SystemClock.elapsedRealtime() + 120_000L
        try {
            startActivity(i)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                toast("Không mở được Cài đặt")
            }
        }
    }

    // ---------- Khu vực ba mẹ ----------
    private fun renderParentGate() {
        if (!askPin) {
            root.add(Ui.button(this, "Dành cho ba mẹ") { askPin = true; message = ""; render() }, 24)
            return
        }
        val c = Ui.card(this)
        c.add(Ui.text(this, "Nhập mã PIN ba mẹ", 16f, Ui.TEXT, bold = true), 0)
        val pin = Ui.input(this, "Mã PIN", numeric = true, password = true)
        c.add(pin, 10)
        val r = Ui.row(this)
        r.add(Ui.button(this, "Hủy") { askPin = false; message = ""; render() }, 0, weight = 1f)
        r.add(Ui.button(this, "Vào", Ui.PRIMARY) {
            if (!Engine.configLoaded) {
                message = "Chưa tải được cài đặt của ba mẹ, kiểm tra mạng."
            } else if (Engine.verifyPin(pin.text.toString().trim())) {
                parentUntil = SystemClock.elapsedRealtime() + 10 * 60_000L
                askPin = false
                message = ""
            } else {
                message = "Sai mã PIN"
            }
            render()
        }, 8, weight = 1f)
        c.add(r, 12)
        if (message.isNotEmpty()) c.add(Ui.text(this, message, 14f, Ui.DANGER), 8)
        root.add(c, 24)
    }

    private fun renderParent() {
        val c = Ui.card(this)
        c.add(Ui.text(this, "Khu vực ba mẹ", 16f, Ui.TEXT, bold = true), 0)
        c.add(Ui.text(this, "Máy: ${prefs.deviceName ?: Build.MODEL} · bản ${BuildConfig.VERSION_NAME}", 13f, Ui.MUTED), 4)

        c.add(Ui.text(this, "Mở khóa máy này", 14f, Ui.TEXT, bold = true), 16)
        val r = Ui.row(this)
        listOf(15, 30, 60).forEachIndexed { i, m ->
            r.add(Ui.chip(this, if (m == 60) "1 giờ" else "$m phút", false) {
                Engine.unlock(m)
                toast("Đã mở khóa $m phút")
                render()
            }, if (i == 0) 0 else 8, weight = 1f)
        }
        c.add(r, 8)
        if (Engine.status().unlocked) c.add(Ui.button(this, "Khóa lại ngay") { Engine.lockAgain(); render() }, 8)

        c.add(Ui.text(this, "Đổi tên máy", 14f, Ui.TEXT, bold = true), 16)
        val name = Ui.input(this, "Tên máy").apply { setText(prefs.deviceName ?: "") }
        c.add(name, 6)
        c.add(Ui.button(this, "Lưu tên") {
            val n = name.text.toString().trim()
            if (n.isNotEmpty()) {
                prefs.deviceName = n
                val u = Engine.uid
                val f = prefs.familyId
                if (u != null && f != null) Fb.db.document("families/$f/devices/$u").update("name", n)
                toast("Đã lưu")
            }
        }, 8)

        c.add(Ui.button(this, "Hủy ghép cặp máy này") {
            Pairing.reset(this)
            parentUntil = 0L
            toast("Đã hủy ghép cặp. Nhớ gỡ máy này trong web ba mẹ.")
            render()
        }.apply { setTextColor(Ui.DANGER) }, 20)
        c.add(Ui.button(this, "Thoát khu vực ba mẹ") { parentUntil = 0L; render() }, 8)
        root.add(c, 24)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
