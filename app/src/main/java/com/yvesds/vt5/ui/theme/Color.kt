package com.yvesds.vt5.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * VT5 kleurdefinities (donker-thema-only).
 *
 * Opmerking:
 * - VT_OnPrimary staat op WIT zodat tekst/icoon op primaire (gevulde) knoppen sowieso wit is.
 * - In de componenten zelf forceren we contentColor = Color.White, dus de knoppen zijn
 *   altijd wit qua tekst — ongeacht de huidige KnopStijl.
 */

/* =========================
 * Basisschema (donker)
 * ========================= */

/** Primaire merk-/actie-kleur (container van gevulde knoppen) */
val VT_Primary = Color(0xFF80D97A)

/** Tekstkleur/icoonkleur bovenop VT_Primary (wit, conform je wens) */
val VT_OnPrimary = Color(0xFFFFFFFF)

/** Donkerdere variant van VT_Primary voor pressed-state (aantik-feedback). */
val VT_PrimaryPressed = Color(0xFF6CC667)

/** Algemene UI-achtergrond (donker) */
val VT_Surface = Color(0xFF121212)

/** Standaard tekst/icoon boven VT_Surface */
val VT_OnSurface = Color(0xFFE6E6E6)

/** Standaard rand-/omlijningkleur */
val VT_Outline = Color(0xFF3DDC84)


/* ==========================================================
 * “Magische” lichtblauwe knopstijl (globaal schakelbaar)
 * ========================================================== */

/** Accentkleur voor omlijning én volledige vulling tijdens pressed-state. */
val VT_LightBlue = Color(0xFF117CAF)

/** Contrasterende tekstkleur bovenop VT_LightBlue (we forceren sowieso wit in knoppen). */
val VT_LightBlue_On = Color(0xFFFFFFFF)

/** Achtergrondkleur voor uitgeschakelde componenten. */
val VT_DisabledBg = Color(0xFF2A2A2A)

/** Tekstkleur voor uitgeschakelde componenten. */
val VT_DisabledFg = Color(0xFF7A7A7A)

/** Subtiele overlay (10%) voor eventuele feedback boven Surface. */
val VT_PressedOverlay = Color(0x1AFFFFFF)
