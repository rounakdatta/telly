# telly's release rules. The libraries ship their own (kotlinx.serialization, OkHttp, Coil, Compose); these are the
# belt and braces for the parts of telly that are found by name rather than by reference.

# The server's JSON models: every type is decoded with its serializer named outright (Api.kt), but keep them whole so
# no field the server sends can be stripped.
-keep class club.taptappers.telly.data.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# Coil finds its network fetcher and decoders through ServiceLoader.
-keep class * implements coil3.util.FetcherServiceLoaderTarget { *; }
-keep class * implements coil3.util.DecoderServiceLoaderTarget { *; }
-dontwarn coil3.**

# OkHttp's optional platform integrations aren't on Android.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
