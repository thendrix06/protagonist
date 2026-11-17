# kotlinx.serialization: keep generated serializers for wire DTOs.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class capture.protocol.** { *** Companion; }
-keepclasseswithmembers class capture.protocol.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class capture.protocol.**$$serializer { *; }
