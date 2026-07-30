# R8 rules for the wear release build.
#
# Everything this module depends on (Compose, wear-compose, play-services-
# wearable, DataStore) ships its own consumer rules; the one thing R8 can't
# infer is kotlinx.serialization's reflective $serializer lookup for :domain's
# wire DTOs (WatchSnapshot/SetEditDelta — the phone<->watch sync format).
# Same recipe as app/proguard-rules.pro, which carries the full explanation.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$Companion Companion;
}
-keepclassmembers class <1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

-keepattributes RuntimeVisibleAnnotations, AnnotationDefault
-keepattributes InnerClasses

-if @kotlinx.serialization.Serializable class **
-keepclassmembers,allowoptimization class ** {
    <fields>;
}
