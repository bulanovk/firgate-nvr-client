# Gson reads the saved home networks by their field names (DefaultServerStore). R8 renames the fields
# of a release build, and the SSID then comes back null: keep the class as it is.
-keep class com.frigate.android.core.server.HomeNetwork { *; }
