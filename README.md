# رحلتي · My Trip

تطبيق لشاشة جيتور: اضغط **START** في بداية الرحلة و **END** في نهايتها،
فيعطيك تقريراً مقسّماً حسب **نمط القيادة** (Sport / Eco / Normal / Others)
وحسب **الكهرباء (EV)** أو **المحرك (Engine)**.

## ما يسجله
| EV | Engine |
|---|---|
| Duration, Charge start/end, Re-charged, kWh consumed, Distance, Average speed | Duration, Fuel start/end, Refueled, Fuel consumed, Distance, Average speed |

## مصادر البيانات
| المعلومة | الإشارة |
|---|---|
| نمط القيادة | `HCU_DRIVEMODE_JT` (0=ECO، 1=NORMAL، 2=SPORT، الباقي Others) |
| كهرباء أو محرك | `ENGINESPEED` ≥ 350 rpm = محرك |
| السرعة والمسافة | `VEHICLESPEEDVSOSIG` |
| الكهرباء المستهلكة والمسترجعة | `BMS_44_PACKPOWERREALTIME` |
| البنزين المستهلك | `FUELROLLINGCOUNTER` (النبضة = 0.0000788519 لتر) |
| البطارية % | `BMS_SOCLIGHT` |
| الوقود % | VHAL `FUEL_LEVEL / INFO_FUEL_CAPACITY` |

الإشارات السريعة تُستقبل لحظياً عبر مستمع Autolink (`AlListener`)، والباقي يُقرأ كل ثانية.

## هيكل المشروع
- `autolink-stubs/` واجهة فارغة لمكتبة Autolink، للبناء فقط ولا تدخل في التطبيق.
- `CarBridge` الاتصال بالسيارة.
- `TripSession` تجميع الرحلة.
- `TripService` خدمة تعمل أثناء الرحلة فقط.
- `MainActivity` الشاشة الرئيسية.
- `ReportActivity` التقرير.

## المعايرة (أول رحلة)
من زر **⚙ تشخيص**:
- تأكد أن "المستمع اللحظي" مفعّل، وأن نبضات عداد الوقود تزيد مع عمل المحرك.
- إذا ظهرت "الكهرباء المسترجعة" تزيد أثناء القيادة على الكهرباء، اضغط **عكس اتجاه البطارية**.

## الصلاحيات (اختياري، للمسار الاحتياطي)
```
adb shell pm grant com.example.triplog android.car.permission.CAR_SPEED
adb shell pm grant com.example.triplog android.car.permission.CAR_ENERGY
adb shell pm grant com.example.triplog android.car.permission.CAR_MILEAGE
adb shell pm grant com.example.triplog android.car.permission.CAR_POWERTRAIN
```

## الملفات
كل رحلة تُحفظ في:
`/sdcard/Android/data/com.example.triplog/files/trips/trip_<وقت>.json`

## القادم
- المرحلة 2: لوحة التقرير بالتصميم الكامل.
- المرحلة 3: تصدير PDF و CSV.
