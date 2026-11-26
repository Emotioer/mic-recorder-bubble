# 🫧 مسجل الصوت العائم | Mic Recorder Bubble

### 🎙️ سجّل من أي شاشة بضغطة واحدة، من غير ما تفتح التطبيق!
### 🎙️ Record from any screen with one tap, without opening the app!

مسجل صوت لأندرويد 7 أو أحدث بفقاعة عائمة فوق كل التطبيقات، زي الـ Chat Heads بتاعة ماسنجر. اضغط الفقاعة، سجّل، وابعت التسجيل. ومعمول كمان للموبايلات اللي المايك الرئيسي فيها بايظ.

An Android 7+ voice recorder with a floating bubble that stays on top of every app, like Messenger chat heads. Tap it, record, share. It is also built for phones with a faulty main microphone.

## 📥 التحميل | Download

🚀 **جاهز للتثبيت؟ حمّل الـAPK وابدأ! | Ready to install? Get the APK and start!**

[⬇️ حمّل ملف APK | Download the APK](https://github.com/Emotioer/mic-recorder-bubble/releases/latest)

## 🆕 الجديد: الفقاعة العائمة | New: the floating bubble

- 🫧 **فقاعة فوق كل التطبيقات** بتفضل ظاهرة وإنت في فيسبوك أو واتساب أو أي تطبيق | **A bubble over all apps** that stays visible in Facebook, WhatsApp or anything else
- 👆 **دوس عليها تتفتح فقاعتين** | **Tap it and it opens two bubbles**
  - 🔴 **سجّل / وقّف** | **Record / stop**
  - 📤 **شارك** التسجيل على طول | **Share** the recording right away
- ⏱️ **عداد وقت** على الفقاعة وهي بتسجل، وبتتحول للأحمر | **A live timer** on the bubble, turning red while recording
- ✋ **اسحبها** في أي مكان، وبتلزق في حافة الشاشة | **Drag it** anywhere, it snaps to the screen edge
- 🔔 **بتسجّل والشاشة على تطبيق تاني** بفضل خدمة في المقدمة مع إشعار | **Keeps recording while you use other apps**, thanks to a foreground service with a notification
- 🚀 **أداء سلس** على الموبايلات الضعيفة | **Smooth performance** on low-end phones

## 🌟 المميزات | Features

- 🎙️ اختيار تلقائي لقناة المايك الشغالة | Auto-picks the working microphone channel
- 🎧 تسجيل ستيريو 48 كيلوهرتز وحفظ بصيغة AAC داخل ملف `.m4a` صغير | Stereo 48 kHz capture, saved as small AAC `.m4a` files
- 🧹 تنقية ضوضاء الخلفية أثناء التسجيل | Live adaptive noise gate
- ⚡ حفظ فوري، الترميز بيحصل أثناء التسجيل | Instant save, encoding happens while you record
- 📊 شكل موجي متحرك | Live waveform
- ▶️ سماع التسجيل من جوه التطبيق | Built-in playback
- 📤 مشاركة على واتساب أو أي تطبيق | Share to WhatsApp or any app
- 🗑️ مسح بضغطة واحدة | One-tap delete
- 🖤 ثيم غامق وواجهة بالعربي | Dark theme, Arabic interface
- 🔒 إذن المايك بس، بدون إعلانات أو تتبع | Mic permission only, no ads, no tracking

## 📱 طريقة الاستخدام | Usage

1. 📲 ثبّت الـAPK وافتح التطبيق | Install the APK and open the app
2. 🫧 دوس «تشغيل الفقاعة العائمة» | Tap "تشغيل الفقاعة العائمة" (turn on the floating bubble)
3. ✅ فعّل إذن «العرض فوق التطبيقات الأخرى» من الإعدادات وارجع | Allow "Display over other apps" in Android settings, then come back
4. 🎙️ دوس الفقاعة، بعدها 🔴 للتسجيل، وللإيقاف دوس ■ | Tap the bubble, then 🔴 to record, ■ to stop
5. 📤 دوس 📤 عشان تبعت التسجيل | Tap 📤 to share the recording

## ⚙️ إزاي شغال | How it works

- `Recorder`: `AudioRecord` من مصدر `CAMCORDER` بستيريو 48 كيلوهرتز، بيختار القناة الأعلى طاقة، وبيشغل بوابة ضوضاء حية، وبيرمّز لـAAC بـ `MediaCodec` و`MediaMuxer` أثناء التسجيل | `AudioRecord` from the `CAMCORDER` source, picks the louder channel, runs a live noise gate, and encodes to AAC with `MediaCodec` and `MediaMuxer` as audio arrives
- `BubbleService`: خدمة في المقدمة بتعرض الفقاعة عبر `WindowManager` و`SYSTEM_ALERT_WINDOW` | a foreground service that shows the bubble through `WindowManager` and `SYSTEM_ALERT_WINDOW`
- المشاركة عبر `ContentProvider` داخلي | Sharing goes through an internal `ContentProvider`

## 🛠️ البناء | Build

No Android Studio needed. `build.sh` drives `aapt2`, `javac`, `d8` and `apksigner`. You need JDK 11+, Android build-tools 30.0.3 and the android-28 platform:

```bash
export BT=/path/to/build-tools/30.0.3
export PLATFORM=/path/to/platforms/android-28/android.jar
export KEYSTORE=/path/to/keystore.jks KS_PASS=yourpassword
./build.sh
```

## ✅ المتطلبات | Requirements

- 📱 Android 7.0 (API 24) or newer
- 🎤 Microphone permission
- 🫧 "Display over other apps" permission for the bubble

## 📜 الرخصة | License

MIT © Ahmed Esmat

---

🕹️ الإصدار الكلاسيك بدون فقاعة | Classic version without the bubble: [mic-recorder](https://github.com/Emotioer/mic-recorder)
