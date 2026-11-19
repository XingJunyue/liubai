#!/usr/bin/env bash
# 模拟器冒烟测试：装上、每个页面都打开截图、识别一张课表、确认没有闪退。
set -u
P=com.liubai.guard
mkdir -p shots
fail=0
shot() { sleep "${2:-3}"; adb exec-out screencap -p > "shots/$1.png"; }
crashed() { adb logcat -d -b crash | grep -q "$P" ; }

adb logcat -c
adb install -r -g out/test-debug.apk || { echo "INSTALL FAILED"; exit 1; }
adb shell appops set $P GET_USAGE_STATS allow
adb shell am start -n $P/.MainActivity --ez seed true; shot 01-home 6

for s in focus time:0 time:1 time:2 weekly groups widget look settings about guide; do
  adb shell am start -n $P/.MainActivity --es open "$s"
  shot "02-${s/:/-}" 4
done

# 停一下页（带「问一句」）、拦截页（每日用完 / 上课 / 打开次数）
adb shell am start -n $P/.PauseActivity --es pkg com.android.chrome --ei sec 5 --ez ask true; shot 03-pause-ask
adb shell input keyevent KEYCODE_BACK
adb shell am start -n $P/.PauseActivity --es pkg com.android.chrome --ei sec 5 --ei again 10; shot 03-pause-again
adb shell input keyevent KEYCODE_BACK
for w in daily class opens; do
  adb shell am start -n $P/.BlockActivity --es pkg com.android.chrome --es why $w; shot "04-block-$w"
  adb shell input keyevent KEYCODE_HOME
done

# 课表截图识别
adb shell mkdir -p /sdcard/Android/data/$P/files
adb push out/timetable.png /sdcard/Android/data/$P/files/t.png
adb shell am start -n $P/.MainActivity --es ocr t.png
for i in $(seq 1 40); do sleep 2; adb logcat -d -s LiubaiTest | grep -q "OCR \(OK\|FAIL\)" && break; done
adb logcat -d -s LiubaiTest | tee shots/ocr.txt
grep -q "OCR OK" shots/ocr.txt || { echo "OCR did not succeed"; fail=1; }
for want in "1|高等数学|480|580" "1|大学物理|840|940" "2|大学英语|600|700" "3|线性代数|480|580" "4|程序设计|960|1060" "5|形势与政策|1140"; do
  grep -q "OCR ROW $want" shots/ocr.txt || { echo "OCR missing: $want"; fail=1; }
done

if crashed; then echo "CRASH (debug)"; adb logcat -d -b crash | tee shots/crash-debug.txt; fail=1; fi

# 正式版（混淆后）能不能正常启动
adb uninstall $P
adb logcat -c
adb install -r -g out/test-release.apk || { echo "INSTALL RELEASE FAILED"; exit 1; }
adb shell am start -n $P/.MainActivity; shot 05-release-start 6
adb shell am start -n $P/.MainActivity --es open focus; shot 05-release-again 3
if crashed; then echo "CRASH (release)"; adb logcat -d -b crash | tee shots/crash-release.txt; fail=1; fi

adb logcat -d > shots/logcat.txt
echo "smoke fail=$fail"
exit $fail
