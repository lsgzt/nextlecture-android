-keep class com.gndec.timetable.data.db.** { *; }
-dontwarn org.jsoup.**

# Compile-time annotations referenced by bundled crypto libraries; not needed at runtime.
-dontwarn com.google.errorprone.annotations.**
