# Tesseract4Android — нативный движок через JNI, имена методов менять нельзя
-keep class cz.adaptech.tesseract4android.** { *; }
# Приложение работает с классами из исходного пакета tess-two: нативная
# библиотека ищет их по точным именам — переименование ломает RegisterNatives
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class ru.danil.medtracker.**$$serializer { *; }
-keepclassmembers class ru.danil.medtracker.** { *** Companion; }
-keepclasseswithmembers class ru.danil.medtracker.** { kotlinx.serialization.KSerializer serializer(...); }
