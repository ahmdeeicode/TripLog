# سجل الرحلات (TripLog)

تطبيق صغير جداً لشاشة جيتور، يسجّل كل رحلة تلقائياً في ملف CSV.
بدون أي مكتبات خارجية، ويصل لبيانات السيارة بالـ reflection بنفس نهج Unlokit.

## مصادر البيانات
| المعلومة | Autolink (أولاً) | VHAL القياسي (احتياطي) |
|---|---|---|
| السرعة | `getVEHICLESPEEDVSOSIG` | `PERF_VEHICLE_SPEED` |
| القير | `getVCU_1_G_PRNDGEARACT` (1=P 2=R 3=N 4=D) | `GEAR_SELECTION` |
| العداد | `getFLZCU_TOTALODOMETERBACKUP` | `PERF_ODOMETER` |
| البطارية % | `getBMS_SOCLIGHT` | `EV_BATTERY_LEVEL / INFO_EV_BATTERY_CAPACITY` |
| الوقود % | — | `FUEL_LEVEL / INFO_FUEL_CAPACITY` |
| المدى | `getVCU_WLTC_RANGEAVAL` | — |
| الحرارة الخارجية | `getEXTERNALTEMPERATURE_C` | `ENV_OUTSIDE_TEMPERATURE` |
| قدرة البطارية | `getBMS_44_PACKPOWERREALTIME` | — |

## منطق الرحلة
- تبدأ عند تجاوز السرعة 3 كم/س.
- تنتهي عند: البقاء على **P لمدة دقيقة**، أو **3 دقائق بدون حركة**، أو **انقطاع البيانات دقيقتين** (إطفاء السيارة).
- تُهمل الرحلات الأقصر من دقيقة أو 200 متر.
- الرحلة الجارية تُحفظ كل 30 ثانية، فإذا أُغلقت الخدمة تُستأنف بعد عودتها.
- القيم في `TripRecorder.java` أعلى الملف، وتقدر تعدّلها.

## البناء
**الطريقة الأولى (بدون Android Studio):** ارفع المجلد على مستودع GitHub، فيبني
GitHub Actions الملف تلقائياً. حمّل `app-debug.apk` من تبويب Actions ← Artifacts.

**الطريقة الثانية:** افتح المجلد في Android Studio، ثم Build ← Build APK.

## التثبيت والصلاحيات
```
adb install -r app-debug.apk
adb shell pm grant com.example.triplog android.car.permission.CAR_SPEED
adb shell pm grant com.example.triplog android.car.permission.CAR_EXTERIOR_ENVIRONMENT
adb shell dumpsys deviceidle whitelist +com.example.triplog
```
- مسار Autolink لا يحتاج صلاحيات غالباً، وهو المصدر الرئيسي.
- `CAR_ENERGY` و`CAR_MILEAGE` صلاحيات نظام لا تُمنح لتطبيق عادي، وتُستخدم فقط في المسار الاحتياطي.
- الأمر الأخير يمنع النظام من قتل الخدمة في الخلفية.

## الملف الناتج
```
/sdcard/Android/data/com.example.triplog/files/trips.csv
adb pull /sdcard/Android/data/com.example.triplog/files/trips.csv
```
| العمود | المعنى |
|---|---|
| distance_km | المسافة من تكامل السرعة (دقيقة حتى للرحلات القصيرة) |
| odo_distance_km | فرق العداد (للمقارنة) |
| avg_kmh | متوسط السرعة أثناء الحركة فقط |
| pack_pos_kwh / pack_neg_kwh | تكامل قدرة البطارية في الاتجاهين. أي اتجاه هو الصرف وأيهما الاسترجاع يختلف حسب الطراز؛ تأكد منه بمقارنة رحلة واحدة |

## للتشخيص
```
adb logcat -s TripCar TripService
```
يظهر أي مصدر اتصل (Autolink أو VHAL)، وكل رحلة محفوظة.
