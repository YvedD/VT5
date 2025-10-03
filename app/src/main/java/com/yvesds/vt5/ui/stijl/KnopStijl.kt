package com.yvesds.vt5.ui.stijl

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Globale schakelaar voor knopstijlen.
 * Zet éénmaal rond je app of rond een specifieke scherm-flow.
 */
enum class KnopStijl {
    Standaard,          // je bestaande groene variant
    LichtblauwOmlijnd   // NIEUW: lichtblauwe omranding + pressed=lichtblauw invul
}

val LocalKnopStijl = staticCompositionLocalOf { KnopStijl.Standaard }

@Composable
fun KnopStijlProvider(stijl: KnopStijl, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalKnopStijl provides stijl) { content() }
}
