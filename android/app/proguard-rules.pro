# NearDue v0.1 - project specific R8 / ProGuard rules.
# AGP already appends its default optimized rules and the Compose consumer rules.

# Keep the single Activity and the widget receiver that are referenced from AndroidManifest.xml.
-keep class com.neardue.app.MainActivity { *; }
-keep class com.neardue.app.NearDueWidget { *; }
-keep class com.neardue.app.ReminderReceiver { *; }
-keep class com.neardue.app.BootReceiver { *; }

# Keep source file and line info for readable stack traces.
-keepattributes SourceFile,LineNumberTable
