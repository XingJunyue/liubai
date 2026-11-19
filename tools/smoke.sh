#!/usr/bin/env bash
# 模拟器冒烟测试：装上、每个页面都打开截图、识别一张课表、确认没有闪退。
set -u
P=com.liubai.guard
mkdir -p shots
fail=0
anr() { adb shell dumpsys window 2>/dev/null | grep -qi "not responding"; }
shot() {
  sleep "${2:-3}"
  # 系统的「xx 没有响应」对话框：关掉再截
  if anr; then echo "ANR dialog before $1, closing"; adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1; sleep 2; fi
  adb exec-out screencap -p > "shots/$1.png"
}
go() { adb shell am start -n $P/.MainActivity --es open "$@" > /dev/null; }
crashed() { adb logcat -d -b crash | grep -q "$P" ; }

# 等系统完全起来，关掉错误对话框（模拟器慢，桌面常报「没有响应」盖住截图）
adb wait-for-device
for i in $(seq 1 90); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ] && break; sleep 2; done
adb shell settings put global hide_error_dialogs 1
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
sleep 20
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1
adb shell input keyevent KEYCODE_WAKEUP

adb logcat -c
adb install -r -g out/test-debug.apk || { echo "INSTALL FAILED"; exit 1; }
adb shell appops set $P GET_USAGE_STATS allow
adb shell am start -n $P/.MainActivity --ez seed true; sleep 6
# 中文截图要用中文界面（模拟器系统语言是英文）
adb shell am start -n $P/.MainActivity --es setlang zh; sleep 8
adb shell am start -n $P/.MainActivity; shot 01-home 6

for s in focus time:0 time:1 time:2 weekly groups widget look settings about guide; do
  adb shell am start -n $P/.MainActivity --es open "$s"
  shot "02-${s/:/-}" 4
  case "$s" in focus|time:*|weekly|settings) adb shell input swipe 540 1900 540 500 400; shot "02-${s/:/-}-b" 2;; esac
done

# 课表整页：本周、滑到下周、点课块、加课、改课、学期设置、开学日期、每节时间、导入菜单、教务列表、网页导入
go table; shot 07-table 6
adb shell input swipe 900 1500 150 1500 250; shot 07-table-next 3
adb shell input swipe 150 1500 900 1500 250; sleep 2
adb shell input tap 175 500; shot 07-tap-course 3
adb shell input keyevent KEYCODE_BACK; sleep 1
go table:3; shot 07-add 3
go table:4; shot 07-edit 3
adb shell input swipe 540 1900 540 700 400; shot 07-edit-b 2
go table:2; shot 07-term 3
go table:5; shot 07-term-date 3
go table:7; shot 07-secs 3
go table:8; shot 07-import-menu 3
go table:1; shot 07-import-list 3
go table:0; sleep 1
adb shell am start -n $P/.MainActivity --es open table:6 --es jwkind qz --es jwurl file:///android_asset/jwtest.html > /dev/null; shot 07-web 8
go table:0; sleep 1
adb shell am start -n $P/.MainActivity --es open table:6 --es jwkind qz --es jwurl file:///android_asset/jwtest.html --ez jwauto true > /dev/null; shot 07-web-preview 10
adb logcat -d | grep -i "chromium\|LiubaiJw" | tail -20 > shots/web-log.txt
go table:0; sleep 1
adb shell am start -n $P/.MainActivity --es open focus; sleep 3
adb shell input tap 960 190; shot 02-focus-set 3
adb shell input keyevent KEYCODE_BACK; sleep 1

# 停一下页（带「问一句」）、拦截页（每日用完 / 上课 / 打开次数）
adb shell am start -f 0x10008000 -n $P/.PauseActivity --es pkg com.android.chrome --ei sec 5 --ez ask true; shot 03-pause-ask
adb shell input keyevent KEYCODE_BACK
adb shell am start -f 0x10008000 -n $P/.PauseActivity --es pkg com.android.chrome --ei sec 5 --ei again 10; shot 03-pause-again
adb shell input keyevent KEYCODE_BACK
for w in daily class opens; do
  adb shell am start -f 0x10008000 -n $P/.BlockActivity --es pkg com.android.chrome --es why $w; shot "04-block-$w"
  adb shell input keyevent KEYCODE_HOME
done

# 课表截图识别：几种语言、几种排版
adb shell mkdir -p /sdcard/Android/data/$P/files
ocr() {  # ocr 变体 期望行...
  local v=$1; shift
  adb push out/tt-$v.png /sdcard/Android/data/$P/files/t-$v.png > /dev/null
  adb logcat -b main -c
  adb shell am start -n $P/.MainActivity --es ocr t-$v.png
  for i in $(seq 1 45); do sleep 2; adb logcat -d -s LiubaiTest | grep -q "OCR \(OK\|FAIL\)" && break; done
  adb logcat -d -s LiubaiTest > shots/ocr-$v.txt
  grep "OCR \(OK\|FAIL\|ROW\)" shots/ocr-$v.txt | sed "s/^/[$v] /"
  grep -q "OCR OK" shots/ocr-$v.txt || { echo "OCR did not succeed: $v"; fail=1; }
  for want in "$@"; do grep -q "OCR ROW $want" shots/ocr-$v.txt || { echo "OCR missing [$v]: $want"; fail=1; }; done
}
ocr zh "1|高等数学|480|580" "1|大学物理|840|940" "2|大学英语|600|700" "3|线性代数|480|580" "4|程序设计|960|1060" "5|形势与政策|1140"
ocr zh_dense "1|概率统计|480|580" "2|数据结构|600|700" "3|马克思主义原理|840|940" "6|日语|480|580" "7|体育|600|700"
ocr en "1|Linear Algebra|480|580" "2|Physics|600|700" "4|History|840|940"
ocr de "1|Mathematik|480|580" "3|Chemie|600|700" "5|Englisch|840|940"
ocr ja "1|線形代数|480|580" "2|英語|600|700" "4|物理学|840|940"
ocr ko "1|자료구조|480|580" "3|영어회화|600|700" "5|물리학|840|940"

# 其他语言下的主要页面
for lang in en de ja; do
  adb shell am start -n $P/.MainActivity --es setlang $lang; sleep 5
  adb shell am start -n $P/.MainActivity; shot "06-$lang-home" 5
  for s in focus time:0 table settings groups weekly; do adb shell am start -n $P/.MainActivity --es open "$s"; shot "06-$lang-${s/:/-}" 3; done
  adb shell am start -f 0x10008000 -n $P/.PauseActivity --es pkg com.android.chrome --ei sec 5 --ez ask true; shot "06-$lang-pause"
  adb shell input keyevent KEYCODE_BACK
  adb shell am start -f 0x10008000 -n $P/.BlockActivity --es pkg com.android.chrome --es why daily; shot "06-$lang-block"
  adb shell input keyevent KEYCODE_HOME
done
adb shell am start -n $P/.MainActivity --es setlang zh; sleep 4

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
