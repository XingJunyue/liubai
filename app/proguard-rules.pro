-keep class io.github.muntashirakon.** { *; }
-keep class android.sun.** { *; }
-keep class org.conscrypt.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn io.github.muntashirakon.**
-dontwarn android.sun.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn com.android.org.conscrypt.**
-dontwarn org.apache.harmony.**
-dontwarn sun.**
-dontwarn javax.naming.**

# 课表网页导入：网页里的 JS 调这些方法
-keepclassmembers class com.liubai.guard.** { @android.webkit.JavascriptInterface <methods>; }
-dontwarn org.jspecify.**
-dontwarn com.google.re2j.**
