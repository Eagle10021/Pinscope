@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.eagle10021.pinscope

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.toShape
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text.uppercase(), style = EyebrowStyle, color = color, modifier = modifier)
}

// Pin name in a small circuit-board badge
@Composable
fun PinBadge(name: String, modifier: Modifier = Modifier, big: Boolean = false, color: Color? = null) {
    val c = LocalPinColors.current
    Box(
        modifier
            .clip(RoundedCornerShape(if (big) 12.dp else 6.dp))
            .background(c.pcb)
            .then(if (color != null) Modifier.border(2.dp, color, RoundedCornerShape(if (big) 12.dp else 6.dp)) else Modifier)
            .padding(horizontal = if (big) 14.dp else 7.dp, vertical = if (big) 8.dp else 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(name, color = c.silk, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = if (big) 22.sp else 13.sp)
    }
}

@Composable
fun TagPill(tag: String) {
    val col = LocalPinColors.current.forTag(tag)
    Box(
        Modifier.clip(CircleShape).background(col.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 3.dp),
    ) { Text(TAG_NAMES[tag] ?: tag, color = col, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun Card(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(16.dp), content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) { Column(Modifier.padding(padding), content = content) }
}

@Composable
fun FactRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(k, Modifier.width(118.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(v, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

// Horizontal chip row: All, Kit parts, then each pin function; a part filter shows as a removable chip
@Composable
fun FilterBar(filter: Filter, kit: KitData, onChange: (Filter) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalPinColors.current
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (filter is Filter.Part) {
            val part = kit.part(filter.id)
            val col = part?.let { c.forCategory(it.catKey) } ?: MaterialTheme.colorScheme.primary
            FilterChip(
                selected = true, onClick = { onChange(Filter.All) },
                label = { Text(part?.name ?: filter.id, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp)) },
                trailingIcon = { Icon(Icons.Rounded.Close, "Clear", Modifier.size(16.dp)) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = col.copy(alpha = 0.2f), selectedLabelColor = MaterialTheme.colorScheme.onSurface),
            )
        }
        Chip("All", filter == Filter.All, null) { onChange(Filter.All) }
        Chip("Kit parts", filter == Filter.Kit, MaterialTheme.colorScheme.secondary) { onChange(Filter.Kit) }
        for ((tag, name) in TAG_NAMES) if (tag != "digital")
            Chip(name, filter == Filter.Tag(tag), c.forTag(tag)) { onChange(if (filter == Filter.Tag(tag)) Filter.All else Filter.Tag(tag)) }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, dot: Color?, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label) },
        leadingIcon = dot?.let { { Box(Modifier.size(8.dp).clip(CircleShape).background(it)) } },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = (dot ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.18f),
            selectedLabelColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected, borderColor = MaterialTheme.colorScheme.outlineVariant, selectedBorderColor = (dot ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.6f)),
    )
}

// Everything about one Mega pin, plus live controls when Firmata is running
@Composable
fun PinDetails(pin: Pin, vm: AppViewModel, live: FirmataPin?, connected: Boolean) {
    val kit = vm.kit
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PinBadge(pin.label.ifEmpty { pin.name }, big = true)
            Column {
                Text(pin.title, style = MaterialTheme.typography.titleLarge)
                Text(pin.header + " header", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (pin.tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            pin.tags.forEach { TagPill(it) }
        }
        if (pin.desc.isNotEmpty()) Text(pin.desc, style = MaterialTheme.typography.bodyLarge)

        if (pin.num != null && connected) LiveControl(pin, vm, live)

        if (pin.lines.isNotEmpty() || !pin.isPower) Card(padding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
            pin.lines.forEach { (k, v) -> FactRow(k, v) }
            if (!pin.isPower) FactRow("Limits", "20 mA per pin, 5V logic")
        }

        Eyebrow("Used by kit parts")
        if (pin.uses.isEmpty()) Text("No kit lesson uses this pin, so it is free for your own wiring.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            pin.uses.forEach { u ->
                val part = kit.part(u.part) ?: return@forEach
                val col = LocalPinColors.current.forCategory(part.catKey)
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { vm.selectedPin = null; vm.openPart = part.id; vm.tab = Tab.PARTS }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(col))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(part.name, fontWeight = FontWeight.Medium)
                        Text("${u.pin} · ${u.set}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun LiveControl(pin: Pin, vm: AppViewModel, live: FirmataPin?) {
    val num = pin.num ?: return
    val c = LocalPinColors.current
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Memory, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Eyebrow("Live on your board", color = MaterialTheme.colorScheme.primary)
            }
            if (live == null || live.modes.isEmpty()) {
                Text("Waiting for the board's pin list…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            val modes = PinMode.controllable.filter { it in live.modes }
            if (num in 0..1) {
                Text("Pins 0 and 1 carry the USB serial link, so Firmata keeps them for itself.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            ConnectedToggles(modes.map { it.label }, modes.indexOf(live.mode), small = modes.size > 3) { vm.bench.setPinMode(num, modes[it]) }
            PinValue(num, live, vm, c)
        }
    }
}

@Composable
fun PinValue(num: Int, live: FirmataPin, vm: AppViewModel, c: PinColors) {
    when (live.mode) {
        PinMode.OUTPUT -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (live.value != 0) "HIGH" else "LOW", fontFamily = Mono, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Switch(checked = live.value != 0, onCheckedChange = { vm.bench.digitalWrite(num, it) })
        }
        PinMode.INPUT, PinMode.PULLUP -> Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (live.value != 0) c.ok else MaterialTheme.colorScheme.outline))
            Spacer(Modifier.width(10.dp))
            Text(if (live.value != 0) "HIGH" else "LOW", fontFamily = Mono, fontWeight = FontWeight.Bold)
        }
        PinMode.PWM, PinMode.SERVO -> {
            val max = if (live.mode == PinMode.PWM) 255f else 180f
            var drag by remember(num, live.mode) { mutableFloatStateOf(live.value.toFloat()) }
            Column {
                Text(if (live.mode == PinMode.PWM) "Duty ${drag.roundToInt()} / 255 (${(drag / 2.55f).roundToInt()}%)" else "Angle ${drag.roundToInt()}°", fontFamily = Mono)
                Slider(value = drag, onValueChange = { drag = it; vm.bench.analogWrite(num, it.roundToInt()) }, valueRange = 0f..max)
            }
        }
        PinMode.ANALOG -> Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${live.value}", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 26.sp)
                Spacer(Modifier.width(8.dp))
                Text("of 1023 · %.2f V".format(live.value * 5f / 1023f), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Box(Modifier.fillMaxWidth(live.value / 1023f).height(6.dp).clip(CircleShape).background(c.forTag("analog")))
            }
        }
        else -> Text("Pick a mode to start.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val spin by rememberInfiniteTransition(label = "badge").animateFloat(
            0f, 360f, infiniteRepeatable(tween(24000, easing = LinearEasing), RepeatMode.Restart), label = "spin",
        )
        Box(Modifier.requiredSize(96.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.requiredSize(96.dp).rotate(spin).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.primaryContainer))
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        actions()
    }
}

// Material 3 Expressive connected button group: one choice, shapes morph as the selection moves
@Composable
fun ConnectedToggles(labels: List<String>, selected: Int, modifier: Modifier = Modifier, small: Boolean = false, onSelect: (Int) -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        labels.forEachIndexed { i, label ->
            ToggleButton(
                checked = i == selected,
                onCheckedChange = { onSelect(i) },
                modifier = Modifier.weight(1f),
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    labels.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = if (small) PaddingValues(horizontal = 4.dp, vertical = 8.dp) else PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            ) { Text(label, maxLines = 1, fontSize = if (small) 12.sp else 14.sp) }
        }
    }
}
