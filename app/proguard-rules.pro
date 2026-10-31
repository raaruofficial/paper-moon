# PAPER MOON release ProGuard rules.
#
# v0.2.10 RE-DERIVATION (Gate 0 C-28) — every keep below carries
# its reason; nothing is inherited on faith from the ktx era:
#
# 1. The JS bridge: @JavascriptInterface methods are invoked BY
#    NAME from the WebView runtime. R8 renaming them breaks every
#    bridge call. Load-bearing, unchanged since v0.2.0.
-keepclassmembers class com.hindsight.app.wallet.PaperMoonWalletBridge {
    public *;
}
#
# 2. The Connect error-code enum names: the gateway's sealed
#    mapping persists Code.name into the C-21 purchase journal
#    (walletErrorKind) and the journal is evidence — the names
#    must be the SDK's canonical names in every build, not
#    whatever an R8 pass chooses. One enum, narrowly kept.
-keepclassmembers enum com.solanamobile.seekerconnect.SeekerConnectException$Code {
    *;
}
#
# REMOVED at v0.2.10: the wholesale clientlib keep
# (-keep class com.solana.mobilewalletadapter.clientlib.**).
# Re-derivation against the resolved 2.1.1 layout: clientlib's
# own AAR ships an EMPTY consumer-rules file (the vendor's
# position is that no keeps are needed); the seeker-connect AARs
# ship none at all (proof census, Gate 0 §2.3); neither SDK's
# sources use reflection (Class.forName census = 0); and no class
# name crosses the wallet process boundary — the association URI
# and the JSON-RPC method strings are literals. The proof for
# this removal is empirical and filed: the full runtime battery
# (connect / sign / send / disconnect / restore) runs against
# THIS R8-processed release candidate (C-28's restore cell +
# the §7.3 cells), not against a debug build.
#
# Tink (money-build crypto) references error-prone / JSR-305 annotations
# that are not on the release classpath; R8 must not fail on them.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**
