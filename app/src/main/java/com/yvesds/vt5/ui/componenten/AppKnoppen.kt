package com.yvesds.vt5.ui.componenten

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yvesds.vt5.ui.stijl.KnopStijl
import com.yvesds.vt5.ui.stijl.LocalKnopStijl
import com.yvesds.vt5.ui.theme.*

/**
 * AppPrimaireKnop / AppOutlinedKnop volgen automatisch de globale KnopStijl,
 * maar de TEKSTKLEUR is altijd WIT (enabled) of grijs (disabled).
 */

@Composable
fun AppPrimaireKnop(
    tekst: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    when (LocalKnopStijl.current) {
        KnopStijl.Standaard -> PrimaireGroeneKnop(tekst, modifier, enabled, onClick)
        KnopStijl.LichtblauwOmlijnd -> PrimaireLichtblauweOutlineKnop(tekst, modifier, enabled, onClick)
    }
}

@Composable
fun AppOutlinedKnop(
    tekst: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    when (LocalKnopStijl.current) {
        KnopStijl.Standaard -> OutlinedGroeneKnop(tekst, modifier, enabled, onClick)
        KnopStijl.LichtblauwOmlijnd -> PrimaireLichtblauweOutlineKnop(tekst, modifier, enabled, onClick)
    }
}

/* ------------------- Implementaties ------------------- */

@Composable
private fun PrimaireGroeneKnop(
    tekst: String,
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed = interaction.collectIsPressedAsState().value

    val container =
        if (enabled) (if (pressed) VT_PrimaryPressed else VT_Primary) else VT_DisabledBg

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        interactionSource = interaction,
        shape = MaterialTheme.shapes.large,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = if (enabled) Color.White else VT_DisabledFg,
            disabledContainerColor = VT_DisabledBg,
            disabledContentColor = VT_DisabledFg
        ),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp)
    ) {
        Text(tekst)
    }
}

@Composable
private fun OutlinedGroeneKnop(
    tekst: String,
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed = interaction.collectIsPressedAsState().value

    val container =
        if (!enabled) VT_DisabledBg
        else if (pressed) MaterialTheme.colorScheme.surface.copy(alpha = 0.10f)
        else MaterialTheme.colorScheme.surface

    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        interactionSource = interaction,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, VT_Outline),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = container,
            contentColor = if (enabled) Color.White else VT_DisabledFg,
            disabledContainerColor = VT_DisabledBg,
            disabledContentColor = VT_DisabledFg
        )
    ) {
        Text(tekst)
    }
}

/**
 * Lichtblauwe outline-variant:
 * - Normaal: surface + lichtblauwe rand
 * - Pressed: volledig lichtblauw
 * - Tekst: ALTIJD wit (enabled), zoals gevraagd
 */
@Composable
private fun PrimaireLichtblauweOutlineKnop(
    tekst: String,
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed = interaction.collectIsPressedAsState().value

    val isDisabled = !enabled
    val borderColor = if (isDisabled) VT_DisabledFg else VT_LightBlue

    val containerColor =
        when {
            isDisabled -> VT_DisabledBg
            pressed -> VT_LightBlue
            else -> MaterialTheme.colorScheme.surface
        }

    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        interactionSource = interaction,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, borderColor),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = containerColor,
            contentColor = if (enabled) Color.White else VT_DisabledFg,
            disabledContainerColor = VT_DisabledBg,
            disabledContentColor = VT_DisabledFg
        )
    ) {
        Text(tekst)
    }
}
