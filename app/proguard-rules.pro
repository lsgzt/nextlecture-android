-keep class com.gndec.timetable.data.db.** { *; }
-dontwarn org.jsoup.**

# Compile-time annotations referenced by bundled crypto libraries; not needed at runtime.
-dontwarn com.google.errorprone.annotations.**

# PDFBox optionally references a JPEG2000 decoder that is not bundled or used by the GNDEC PDFs.
-dontwarn com.gemalto.jp2.**
