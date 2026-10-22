# PAPER MOON release ProGuard rules.
# Keep the MWA clientlib and the JS bridge (called by name from the WebView).
-keep class com.solana.mobilewalletadapter.clientlib.** { *; }
-keepclassmembers class com.hindsight.app.wallet.PaperMoonWalletBridge {
    public *;
}

# Tink (money-build crypto) references error-prone / JSR-305 annotations
# that are not on the release classpath; R8 must not fail on them.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**
