@file:OptIn(ExperimentalMaterial3Api::class)

package com.cardlens.scan

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CounterTheme {
                CardLensApp()
            }
        }
    }
}

private val Navy = Color(0xFF14304A)
private val Gold = Color(0xFFF2B233)
private val Mint = Color(0xFF2FB597)

@Composable
fun CounterTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) {
        darkColorScheme(
            primary = Gold, onPrimary = Color(0xFF1B1400),
            secondary = Mint, background = Color(0xFF0E1621), surface = Color(0xFF0E1621),
            surfaceVariant = Color(0xFF1A2633), onSurface = Color(0xFFE6ECF2),
        )
    } else {
        lightColorScheme(
            primary = Navy, onPrimary = Color.White,
            secondary = Mint, background = Color(0xFFF4F6F9), surface = Color(0xFFF4F6F9),
            surfaceVariant = Color(0xFFE3E9F0), onSurface = Color(0xFF14202C),
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun CardLensApp(vm: AppViewModel = viewModel()) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(vm.apiKey.isBlank()) }
    var pendingShot by rememberSaveable { mutableStateOf<Uri?>(null) }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingShot
        if (ok && uri != null) vm.onPhoto(uri)
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.onPhoto(uri)
    }

    fun scan() {
        val dir = File(context.cacheDir, "shots").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
        val file = File(dir, "shot_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        pendingShot = uri
        takePicture.launch(uri)
    }

    LaunchedEffect(vm.toast) {
        val msg = vm.toast
        if (msg != null) {
            snackbar.showSnackbar(msg)
            vm.toast = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CardLens", fontWeight = FontWeight.SemiBold) },
                actions = { TextButton(onClick = { showSettings = true }) { Text("Settings") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { LotSummary(vm) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { scan() },
                        modifier = Modifier.weight(1f).height(56.dp),
                        enabled = vm.phase !is Phase.Working,
                    ) { Text("Scan card", fontSize = 17.sp) }
                    OutlinedButton(
                        onClick = {
                            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        modifier = Modifier.height(56.dp),
                        enabled = vm.phase !is Phase.Working,
                    ) { Text("From photos") }
                }
            }
            item { PhaseView(vm) }
            if (vm.lot.isNotEmpty()) {
                item { LotHeader(vm) }
                items(vm.lot) { LotRow(vm, it) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showSettings) SettingsDialog(vm) { showSettings = false }
}

@Composable
fun LotSummary(vm: AppViewModel) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("OFFER", fontSize = 11.sp, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Text(money(vm.lotOffer), fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${vm.lot.size} card${if (vm.lot.size == 1) "" else "s"}", fontSize = 13.sp)
                    Text("Market ${money(vm.lotMarket)}", fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Paying ${vm.buyPercent}% of market", modifier = Modifier.weight(1f), fontSize = 13.sp)
                OutlinedButton(onClick = { vm.nudgeBuyPercent(-5) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("−5") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.nudgeBuyPercent(5) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("+5") }
            }
        }
    }
}

@Composable
fun ConditionPicker(vm: AppViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Condition.entries.forEach { c ->
            FilterChip(
                selected = vm.condition == c,
                onClick = { vm.condition = c },
                label = { Text(c.label) },
            )
        }
    }
}

@Composable
fun PhaseView(vm: AppViewModel) {
    when (val p = vm.phase) {
        is Phase.Idle -> Text(
            "Lay the card flat, fill the frame, avoid glare. Pick the condition before tapping a price.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
        is Phase.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
            Spacer(Modifier.width(12.dp))
            Text(p.message)
        }
        is Phase.Failed -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(p.message, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { vm.dismiss() }) { Text("OK") }
        }
        is Phase.Result -> ResultView(vm, p)
    }
}

@Composable
fun ResultView(vm: AppViewModel, r: Phase.Result) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (r.photo != null) {
                Image(
                    bitmap = r.photo.asImageBitmap(),
                    contentDescription = "Your photo",
                    modifier = Modifier.width(84.dp).height(118.dp).clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(r.id.name.ifBlank { "Unknown card" }, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(listOf(r.id.setName, r.id.number).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 13.sp)
                Text(
                    listOf(gameLabel(r.id.game), r.id.rarity, r.id.variant).filter { it.isNotBlank() }.joinToString(" · "),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                val conf = (r.id.confidence * 100).toInt()
                Text(
                    if (conf >= 80) "ID confidence $conf%" else "ID confidence $conf% — check the match",
                    fontSize = 12.sp,
                    color = if (conf >= 80) Mint else MaterialTheme.colorScheme.error,
                )
                if (r.id.notes.isNotBlank()) Text(r.id.notes, fontSize = 12.sp)
            }
        }

        ConditionPicker(vm)

        if (r.id.game == "other") {
            Text("That doesn't look like Pokémon, One Piece or Yu-Gi-Oh. Add it with your own price below.", fontSize = 13.sp)
        } else if (r.printings.isEmpty()) {
            Text("No TCGplayer match. Fix the set or number below and search again.", fontSize = 13.sp)
        } else {
            Text("Tap the right printing to add it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            r.printings.forEach { PrintingRow(vm, it) }
        }

        FixSearch(vm, r)
        ManualAdd(vm, r.id.name)
        TextButton(onClick = { vm.dismiss() }) { Text("Discard this scan") }
    }
}

fun gameLabel(g: String) = when (g) {
    "pokemon" -> "Pokémon"
    "yugioh" -> "Yu-Gi-Oh"
    "onepiece" -> "One Piece"
    else -> ""
}

@Composable
fun PrintingRow(vm: AppViewModel, p: Printing) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().clickable { vm.add(p) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            RemoteImage(p.imageUrl, Modifier.width(44.dp).height(62.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(p.groupName, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(p.subType, p.rarity, p.number).filter { it.isNotBlank() }.joinToString(" · "),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                if (p.url.isNotBlank()) {
                    Text(
                        "View on TCGplayer",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.clickable {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.url))) }
                        }.padding(top = 2.dp),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                val m = p.market
                Text(if (m != null) money(m) else "—", fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("market", fontSize = 10.sp)
                if (m != null) {
                    Text(
                        "offer ${money(vm.offerFor(m, vm.condition))}",
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.secondary,
                    )
                } else if (p.low != null) {
                    Text("low ${money(p.low)}", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun FixSearch(vm: AppViewModel, r: Phase.Result) {
    if (r.id.game == "other") return
    var open by remember(r) { mutableStateOf(r.printings.isEmpty()) }
    var name by remember(r) { mutableStateOf(r.id.name) }
    var set by remember(r) { mutableStateOf(r.id.setName) }
    var number by remember(r) { mutableStateOf(r.id.number) }
    if (!open) {
        TextButton(onClick = { open = true }) { Text("Wrong card? Fix and search again") }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Card name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(set, { set = it }, label = { Text("Set name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(number, { number = it }, label = { Text("Number or code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { vm.research(set, number, name) }) { Text("Search again (free)") }
    }
}

@Composable
fun ManualAdd(vm: AppViewModel, defaultName: String) {
    var open by remember { mutableStateOf(false) }
    var price by remember { mutableStateOf("") }
    if (!open) {
        TextButton(onClick = { open = true }) { Text("Add with my own price") }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            price, { price = it.filter { c -> c.isDigit() || c == '.' } },
            label = { Text("Market $") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        Button(onClick = {
            val v = price.toDoubleOrNull()
            if (v != null) vm.addManual(defaultName, v)
        }, enabled = price.toDoubleOrNull() != null) { Text("Add") }
    }
}

@Composable
fun LotHeader(vm: AppViewModel) {
    var armed by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Text("This lot", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = {
            if (armed) {
                vm.clearLot()
                armed = false
            } else {
                armed = true
            }
        }) {
            Text(if (armed) "Tap again to clear" else "Clear lot", color = if (armed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun LotRow(vm: AppViewModel, item: LotItem) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text("${item.condition.label} · ${item.detail}", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(money(vm.offerFor(item.market, item.condition)), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            Text("mkt ${money(item.market * item.condition.mult)}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        TextButton(onClick = { vm.remove(item) }) { Text("✕") }
    }
}

@Composable
fun SettingsDialog(vm: AppViewModel, onClose: () -> Unit) {
    var key by remember { mutableStateOf(vm.apiKey) }
    var model by remember { mutableStateOf(vm.model) }
    var pct by remember { mutableStateOf(vm.buyPercent.toString()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Settings") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    key, { key = it }, label = { Text("Anthropic API key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                )
                Text("Card ID model", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = model == Models.FAST, onClick = { model = Models.FAST }, label = { Text("Fast (Haiku)") })
                    FilterChip(selected = model == Models.ACCURATE, onClick = { model = Models.ACCURATE }, label = { Text("Accurate (Sonnet)") })
                }
                OutlinedTextField(
                    pct, { pct = it.filter { c -> c.isDigit() }.take(3) }, label = { Text("Default buy % of market") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { vm.clearPriceCache() }) { Text("Refresh prices now") }
                ScanLogSection(vm)
                Text(
                    "Prices are TCGplayer market prices, updated daily. Condition discounts: LP 85%, MP 70%, HP 50%, DMG 30% of market.",
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.saveSettings(key, model, pct.toIntOrNull() ?: vm.buyPercent)
                onClose()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

private val imageCache = android.util.LruCache<String, Bitmap>(80)

@Composable
fun RemoteImage(url: String, modifier: Modifier) {
    val bmp by produceState<Bitmap?>(initialValue = imageCache.get(url), url) {
        if (value == null && url.isNotBlank()) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = URL(url).openConnection()
                    conn.setRequestProperty("User-Agent", Http.USER_AGENT)
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 15_000
                    conn.getInputStream().use { BitmapFactory.decodeStream(it) }
                }.getOrNull()?.also { imageCache.put(url, it) }
            }
        }
    }
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surface)) {
        val b = bmp
        if (b != null) {
            Image(b.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }
}

@Composable
fun ScanLogSection(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf(vm.scanStats()) }
    var armed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Scan accuracy", fontWeight = FontWeight.SemiBold)
        if (stats.counted == 0) {
            Text("No scans logged yet. Every scan is saved with what you picked or fixed.", fontSize = 12.sp)
        } else {
            Text("Last ${stats.counted} scans", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Text("Right card first try: ${stats.firstTry}", fontSize = 13.sp, color = Mint)
            Text("Right card lower in list: ${stats.inList}", fontSize = 13.sp)
            Text("Needed a fix: ${stats.fixed}", fontSize = 13.sp)
            Text("Missed (manual, discarded, error): ${stats.missed}", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            Text(
                String.format(java.util.Locale.US, "Average scan time: %.1fs", stats.avgSeconds),
                fontSize = 13.sp, fontFamily = FontFamily.Monospace,
            )
            Text("${stats.totalLogged} scans saved in total", fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                enabled = !busy && stats.totalLogged > 0,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val zip = withContext(Dispatchers.IO) { vm.exportScans() }
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", zip)
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "application/zip"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                putExtra(Intent.EXTRA_SUBJECT, zip.name)
                                clipData = ClipData.newRawUri(zip.name, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, "Share scan log"))
                        } catch (e: Exception) {
                            vm.toast = "Export failed: ${e.message}"
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Preparing…" else "Export log") }
            TextButton(
                enabled = stats.totalLogged > 0,
                onClick = {
                    if (armed) {
                        vm.clearScans()
                        stats = LogStats(0, 0, 0, 0, 0, 0.0, 0)
                        armed = false
                    } else {
                        armed = true
                    }
                },
            ) {
                Text(
                    if (armed) "Tap again to delete" else "Clear log",
                    color = if (armed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
