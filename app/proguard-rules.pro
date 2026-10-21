# kotlinx.serialization ships its own R8 rules; keep our DTO companions just in case.
-keepclassmembers @kotlinx.serialization.Serializable class app.musikbox.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn org.slf4j.**
