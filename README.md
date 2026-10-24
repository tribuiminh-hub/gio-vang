# Giờ Vàng: quản lý giờ chơi của con

Gồm 3 phần:

| Phần | Chạy ở đâu | Làm gì |
|---|---|---|
| **Web ba mẹ** (`web/`) | Điện thoại của ba mẹ, mở bằng link | Xem giờ đã chơi, chỉnh quy tắc, duyệt xin thêm giờ, chọn app Học/Chơi, ghép cặp máy của con |
| **App máy con** (`android/`) | Điện thoại, máy tính bảng Android của con | Đếm giờ chơi, khóa khi hết giờ, ngoài giờ, hết lượt 20 phút; con xin thêm giờ |
| **Firebase** (`firestore.rules`) | Máy chủ miễn phí của Google | Đồng bộ quỹ giờ chung giữa các máy |

Laptop Windows sẽ làm ở đợt sau.

## Tính năng

- Quỹ giờ chơi mỗi ngày **dùng chung** cho mọi máy, đặt riêng ngày thường và cuối tuần.
- Giờ mở máy và giờ tự khóa, đặt riêng ngày thường và cuối tuần.
- Mỗi lần chơi liên tục tối đa 20 phút (chỉnh được), sau đó nghỉ 10 phút (chỉnh được). Có nút bật/tắt.
- Chỉ tách **Chơi** và **Học**. App học không tính giờ và luôn mở. App mới cài tự tính là Chơi cho tới khi ba mẹ chọn.
- Con xin thêm giờ, ba mẹ chọn 15/30/60 phút hoặc gõ số phút tùy ý. Giới hạn số lần xin mỗi ngày, có nút bật/tắt.
- Ba mẹ cho thêm giờ ngay, không cần chờ con xin.
- Mã PIN ba mẹ để mở khóa tại chỗ, mở Cài đặt, hoặc gỡ app.
- Chống lách: giờ lấy theo máy chủ (đổi giờ trên máy không có tác dụng), khóa Cài đặt bằng PIN, quyền Quản trị thiết bị chống gỡ app, báo cho ba mẹ khi máy mất liên lạc.

---

## Cài đặt (làm được hoàn toàn trên điện thoại)

### Bước 1. Tạo Firebase (khoảng 10 phút)

1. Vào <https://console.firebase.google.com>, đăng nhập Google, bấm **Create a project**, đặt tên ví dụ `gio-vang`. Có thể tắt Google Analytics.
2. **Build → Authentication → Get started**, rồi ở tab *Sign-in method* bật:
   - **Google** (cho ba mẹ đăng nhập)
   - **Anonymous** (cho máy của con)
3. **Build → Firestore Database → Create database**, chọn vùng `asia-southeast1` (Singapore), chế độ *production*.
4. Ở tab **Rules** của Firestore, xóa hết nội dung cũ, dán toàn bộ file `firestore.rules`, rồi bấm **Publish**.
5. **Project settings (bánh răng) → Your apps → biểu tượng `</>` (Web)**, đặt tên `gio-vang-web`, bấm Register. Sao chép đoạn `firebaseConfig = { ... }`.
6. **Authentication → Settings → Authorized domains → Add domain**: thêm tên miền của web ba mẹ, ví dụ `ten-github-cua-anh.github.io`.

### Bước 2. Mở web ba mẹ

1. Mở link web ba mẹ (GitHub Pages, ví dụ `https://ten-github-cua-anh.github.io/gio-vang/`).
2. Dán đoạn `firebaseConfig` ở bước 1.5 vào, rồi bấm **Lưu và tiếp tục**.
3. Đăng nhập Google, nhập tên con và đặt **mã PIN ba mẹ** (4–6 số).
4. Trong Chrome, bấm ⋮ → **Thêm vào màn hình chính** để dùng như một app.

> Muốn khỏi dán cấu hình trên mỗi điện thoại: sửa file `web/config.js`, thay `null` bằng đoạn cấu hình.

### Bước 3. Cài app lên máy của con

1. Trên máy của con, tải **GioVang.apk** ở mục *Releases* của repo, bản `latest`.
2. Mở file APK. Android sẽ hỏi cho phép cài app từ nguồn này, bấm cho phép rồi **Cài đặt**.
3. Trên web ba mẹ: **Thiết bị → Tạo mã cài đặt → Gửi qua Zalo…** (hoặc Sao chép).
4. Trên máy của con: mở Giờ Vàng, dán mã, đặt tên máy, bấm **Ghép cặp**.
5. Bấm **Cấp quyền** lần lượt cho từng mục:
   - Truy cập dữ liệu sử dụng (bắt buộc)
   - Hiển thị trên ứng dụng khác (bắt buộc)
   - Quản trị thiết bị (chống gỡ app)
   - Không tối ưu pin
   - Thông báo
6. Máy Xiaomi, Oppo, Vivo, Samsung: vào *Cài đặt → Ứng dụng → Giờ Vàng* và bật thêm **Tự khởi động / Autostart**, **Không giới hạn pin**.
7. Trên web ba mẹ, mục **Ứng dụng**: chọn những app thuộc nhóm **Học**. Các app còn lại tính là Chơi.

Lặp lại bước 3 cho từng máy của con.

---

## Cách hoạt động

- App trên máy con kiểm tra **app đang mở** mỗi 2 giây. Nếu là app Chơi thì cộng giờ, cứ 20 giây gửi lên Firebase một lần.
- Khi hết quỹ giờ, ngoài khung giờ, hoặc hết lượt chơi, mở app Chơi sẽ hiện **màn hình khóa**. Con vẫn dùng được app Học, gọi điện và danh bạ.
- Mất mạng: app vẫn đếm giờ và khóa bình thường, có mạng lại thì đồng bộ.
- Ba mẹ đồng ý thêm giờ: quỹ giờ hôm nay tăng lên. Nếu đã gần giờ tự khóa thì giờ khóa được lùi cho đủ số phút vừa cho.

## Giới hạn hiện tại

- **Thông báo cho ba mẹ** chỉ hiện khi web ba mẹ đang mở (hoặc mở lại sẽ thấy). Muốn báo ngay cả khi tắt web thì cần nâng Firebase lên gói Blaze (trả theo dùng, gia đình gần như 0đ) để chạy Cloud Functions. Có thể làm ở đợt sau.
- Chưa có app Windows.
- Con có thể khôi phục cài đặt gốc (factory reset) để xóa app. Ba mẹ sẽ thấy máy “Mất liên lạc” trên web.
- Mỗi gia đình có một tài khoản ba mẹ. Muốn thêm tài khoản cho mẹ thì làm ở đợt sau.

## Dành cho người lập trình

```
web/            Web ba mẹ (HTML + JS thuần, Firebase JS SDK từ CDN)
web/policy.js   Quy tắc tính giờ (dùng chung, có kiểm thử)
android/        App Android cho máy con (Kotlin, không cần google-services.json)
firestore.rules Luật bảo mật
tests/          Kiểm thử logic tính giờ, chạy thử giao diện web
.github/        GitHub Actions: build APK + đưa web lên GitHub Pages
```

- Chạy kiểm thử: `node tests/policy.test.mjs`
- Build APK: `cd android && gradle :app:assembleRelease` (cần Android SDK, JDK 17)
- App Android khởi tạo Firebase bằng cấu hình nằm trong mã cài đặt, nên một file APK dùng được cho mọi gia đình.
- `android/app/giovang.keystore` là khóa ký cố định để các bản cập nhật cài đè lên nhau được.
- GitHub Pages miễn phí chỉ chạy với repo **Public**. Cấu hình Firebase vốn được thiết kế để công khai (dữ liệu được luật bảo mật bảo vệ), nên để repo Public vẫn an toàn cho gia đình. Có GitHub Pro thì để Private.
