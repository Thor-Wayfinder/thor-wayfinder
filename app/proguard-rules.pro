# RootMover / RecentsTool are loaded by name via `app_process`, so R8 must not
# rename or strip them (or their main) even if minification is enabled later.
-keep class app.wayfinder.RootMover { public static void main(java.lang.String[]); }
-keep class app.wayfinder.RecentsTool { public static void main(java.lang.String[]); }
-keep class app.wayfinder.DisplayPowerTool { public static void main(java.lang.String[]); }
-keep class app.wayfinder.InputMonitorTool { public static void main(java.lang.String[]); }
-keep class app.wayfinder.FocusTool { public static void main(java.lang.String[]); }
-keep class app.wayfinder.BrightnessTool { public static void main(java.lang.String[]); }

# Shrink the libraries only: ALL of Wayfinder's own code stays as written — tools started by name
# through app_process, enum and class names stored in settings / JSON, reflection, debug-build test
# hooks. No renaming at all (stack traces stay readable, stored names stay valid).
-keep class app.wayfinder.** { *; }
-dontobfuscate
