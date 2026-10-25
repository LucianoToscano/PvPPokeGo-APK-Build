# Distribuicao isolada para amigos; a edicao normal permanece sem R8.
# Componentes referenciados pelo Android Manifest devem sobreviver ao shrinking.
-keep class com.lucianotoscano.pvppokego.PvPPokeGoApp { *; }
-keep class com.lucianotoscano.pvppokego.MainActivity { *; }
-keep class com.lucianotoscano.pvppokego.overlay.BattleOverlayService { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keepclassmembers class * {
  @android.webkit.JavascriptInterface <methods>;
}
