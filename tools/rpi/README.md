# بث درون تجريبي من Raspberry Pi 5

يبث درون وهمي (Remote ID عبر البلوتوث) يدور فوق **واحة دبي للسيليكون**، لاختبار تطبيق رادار الدرون بدون درون حقيقي.

## التشغيل
```bash
wget -O rid_sim.py https://raw.githubusercontent.com/tarigus9-ui/drone-radar/main/tools/rpi/rid_sim.py
sudo python3 rid_sim.py
```
ثم افتح المتصفح على: `http://localhost:8080`

- **▶ تشغيل / ■ إيقاف**
- **درون طبيعي:** يدور على بعد 300 م من المشغل، ارتفاع 60 م، سرعة 10 م/ث.
- **بيانات مزيفة:** يقفز 3 كم كل 10 ثوانٍ، يدّعي 90 م/ث، والمشغل "على بعد 20 كم" — يجب أن يظهر في التطبيق ⚠️ بنفسجي.

## إن لم يظهر الدرون في التطبيق
```bash
sudo systemctl stop bluetooth
sudo python3 rid_sim.py
```
(أعد تشغيل البلوتوث بعدها: `sudo systemctl start bluetooth`)

⚠️ للاختبار فقط — مدى قصير. أوقفه بعد الانتهاء ولا تشغّله قرب المطارات.
