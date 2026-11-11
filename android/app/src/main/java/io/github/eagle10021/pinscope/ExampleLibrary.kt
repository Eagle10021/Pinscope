package io.github.eagle10021.pinscope

import androidx.compose.material.icons.rounded.Science
import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray

// The Arduino IDE's examples and the kit libraries' examples, precompiled for the Mega (tools/build-examples.py)

data class ArduinoExample(
    val id: String, val name: String, val group: String, val library: String?, val license: String?,
    val desc: String, val size: Int?, val baud: Int?, val multiFile: Boolean,
)

private val BUILTIN_ORDER = listOf("Basics", "Digital", "Analog", "Communication", "Control", "Sensors", "Display", "Strings", "StarterKit BasicKit", "ArduinoISP")

object ExampleLibrary {
    @Volatile private var cache: List<ArduinoExample>? = null

    fun load(context: Context): List<ArduinoExample> = cache ?: runCatching {
        val a = JSONArray(context.assets.open("examples/index.json").bufferedReader().use { it.readText() })
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            ArduinoExample(
                o.getString("id"), o.getString("name"), o.getString("group"),
                o.optString("library").takeIf { !o.isNull("library") && it.isNotEmpty() },
                o.optString("license").takeIf { !o.isNull("license") && it.isNotEmpty() },
                o.optString("desc"), if (o.isNull("size")) null else o.optInt("size"),
                if (o.isNull("baud")) null else o.optInt("baud"), o.optBoolean("multiFile"),
            )
        }.sortedWith(compareBy({ g -> BUILTIN_ORDER.indexOf(g.group).let { if (it < 0) 100 else it } }, { it.group.lowercase() }, { it.name.lowercase() }))
    }.getOrDefault(emptyList()).also { cache = it }

    fun source(context: Context, e: ArduinoExample) = context.assets.open("examples/${e.id}.ino").bufferedReader().use { it.readText() }
    fun hex(context: Context, e: ArduinoExample) = context.assets.open("examples/${e.id}.hex").bufferedReader().use { it.readText() }
}

// Adds the searchable, grouped example list to a LazyColumn
fun LazyListScope.exampleLibrary(
    examples: List<ArduinoExample>, query: String, onQuery: (String) -> Unit,
    expanded: Set<String>, onToggle: (String) -> Unit,
    busy: Boolean, onUpload: (ArduinoExample) -> Unit, onEdit: (ArduinoExample) -> Unit, onSimulate: (ArduinoExample) -> Unit = {},
) {
    item(key = "ex-head") {
        Column(Modifier.padding(top = 16.dp)) {
            Eyebrow("Arduino examples · ${examples.size}", Modifier.padding(start = 4.dp, bottom = 8.dp))
            OutlinedTextField(
                value = query, onValueChange = onQuery, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Search examples, like blink, servo or RFID") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, "Clear") } },
                shape = RoundedCornerShape(50),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) examples else examples.filter { e ->
        e.name.lowercase().contains(q) || e.group.lowercase().contains(q) || e.desc.lowercase().contains(q)
    }
    if (shown.isEmpty()) item(key = "ex-none") {
        Text("No examples match.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
    }
    for ((group, list) in shown.groupBy { it.group }) {
        val open = q.isNotEmpty() || group in expanded
        item(key = "ex-g-$group") {
            val turn by animateFloatAsState(if (open) 180f else 0f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "chev")
            Surface(
                onClick = { onToggle(group) }, shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(group, style = MaterialTheme.typography.titleSmall)
                        val lib = list.first()
                        Text(
                            (if (lib.library != null) "Library · " else "Built-in · ") + "${list.size} examples" + (lib.license?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.Rounded.ExpandMore, if (open) "Collapse" else "Expand", Modifier.rotate(turn))
                }
            }
        }
        if (open) items(list, key = { "ex-${it.id}" }) { e -> ExampleRow(e, busy, onUpload, onEdit, onSimulate) }
    }
}

@Composable
private fun ExampleRow(e: ArduinoExample, busy: Boolean, onUpload: (ArduinoExample) -> Unit, onEdit: (ArduinoExample) -> Unit, onSimulate: (ArduinoExample) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp).clip(MaterialTheme.shapes.small).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(e.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(e.desc.takeIf { it.isNotBlank() }, e.baud?.let { "$it baud" }).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { onSimulate(e) }) { Icon(androidx.compose.material.icons.Icons.Rounded.Science, "Try ${e.name} in the simulator") }
        if (!e.multiFile) IconButton(onClick = { onEdit(e) }) { Icon(Icons.Rounded.Edit, "Edit a copy of ${e.name}") }
        IconButton(onClick = { onUpload(e) }, enabled = !busy) { Icon(Icons.Rounded.Upload, "Upload ${e.name}", tint = MaterialTheme.colorScheme.primary) }
    }
}
