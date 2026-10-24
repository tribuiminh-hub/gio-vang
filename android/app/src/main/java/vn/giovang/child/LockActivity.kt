package vn.giovang.child

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Toast

// Màn hình khóa khi hết giờ, ngoài giờ, đang nghỉ, hoặc mở Cài đặt.
// Cũng dùng cho: xin thêm giờ, mở app học, ba mẹ nhập PIN.
class LockActivity : Activity() {
    companion object {
        @Volatile var visible = false
            private set
        var current: LockActivity? = null
            private set

        private var wrongPins = 0
        private var pinLockedUntil = 0L
    }

    private lateinit var root: LinearLayout
    private var mode = "lock" // lock | request | study | pin
    private var reason = ""
    private var fromApp = false
    private var reqMinutes = 15
    private var unlockMinutes = 30
    private var info = ""
    private var pinDraft = ""
    private var msgDraft = ""
    private var lastRenderSec = -1L

    private val h = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (mode == "lock") {
                val s = Engine.status()
                if (s.reason == Reason.BREAK && s.breakLeftSec != lastRenderSec) render()
            }
            h.postDelayed(this, 1_000L)
        }
    }
    private val onEngine: () -> Unit = { if (mode == "lock") render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.start(this)
        root = Ui.screen(this, Ui.DARK)
        readIntent(intent)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
        render()
    }

    private fun readIntent(i: Intent) {
        reason = i.getStringExtra("reason") ?: ""
        mode = i.getStringExtra("mode") ?: "lock"
        fromApp = i.getBooleanExtra("fromApp", false)
        info = ""
    }

    override fun onResume() {
        super.onResume()
        visible = true
        current = this
        Engine.addListener(onEngine)
        h.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        visible = false
        current = null
        Engine.removeListener(onEngine)
        h.removeCallbacks(ticker)
    }

    override fun onStop() {
        super.onStop()
        // Rời màn hình khóa thì đóng luôn, lần sau mở game sẽ khóa lại
        if (!isChangingConfigurations && mode == "lock") finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            mode != "lock" && !fromApp -> { mode = "lock"; info = ""; render() }
            fromApp -> finish()
            else -> goHome()
        }
    }

    fun refresh() = render()

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun stillLocked(st: Status): Boolean =
        if (reason == "protected") Engine.cfg.protectSettings && !st.unlocked && !Engine.inGrace() else st.blocked

    private fun render() {
        root.removeAllViews()
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.add(Ui.text(this, "Giờ Vàng", 16f, android.graphics.Color.WHITE, bold = true), 0)
        when (mode) {
            "request" -> renderRequest()
            "study" -> renderStudy()
            "pin" -> renderPin()
            else -> renderLock()
        }
        if (info.isNotEmpty()) {
            root.add(Ui.text(this, info, 15f, Ui.DARK_MUTED).apply { gravity = Gravity.CENTER }, 16)
        }
    }

    private fun title(s: String, sub: String) {
        root.add(Ui.text(this, s, 30f, android.graphics.Color.WHITE, bold = true).apply { gravity = Gravity.CENTER }, 56)
        if (sub.isNotEmpty()) root.add(Ui.text(this, sub, 17f, Ui.DARK_MUTED).apply { gravity = Gravity.CENTER }, 12)
    }

    private fun renderLock() {
        val st = Engine.status()
        val name = Engine.childName
        if (!stillLocked(st)) {
            title("Con dùng tiếp được rồi", if (st.reason == Reason.NONE) "Còn ${Policy.fmtDur(st.canPlaySec)} chơi." else "Ba mẹ đang mở khóa.")
            root.add(Ui.button(this, "Đóng", Ui.ORANGE) { finish() }, 32)
            return
        }
        when {
            reason == "protected" -> title("Cần ba mẹ mở khóa", "Phần Cài đặt được ba mẹ khóa bằng mã PIN.")
            st.reason == Reason.HOURS && st.beforeOpen -> title("Chưa đến giờ dùng máy", "Máy mở lúc ${st.openAt}. $name ngủ ngon nhé.")
            st.reason == Reason.HOURS -> title("Hết giờ dùng máy hôm nay rồi", "Đã đến ${st.cutoff}. Máy sẽ mở lại lúc ${st.openAt} sáng mai.")
            st.reason == Reason.QUOTA -> title("Hết giờ chơi hôm nay rồi", "$name đã chơi đủ ${Policy.fmtDur(st.quotaSec)} trên các máy.")
            st.reason == Reason.BREAK -> {
                lastRenderSec = st.breakLeftSec
                title("Nghỉ giải lao nào", "$name đã chơi liên tục ${Engine.cfg.sessionMaxMin} phút. Đi uống nước, vận động một chút nhé.")
                root.add(Ui.text(this, Policy.fmtClock(st.breakLeftSec), 64f, android.graphics.Color.WHITE, bold = true).apply { gravity = Gravity.CENTER }, 24)
                root.add(Ui.text(this, "nữa là chơi tiếp được", 15f, Ui.DARK_MUTED).apply { gravity = Gravity.CENTER }, 4)
            }
        }

        val pend = Engine.requests.firstOrNull { it.status == "pending" }
        if (pend != null) {
            root.add(Ui.text(this, "Đã gửi xin thêm ${pend.minutes} phút, chờ ba mẹ duyệt.", 15f, Ui.WARN_SOFT, bold = true).apply { gravity = Gravity.CENTER }, 24)
        }

        val canAsk = reason != "protected" && Engine.cfg.requestsEnabled && st.requestsLeft > 0 && pend == null
        if (canAsk) {
            root.add(Ui.button(this, "Xin thêm giờ", Ui.ORANGE) { mode = "request"; info = ""; render() }, 36)
            root.add(Ui.text(this, "Hôm nay còn xin được ${st.requestsLeft} lần", 13f, Ui.DARK_MUTED).apply { gravity = Gravity.CENTER }, 6)
        }
        if (reason != "protected" && Engine.studyApps().isNotEmpty()) {
            root.add(Ui.button(this, "Mở app học", Ui.GHOST_DARK) { mode = "study"; info = ""; render() }, if (canAsk) 16 else 36)
        }
        root.add(Ui.button(this, "Ba mẹ mở khóa", Ui.GHOST_DARK) { mode = "pin"; info = ""; render() }, 16)
        root.add(Ui.button(this, "Về màn hình chính", Ui.GHOST_DARK) { goHome() }, 16)
    }

    private fun renderRequest() {
        title("Xin thêm giờ", "Ba mẹ sẽ nhận được yêu cầu của con ngay.")
        val chips = Ui.row(this)
        val msg = Ui.input(this, "Ví dụ: con đang chơi dở màn").apply { setText(msgDraft) }
        listOf(15, 30, 60).forEachIndexed { i, m ->
            chips.add(Ui.chip(this, if (m == 60) "1 giờ" else "$m phút", reqMinutes == m) {
                reqMinutes = m
                msgDraft = msg.text.toString()
                render()
            }, if (i == 0) 0 else 8, weight = 1f)
        }
        root.add(chips, 32)
        root.add(Ui.text(this, "Hoặc nhập số phút", 14f, Ui.DARK_MUTED), 16)
        val minutes = Ui.input(this, "Số phút", numeric = true).apply { setText(reqMinutes.toString()) }
        root.add(minutes, 6)
        root.add(Ui.text(this, "Lời nhắn cho ba mẹ (không bắt buộc)", 14f, Ui.DARK_MUTED), 16)
        root.add(msg, 6)
        root.add(Ui.button(this, "Gửi cho ba mẹ", Ui.ORANGE) {
            val m = minutes.text.toString().trim().toIntOrNull() ?: 0
            Engine.requestExtra(m, msg.text.toString()) { ok, text ->
                Toast.makeText(this, text, Toast.LENGTH_LONG).show()
                msgDraft = if (ok) "" else msg.text.toString()
                if (ok) {
                    if (fromApp) finish() else { mode = "lock"; info = text; render() }
                } else {
                    info = text
                    render()
                }
            }
        }, 24)
        root.add(Ui.button(this, "Quay lại", Ui.GHOST_DARK) { onBackPressedCompat() }, 12)
    }

    private fun renderStudy() {
        title("Mở app học", "App học không tính vào giờ chơi.")
        val list = Engine.studyApps()
        if (list.isEmpty()) root.add(Ui.text(this, "Ba mẹ chưa chọn app học nào.", 15f, Ui.DARK_MUTED).apply { gravity = Gravity.CENTER }, 24)
        list.forEachIndexed { i, (pkg, label) ->
            root.add(Ui.button(this, label) {
                packageManager.getLaunchIntentForPackage(pkg)?.let {
                    startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                }
            }, if (i == 0) 32 else 12)
        }
        root.add(Ui.button(this, "Quay lại", Ui.GHOST_DARK) { onBackPressedCompat() }, 24)
    }

    private fun renderPin() {
        title("Ba mẹ mở khóa", "Nhập mã PIN của ba mẹ.")
        val pin = Ui.input(this, "Mã PIN", numeric = true, password = true).apply { setText(pinDraft) }
        root.add(pin, 32)
        val st = Engine.status()
        root.add(Ui.text(this, "Mở khóa trong", 14f, Ui.DARK_MUTED), 16)
        val chips = Ui.row(this)
        listOf(15, 30, 60).forEachIndexed { i, m ->
            chips.add(Ui.chip(this, if (m == 60) "1 giờ" else "$m phút", unlockMinutes == m) {
                unlockMinutes = m
                pinDraft = pin.text.toString()
                render()
            }, if (i == 0) 0 else 8, weight = 1f)
        }
        root.add(chips, 6)
        root.add(Ui.button(this, "Mở khóa", Ui.ORANGE) {
            val now = SystemClock.elapsedRealtime()
            if (now < pinLockedUntil) {
                info = "Nhập sai nhiều lần. Thử lại sau ${(pinLockedUntil - now) / 1000 + 1} giây."
                render()
                return@button
            }
            if (!Engine.configLoaded) {
                info = "Chưa tải được cài đặt của ba mẹ. Kết nối mạng rồi thử lại."
                render()
                return@button
            }
            pinDraft = ""
            if (Engine.verifyPin(pin.text.toString().trim())) {
                wrongPins = 0
                Engine.unlock(unlockMinutes)
                Toast.makeText(this, "Đã mở khóa $unlockMinutes phút", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                wrongPins++
                if (wrongPins >= 5) {
                    pinLockedUntil = now + 60_000L
                    wrongPins = 0
                }
                info = "Sai mã PIN"
                render()
            }
        }, 24)
        if (st.unlocked) {
            root.add(Ui.button(this, "Khóa lại ngay", Ui.GHOST_DARK) { Engine.lockAgain(); mode = "lock"; render() }, 12)
        }
        root.add(Ui.button(this, "Quay lại", Ui.GHOST_DARK) { onBackPressedCompat() }, 12)
    }

    private fun onBackPressedCompat() {
        if (fromApp) finish() else { mode = "lock"; info = ""; render() }
    }
}
