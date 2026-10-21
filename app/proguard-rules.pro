-keep class codes.dreaming.cloudmedia.provider.** { *; }
-keep class codes.dreaming.cloudmedia.network.** { *; }

-keep class okhttp3.** { *; }
-keep class okio.** { *; }

-keep class rikka.shizuku.** { *; }

# Shizuku starts the user service in its own process and creates it by class name with the no-arg
# constructor. Nothing in the app calls that constructor, so R8 would strip the class to an empty shell.
-keep class codes.dreaming.cloudmedia.util.ShellService { *; }
-keep class codes.dreaming.cloudmedia.IShellService** { *; }
