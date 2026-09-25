# JGit & SSHJ reflection/service loaders
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.codespace.ide.**$$serializer { *; }
-keepclassmembers class com.codespace.ide.** { *** Companion; }
-keepclasseswithmembers class com.codespace.ide.** { kotlinx.serialization.KSerializer serializer(...); }
