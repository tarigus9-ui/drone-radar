# رادار الدرون — DroneRadar (Android)

يلتقط بث **Remote ID / Open Drone ID** (معيار ASTM F3411 و EN 4709-002) من أي شركة تلتزم به:
DJI، Skydio، Autel، Parrot، ووحدات Remote ID الخارجية.

## القنوات المدعومة
| القناة | الحالة |
|---|---|
| Bluetooth 4 Legacy | ✅ |
| Bluetooth 5 Long Range | ✅ إذا كان الهاتف يدعم Coded PHY |
| Wi-Fi Beacon (تستخدمه DJI) | ✅ Android 11+ |
| Wi-Fi NaN (Aware) | ✅ إذا كان الهاتف يدعمه |

## البناء
1. افتح المجلد في **Android Studio** (Ladybug أو أحدث).
2. انتظر مزامنة Gradle ثم Run على هاتف حقيقي (المحاكي لا يملك بلوتوث/واي فاي حقيقي).

## العمل والتطبيق مغلق
- خدمة أمامية `ScanService` بنوع location تعمل باستمرار (START_STICKY).
- مسح بلوتوث عبر `PendingIntent` يديره النظام نفسه، فيوقظ `BleHitReceiver` حتى لو قُتلت عملية التطبيق.
- `BootReceiver` يعيد التشغيل بعد إعادة تشغيل الهاتف.
- عند أول تشغيل: امنح الموقع "السماح طوال الوقت" واستثنِ التطبيق من تحسين البطارية.
- هواتف Xiaomi/Huawei/Oppo: فعّل "التشغيل التلقائي" يدوياً من الإعدادات.

## نصائح
- مسح الواي فاي محدود من أندرويد (~4 مرات كل دقيقتين). لتحديث أسرع لدرونات DJI:
  خيارات المطوّر ← أوقف "Wi-Fi scan throttling".

## الخريطة
OpenStreetMap عبر osmdroid (بدون مفتاح API).
🔴 الدرون • 🟢 المشغل • خط برتقالي يربط بينهما. اضغط على أي نقطة للتفاصيل.

## الملفات
- `OdidParser.kt` — فك رسائل ODID (BasicID, Location, SelfID, System, OperatorID, MessagePack)
- `ScanService.kt` — المسح على البلوتوث + Wi-Fi Beacon + NaN
- `Alerts.kt` — الإشعارات والتنبيه بصوت المنبه
- `MainActivity.kt` — الخريطة والأذونات
