# R8 rules for the release build (M6 #23 / PLAN.md A9).
#
# Room, Hilt, Jetpack Compose, DataStore, and the Health Connect client all
# ship their own consumer-rules.pro inside their AARs — R8 picks those up
# automatically from the dependency graph, so this file does not duplicate
# them. What's left is the one thing R8 can't infer on its own: which of
# *our* classes kotlinx.serialization reflects into at runtime.
#
# kotlinx.serialization generates a $serializer companion for every
# @Serializable class, but nothing in the bytecode calls it directly — the
# runtime looks it up by name the first time Json.encode/decode sees that
# class. Without a keep rule R8 treats the whole thing as dead code and
# strips it, which breaks JSON backup export/import (:transfer's
# BackupDocument tree), Room's TypeConverters for cardio/set JSON columns
# (:data's CardioSerialization/SetSerialization), and the watch sync wire
# format (:domain's SetEditDelta/WatchSnapshot) — every reflection-sensitive
# path this module ships. This is the recipe kotlinx.serialization's own
# docs recommend for R8 (github.com/Kotlin/kotlinx.serialization, ProGuard
# rules section); it matches on the @Serializable annotation itself so it
# covers all four modules' DTOs without listing them by name.
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

# @Serializable/@Polymorphic themselves are read at runtime to pick the
# serializer, and InnerClasses is needed for the $Companion lookups above
# (getDeclaredClasses).
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault
-keepattributes InnerClasses

# Keep the serialized fields of every @Serializable class by name (the JSON
# key IS the field name, SSOT with BackupDocument/CardioSerialization/etc.)
# but still let R8 drop/inline anything it can prove is otherwise unused.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers,allowoptimization class ** {
    <fields>;
}
