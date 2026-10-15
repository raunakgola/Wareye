# LiteRT: native code looks up these classes and members by name.
# The AI-pack download path (deployment/, ModelProvider, ModelSelector) is never used, so it is not kept.
-keep class !com.google.ai.edge.litert.deployment.**,!com.google.ai.edge.litert.ModelProvider*,!com.google.ai.edge.litert.ModelSelector*,com.google.ai.edge.litert.** { *; }
-keep class org.tensorflow.lite.** { *; }
# Dependencies excluded in build.gradle.kts; only referenced by the unused AI-pack path.
-dontwarn com.google.android.play.**
-dontwarn com.google.android.gms.**
-dontwarn com.google.common.**
-dontwarn kotlinx.coroutines.guava.**
