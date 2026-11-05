package com.tia.galerie

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

data class Cfg(val style: Int, val delay: Long, val loop: Boolean, val shuffle: Boolean, val autoVid: Boolean)
data class Theme(val name: String, val top: Color, val bottom: Color, val accent: Color)
class Acts(val fav: (Media) -> Unit, val share: (Media) -> Unit, val edit: (Media) -> Unit, val vault: (Media) -> Unit,
           val trash: (Media) -> Unit, val restore: (Media) -> Unit, val purge: (Media) -> Unit)

val Themes = listOf(
    Theme("Néon", Color(0xFF2B1560), Color(0xFF0E0A1F), Color(0xFFFF8A3D)),
    Theme("Océan", Color(0xFF0B3C8F), Color(0xFF050B1F), Color(0xFF22D3C5)),
    Theme("AMOLED", Color(0xFF000000), Color(0xFF000000), Color(0xFFFF4D8D)),
    Theme("Forêt", Color(0xFF0F5132), Color(0xFF04120C), Color(0xFF8EF5A0)))
val Styles = listOf("Cube 3D", "Flip", "Zoom fondu", "Spirale")
const val COPYRIGHT = "© 2026 TIA. Tous droits réservés."
object T { var accent by mutableStateOf(Color(0xFFFF8A3D)) }

class MainActivity : FragmentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { App(this) } }
    }
}

@Composable
fun Chip(label: String, on: Boolean, click: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(if (on) T.accent else Color(0x1AFFFFFF)).clickable(onClick = click)
        .padding(horizontal = 16.dp, vertical = 10.dp)) { Text(label, color = if (on) Color(0xFF1A0A00) else Color(0xFFF4EFFF), fontSize = 14.sp) }
}

@Composable
fun Section(t: String) = Text(t, Modifier.padding(top = 20.dp, bottom = 8.dp), color = T.accent, fontSize = 16.sp)

@Composable
fun Options(opts: List<String>, sel: Int, pick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        opts.forEachIndexed { k, n -> Chip(n, sel == k) { pick(k) } }
    }
}

@Composable
fun SwitchRow(label: String, v: Boolean, on: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = Color(0xFFF4EFFF))
        Switch(v, on)
    }
}

@Composable
fun MediaGrid(data: List<Media>, cols: Int, anim: Boolean, favs: Set<String>, loader: ImageLoader, click: (Int) -> Unit) {
    LazyVerticalGrid(GridCells.Fixed(cols), contentPadding = PaddingValues(6.dp, 6.dp, 6.dp, 120.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(data.size, span = { i -> GridItemSpan(if (i == 0) cols else 1) }) { i ->
            var vis by remember { mutableStateOf(!anim) }
            LaunchedEffect(Unit) { if (anim) { delay((i % 15) * 40L); vis = true } }
            val a by animateFloatAsState(if (vis) 1f else 0f, spring(Spring.DampingRatioMediumBouncy), label = "a")
            Box(Modifier.aspectRatio(if (i == 0) 1.5f else 1f).graphicsLayer { scaleX = a; scaleY = a; alpha = a; rotationZ = (1 - a) * 18f }
                .clip(RoundedCornerShape(14.dp)).clickable { click(i) }) {
                AsyncImage(model = data[i].uri, contentDescription = null, imageLoader = loader,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (data[i].video) Text("▶", Modifier.align(Alignment.BottomEnd).padding(8.dp), color = Color.White)
                if (data[i].key in favs) Text("♥", Modifier.align(Alignment.TopEnd).padding(8.dp), color = T.accent)
            }
        }
    }
}

@Composable
fun App(act: FragmentActivity) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val loader = remember { ImageLoader.Builder(ctx).components { add(VideoFrameDecoder.Factory()) }.build() }
    val sp = remember { ctx.getSharedPreferences("tia", Context.MODE_PRIVATE) }
    var cols by remember { mutableIntStateOf(sp.getInt("cols", 3)) }
    var style by remember { mutableIntStateOf(sp.getInt("style", 0)) }
    var delayMs by remember { mutableLongStateOf(sp.getLong("delay", 2500L)) }
    var theme by remember { mutableIntStateOf(sp.getInt("theme", 0)) }
    var anim by remember { mutableStateOf(sp.getBoolean("anim", true)) }
    var loop by remember { mutableStateOf(sp.getBoolean("loop", true)) }
    var shuffle by remember { mutableStateOf(sp.getBoolean("shuffle", false)) }
    var autoVid by remember { mutableStateOf(sp.getBoolean("autoVid", false)) }
    var newest by remember { mutableStateOf(sp.getBoolean("newest", true)) }
    var lock by remember { mutableStateOf(sp.getBoolean("lock", false)) }
    var favs by remember { mutableStateOf(sp.getStringSet("favs", emptySet())!!.toSet()) }
    LaunchedEffect(cols, style, delayMs, theme, anim, loop, shuffle, autoVid, newest, lock, favs) {
        sp.edit().putInt("cols", cols).putInt("style", style).putLong("delay", delayMs).putInt("theme", theme)
            .putBoolean("anim", anim).putBoolean("loop", loop).putBoolean("shuffle", shuffle)
            .putBoolean("autoVid", autoVid).putBoolean("newest", newest).putBoolean("lock", lock)
            .putStringSet("favs", favs).apply()
    }
    val th = Themes[theme]
    SideEffect { T.accent = th.accent }

    var list by remember { mutableStateOf(emptyList<Media>()) }
    var trashList by remember { mutableStateOf(emptyList<Media>()) }
    var vault by remember { mutableStateOf(emptyList<Media>()) }
    var open by remember { mutableStateOf(false) }
    var idx by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }
    var filt by remember { mutableIntStateOf(0) }
    var album by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Media?>(null) }
    var locked by remember { mutableStateOf(sp.getBoolean("lock", false)) }

    val reload = { list = loadMedia(ctx); trashList = loadMedia(ctx, true); vault = loadVault(ctx) }
    val perms = if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { reload() }
    val sender = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { reload() }
    fun ask(pi: PendingIntent) = sender.launch(IntentSenderRequest.Builder(pi.intentSender).build())
    LaunchedEffect(Unit) { launcher.launch(perms) }
    LaunchedEffect(locked) { if (locked) authenticate(act, "TIA GALERIE", false) { locked = false } }

    val cr = ctx.contentResolver
    val acts = Acts(
        fav = { m -> favs = if (m.key in favs) favs - m.key else favs + m.key },
        share = { m -> share(ctx, m) },
        edit = { m -> editing = m },
        vault = { m -> scope.launch { withContext(Dispatchers.IO) { vaultAdd(ctx, m) }; toast(ctx, "Copié dans le coffre. Confirme la suppression de l'original."); ask(MediaStore.createDeleteRequest(cr, listOf(m.uri))) } },
        trash = { m -> ask(MediaStore.createTrashRequest(cr, listOf(m.uri), true)) },
        restore = { m ->
            if (m.uri.scheme == "file") scope.launch { withContext(Dispatchers.IO) { vaultRestore(ctx, m) }; reload() }
            else ask(MediaStore.createTrashRequest(cr, listOf(m.uri), false))
        },
        purge = { m ->
            if (m.uri.scheme == "file") { File(m.uri.path!!).delete(); reload() }
            else ask(MediaStore.createDeleteRequest(cr, listOf(m.uri)))
        })

    val src = if (filt == 4) trashList else list
    val base = src.filter { m -> when (filt) { 1 -> !m.video; 2 -> m.video; 3 -> m.key in favs; else -> true } && (album == null || m.album == album) }
    val shown = if (newest) base else base.reversed()
    val mode = if (tab == 2) 2 else if (filt == 4) 1 else 0
    val viewList = if (tab == 2) vault else shown
    val delays = listOf(2500L, 4000L, 6000L)
    val title = when { tab == 2 -> "Coffre privé"; tab == 0 && filt == 4 -> "Corbeille"; tab == 0 && album != null -> album!!; else -> "TIA GALERIE" }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to th.top, 0.45f to th.bottom, 1f to th.bottom))) {
        Column(Modifier.statusBarsPadding()) {
            Text(title, Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp), fontSize = 30.sp, color = T.accent)
            when (tab) {
                0 -> {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Tout", filt == 0 && album == null) { filt = 0; album = null }
                        Chip("Photos", filt == 1) { filt = 1 }
                        Chip("Vidéos", filt == 2) { filt = 2 }
                        Chip("Favoris", filt == 3) { filt = 3 }
                        Chip("Corbeille", filt == 4) { filt = 4 }
                    }
                    if (shown.isEmpty()) Text(if (filt == 3) "Aucun favori. Ouvre une photo et touche ♡ Favori." else if (filt == 4) "La corbeille est vide." else "", Modifier.padding(20.dp), color = Color(0xFFC9BFE6))
                    MediaGrid(shown, cols, anim, favs, loader) { i -> idx = i; open = true }
                }
                1 -> LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 120.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val groups = list.groupBy { it.album }.entries.toList()
                    items(groups.size) { g ->
                        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(20.dp)).clickable { album = groups[g].key; filt = 0; tab = 0 }) {
                            AsyncImage(model = groups[g].value.first().uri, contentDescription = null, imageLoader = loader,
                                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            Text(groups[g].key + " (" + groups[g].value.size + ")", Modifier.align(Alignment.BottomStart).padding(12.dp), color = Color.White)
                        }
                    }
                }
                2 -> {
                    if (vault.isEmpty()) Text("Le coffre est vide. Ouvre une photo et touche « Coffre » pour la cacher ici. Elle disparaît de la galerie et ne s'ouvre qu'avec ton empreinte ou le code du téléphone.", Modifier.padding(20.dp), color = Color(0xFFC9BFE6))
                    MediaGrid(vault, cols, anim, favs, loader) { i -> idx = i; open = true }
                }
                else -> Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 130.dp)) {
                    Section("Apparence")
                    Options(Themes.map { it.name }, theme) { theme = it }
                    Section("Grille")
                    Text("Colonnes", color = Color(0xFFC9BFE6))
                    Options(listOf("2", "3", "4", "5"), cols - 2) { cols = it + 2 }
                    Spacer(Modifier.height(8.dp))
                    Options(listOf("Plus récents d'abord", "Plus anciens d'abord"), if (newest) 0 else 1) { newest = it == 0 }
                    SwitchRow("Animations de la grille", anim) { anim = it }
                    Section("Slides")
                    Options(Styles, style) { style = it }
                    Section("Diaporama")
                    Options(listOf("2,5 s", "4 s", "6 s"), delays.indexOf(delayMs).coerceAtLeast(0)) { delayMs = delays[it] }
                    SwitchRow("Lecture en boucle", loop) { loop = it }
                    SwitchRow("Ordre aléatoire", shuffle) { shuffle = it }
                    Section("Vidéos")
                    SwitchRow("Lecture automatique à l'ouverture", autoVid) { autoVid = it }
                    Section("Sécurité")
                    SwitchRow("Verrouiller l'application (empreinte ou code)", lock) { lock = it }
                    Text("Le coffre privé demande toujours l'empreinte ou le code du téléphone.", color = Color(0xFFC9BFE6), fontSize = 13.sp)
                    Section("Corbeille")
                    Text("Les éléments supprimés restent dans la corbeille d'Android pendant 30 jours environ avant d'être effacés.", color = Color(0xFFC9BFE6), fontSize = 13.sp)
                    Section("Réinitialisation")
                    Chip("Rétablir les réglages par défaut", false) {
                        cols = 3; style = 0; delayMs = 2500L; theme = 0; anim = true; loop = true; shuffle = false; autoVid = false; newest = true; lock = false
                    }
                    Section("À propos")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.logo), null, Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)))
                        Column(Modifier.padding(start = 14.dp)) {
                            Text("TIA GALERIE", color = Color.White, fontSize = 18.sp)
                            Text("Version 1.1", color = Color(0xFFC9BFE6), fontSize = 13.sp)
                            Text(COPYRIGHT, color = Color(0xFFC9BFE6), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 24.dp, vertical = 28.dp)
            .clip(RoundedCornerShape(50)).background(Color(0xEE1D0F3D)).padding(4.dp), horizontalArrangement = Arrangement.SpaceAround) {
            listOf("Photos", "Albums", "Coffre", "Réglages").forEachIndexed { k, n ->
                TextButton({
                    if (k == 2 && tab != 2) authenticate(act, "Coffre privé", true) { reload(); tab = 2 } else tab = k
                }) { Text(n, color = if (tab == k) T.accent else Color(0xFFC9BFE6)) }
            }
        }
        AnimatedVisibility(open, enter = fadeIn() + scaleIn(initialScale = 0.7f), exit = fadeOut() + scaleOut(targetScale = 0.7f)) {
            Viewer(viewList, idx, loader, Cfg(style, delayMs, loop, shuffle, autoVid), mode, favs, acts) { open = false }
        }
        editing?.let { m -> Editor(m) { saved -> editing = null; if (saved) reload() } }
        if (locked) Column(Modifier.fillMaxSize().background(Nuit()).padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.logo), null, Modifier.size(120.dp).clip(RoundedCornerShape(26.dp)))
            Text("TIA GALERIE", Modifier.padding(top = 16.dp, bottom = 20.dp), color = T.accent, fontSize = 24.sp)
            Chip("Déverrouiller", true) { authenticate(act, "TIA GALERIE", false) { locked = false } }
        }
    }
}

fun Nuit() = Color(0xFF0E0A1F)

@Composable
fun Editor(m: Media, onDone: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var bmp by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var rot by remember { mutableIntStateOf(0) }
    var fl by remember { mutableIntStateOf(0) }
    var ratio by remember { mutableIntStateOf(0) }
    LaunchedEffect(m) { bmp = withContext(Dispatchers.IO) { runCatching { loadBitmap(ctx, m.uri) }.getOrNull() } }
    BackHandler { onDone(false) }
    val b = bmp
    val out = if (b != null) remember(b, rot, ratio) { cropBmp(rotateBmp(b, rot * 90), Ratios[ratio].second) } else null
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
            if (out == null) Text("Chargement…", color = Color.White)
            else Image(out.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                colorFilter = filterArr(fl)?.let { ColorFilter.colorMatrix(ColorMatrix(it)) })
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Filtre", color = Color(0xFFC9BFE6)); Options(FilterNames, fl) { fl = it }
            Text("Recadrage", color = Color(0xFFC9BFE6)); Options(Ratios.map { it.first }, ratio) { ratio = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("↻ Pivoter", false) { rot = (rot + 1) % 4 }
                Chip("Annuler", false) { onDone(false) }
                Chip("Enregistrer", true) {
                    if (out != null) scope.launch {
                        withContext(Dispatchers.IO) { saveEdited(ctx, out, fl) }
                        toast(ctx, "Enregistré dans Pictures/TIA GALERIE (l'original est conservé)")
                        onDone(true)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Viewer(list: List<Media>, start: Int, loader: ImageLoader, cfg: Cfg, mode: Int, favs: Set<String>, acts: Acts, onClose: () -> Unit) {
    val pager = rememberPagerState(start) { list.size }
    var style by remember { mutableIntStateOf(cfg.style) }
    var auto by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)
    LaunchedEffect(auto, pager.currentPage) {
        if (auto && list.isNotEmpty()) {
            delay(cfg.delay)
            val c = pager.currentPage
            if (!cfg.loop && !cfg.shuffle && c == list.size - 1) auto = false
            else pager.animateScrollToPage(if (cfg.shuffle) Random.nextInt(list.size) else (c + 1) % list.size)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { p ->
            val o = (pager.currentPage - p) + pager.currentPageOffsetFraction
            Box(Modifier.fillMaxSize().graphicsLayer {
                val a = abs(o)
                cameraDistance = 14f * density
                when (style) {
                    0 -> { transformOrigin = TransformOrigin(if (o < 0) 0f else 1f, 0.5f); rotationY = o * 90f; alpha = if (a > 1f) 0f else 1f }
                    1 -> { translationX = size.width * o; rotationY = o * 180f; alpha = if (a < 0.5f) 1f else 0f }
                    2 -> { scaleX = 1 - a * 0.3f; scaleY = 1 - a * 0.3f; alpha = 1 - a }
                    else -> { rotationZ = o * -25f; scaleX = 1 - a * 0.4f; scaleY = 1 - a * 0.4f; alpha = 1 - a * 0.8f }
                }
            }) { Page(list[p], loader, cfg.autoVid) }
        }
        Column(Modifier.align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))).statusBarsPadding()) {
            Row {
                TextButton(onClose) { Text("✕") }
                TextButton({ style = (style + 1) % 4 }) { Text(Styles[style]) }
                TextButton({ auto = !auto }) { Text(if (auto) "⏸ Stop" else "▶ Diaporama") }
            }
            val m = list.getOrNull(pager.currentPage)
            if (m != null) Row(Modifier.horizontalScroll(rememberScrollState())) {
                when (mode) {
                    0 -> {
                        TextButton({ acts.fav(m) }) { Text(if (m.key in favs) "♥ Favori" else "♡ Favori") }
                        TextButton({ acts.share(m) }) { Text("Partager") }
                        if (!m.video) TextButton({ onClose(); acts.edit(m) }) { Text("Éditer") }
                        TextButton({ acts.vault(m); onClose() }) { Text("Coffre") }
                        TextButton({ acts.trash(m); onClose() }) { Text("Corbeille") }
                    }
                    1 -> {
                        TextButton({ acts.restore(m); onClose() }) { Text("Restaurer") }
                        TextButton({ acts.purge(m); onClose() }) { Text("Supprimer définitivement") }
                    }
                    else -> {
                        TextButton({ acts.restore(m); onClose() }) { Text("Remettre dans la galerie") }
                        TextButton({ acts.purge(m); onClose() }) { Text("Supprimer définitivement") }
                    }
                }
            }
        }
    }
}

@Composable
fun Page(m: Media, loader: ImageLoader, autoVid: Boolean) {
    var play by remember { mutableStateOf(autoVid && m.video) }
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        if (play) VideoView(m.uri) else {
            AsyncImage(model = m.uri, contentDescription = null, imageLoader = loader, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().clickable(enabled = m.video) { play = true })
            if (m.video) Text("▶", color = Color.White, fontSize = 64.sp)
        }
    }
}

@Composable
fun VideoView(uri: Uri) {
    val ctx = LocalContext.current
    val player = remember { ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(uri)); prepare(); playWhenReady = true } }
    DisposableEffect(Unit) { onDispose { player.release() } }
    AndroidView({ PlayerView(it).apply { this.player = player } }, Modifier.fillMaxSize())
}
