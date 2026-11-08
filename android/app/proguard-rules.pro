# JNI whisper.cpp: nama metode native harus tetap
-keepclasseswithmembernames class id.ac.uika.everest.whisper.WhisperLib { native <methods>; }
-keep class id.ac.uika.everest.whisper.WhisperLib { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class id.ac.uika.everest.**$$serializer { *; }

# Retrofit (interface dengan anotasi + suspend)
-keep,allowobfuscation interface id.ac.uika.everest.data.remote.EverestApi
-keepattributes Signature, Exceptions
-keep class kotlin.coroutines.Continuation

# SQLCipher
-keep class net.zetetic.database.** { *; }
