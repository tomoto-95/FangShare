# NanoHTTPd
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.lanshare.app.model.** { *; }
-keep class com.google.gson.** { *; }
