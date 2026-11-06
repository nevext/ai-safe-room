# XM / JFTech FunSDK: the native library calls back into these classes by name (JNI), and reads its structs by
# reflection. Shrinking or renaming them breaks the cloud login and the video.
-keep class com.lib.** { *; }
-keep class com.manager.** { *; }
-keep class com.basic.** { *; }
-keep class com.xm.** { *; }
-keep class com.xmgl.** { *; }
-keep class com.vatics.** { *; }
-keep class com.utils.** { *; }
-dontwarn com.lib.**
-dontwarn com.manager.**
-dontwarn com.xm.**
-dontwarn com.basic.**
-dontwarn com.utils.**
-dontwarn kotlinx.android.extensions.**
