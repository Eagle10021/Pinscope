package io.github.eagle10021.pinscope

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri

@Composable
fun PartsScreen(vm: AppViewModel) {
    val open = vm.openPart?.let { vm.kit.part(it) }
    if (open != null) {
        BackHandler { vm.openPart = null }
        PartDetail(open, vm)
    } else PartList(vm)
}

@Composable
private fun PartList(vm: AppViewModel) {
    val kit = vm.kit
    var query by rememberSaveable { mutableStateOf("") }
    var cat by rememberSaveable { mutableStateOf<String?>(null) }
    val q = query.trim().lowercase()
    val shown = kit.parts.filter { p ->
        (cat == null || p.cat == cat) &&
            (q.isEmpty() || listOf(p.name, p.part, p.blurb).any { it.lowercase().contains(q) } ||
                p.refs.any { it.lowercase() == q || it.lowercase() == "d$q" })
    }
    val colors = LocalPinColors.current

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 8.dp)) {
                Text("Kit parts", style = MaterialTheme.typography.headlineSmall)
                Text("${kit.parts.size} parts from the ELEGOO Mega starter kit", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search parts or a pin, like 22") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                )
            }
        }
        item {
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().horizontalScrollCompat().padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CatChip("All", null, cat == null) { cat = null }
                for ((name, key) in kit.categories) CatChip(name, colors.forCategory(key), cat == name) { cat = if (cat == name) null else name }
            }
        }
        if (shown.isEmpty()) item {
            EmptyState(Icons.Rounded.SearchOff, "No parts match", "Try a part name like \"servo\", a chip like \"74HC595\", or a pin number.", Modifier.fillMaxWidth())
        }
        val groups = shown.groupBy { it.cat }
        for ((name, _) in kit.categories) {
            val list = groups[name] ?: continue
            item(key = "h-$name") {
                Eyebrow(name, Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp))
            }
            items(list, key = { it.id }) { p -> PartRow(p) { vm.openPart = p.id } }
        }
    }
}

@Composable
private fun Modifier.horizontalScrollCompat() = this.horizontalScroll(rememberScrollState())

@Composable
private fun CatChip(label: String, dot: Color?, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.FilterChip(
        selected = selected, onClick = onClick, label = { Text(label) },
        leadingIcon = dot?.let { { Box(Modifier.size(8.dp).clip(CircleShape).background(it)) } },
    )
}

@Composable
private fun PartRow(p: Part, onClick: () -> Unit) {
    val col = LocalPinColors.current.forCategory(p.catKey)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(col.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Text(p.pins.size.toString(), color = col, fontWeight = FontWeight.Bold, fontFamily = Mono)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                p.part + (p.lesson?.let { " · Lesson $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        val pins = p.refs.filter { it != "5V" && it != "GND" && it != "3V3" && it != "VIN" }
        if (pins.isNotEmpty()) Text(
            pins.take(3).joinToString(" ") { it.removePrefix("D") } + if (pins.size > 3) " +${pins.size - 3}" else "",
            fontFamily = Mono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PartDetail(p: Part, vm: AppViewModel) {
    val col = LocalPinColors.current.forCategory(p.catKey)
    val context = LocalContext.current
    val open = { url: String -> context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.openPart = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text("Kit parts", style = MaterialTheme.typography.titleMedium)
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(col))
                    Spacer(Modifier.width(8.dp))
                    Eyebrow(p.cat + (p.lesson?.let { " · Lesson $it" } ?: ""), color = col)
                }
                Text(p.name, style = MaterialTheme.typography.headlineSmall)
                Text(p.part, fontFamily = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (p.blurb.isNotEmpty()) Text(p.blurb, style = MaterialTheme.typography.bodyLarge)
                if (p.refs.isNotEmpty()) Button(onClick = { vm.showPartOnBoard(p.id) }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    Icon(Icons.Rounded.DeveloperBoard, null); Spacer(Modifier.width(10.dp))
                    Text(if (vm.photo != null) "Show on my board" else "Show on the board")
                }
            }
        }
        item { Section("Pinout") }
        item {
            Card(Modifier.padding(horizontal = 20.dp), padding = PaddingValues(vertical = 6.dp)) {
                p.pins.forEachIndexed { i, pin ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 16.dp))
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(pin.num, fontFamily = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(36.dp))
                        Text(pin.name, fontFamily = Mono, fontWeight = FontWeight.Bold, modifier = Modifier.width(84.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(pin.desc, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        for (w in p.wiring) {
            item { Section(w.title) }
            item {
                Card(Modifier.padding(horizontal = 20.dp), padding = PaddingValues(vertical = 6.dp)) {
                    w.rows.forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 16.dp))
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.pin, fontWeight = FontWeight.Medium)
                                if (r.note.isNotEmpty()) Text(r.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(12.dp))
                            if (r.refs.isNotEmpty()) PinBadge(r.mega.replace(Regex("\\bD(\\d)"), "$1"), color = if (r.refs.any { it == "GND" || it == "5V" || it == "3V3" }) null else col)
                            else Text(r.mega, fontFamily = Mono, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (p.resistors.isNotEmpty() || p.noResistor != null) {
            item { Section("Resistors") }
            item {
                Card(Modifier.padding(horizontal = 20.dp)) {
                    p.noResistor?.let { Text(it) }
                    p.resistors.forEachIndexed { i, r ->
                        if (i > 0) Spacer(Modifier.height(14.dp))
                        Text(r.value, fontFamily = Mono, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(r.where, fontWeight = FontWeight.Medium)
                        Text(r.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (p.notes.isNotEmpty()) {
            item { Section("Notes") }
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    p.notes.forEach { n ->
                        Row {
                            Box(Modifier.padding(top = 8.dp).size(6.dp).clip(CircleShape).background(col))
                            Spacer(Modifier.width(12.dp))
                            Text(n, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        if (p.links.isNotEmpty()) {
            item { Section("Datasheets and links") }
            items(p.links) { l ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(MaterialTheme.shapes.small).clickable { open(l.url) }.padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(l.label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 10.dp))
}
