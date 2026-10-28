package com.tnbus.ticketing

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.function.Consumer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val colors = lightColorScheme(
                primary = Color(0xFF1B3A5C),
                onPrimary = Color.White,
                primaryContainer = Color(0xFFDCE8F5),
                onPrimaryContainer = Color(0xFF0E2238),
                secondary = Color(0xFF5B6B7C),
                tertiary = Color(0xFFE8890C),
                background = Color(0xFFF5F7FA),
                surface = Color.White,
                surfaceVariant = Color(0xFFEAEFF5),
                error = Color(0xFFC62828),
            )
            MaterialTheme(colorScheme = colors) { Surface(Modifier.fillMaxSize()) { App() } }
        }
    }
}

private enum class Screen { TICKET, CANCEL, SETTINGS }

private fun onMain(block: () -> Unit) = Handler(Looper.getMainLooper()).post(block)

@SuppressLint("MissingPermission")
private fun requestLocation(context: Context, onResult: (Location?) -> Unit) {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .firstOrNull { lm.isProviderEnabled(it) }
    if (provider == null) { onResult(null); return }
    if (android.os.Build.VERSION.SDK_INT >= 30) {
        lm.getCurrentLocation(provider, CancellationSignal(), ContextCompat.getMainExecutor(context),
            Consumer { onResult(it) })
    } else {
        @Suppress("DEPRECATION")
        lm.requestSingleUpdate(provider, { onResult(it) }, null)
    }
}

@Composable
fun App() {
    val context = LocalContext.current
    val store = remember { Store(context) }
    var screen by remember { mutableStateOf(Screen.TICKET) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Image(painterResource(R.drawable.bus_logo), null, Modifier.size(44.dp))
                    Column(Modifier.weight(1f)) {
                        Text(store.operatorName.ifBlank { "OASYS BUS" }, fontSize = 20.sp,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(
                            (if (store.busNumber.isNotBlank()) "Bus ${store.busNumber}  |  " else "") +
                            when (screen) {
                                Screen.TICKET -> "Issue ticket"
                                Screen.CANCEL -> "Cancel ticket"
                                Screen.SETTINGS -> "Settings"
                            }, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(screen == Screen.TICKET, { screen = Screen.TICKET },
                    { Icon(Icons.Default.Add, null) }, label = { Text("Issue") })
                NavigationBarItem(screen == Screen.CANCEL, { screen = Screen.CANCEL },
                    { Icon(Icons.Default.Close, null) }, label = { Text("Cancel") })
                NavigationBarItem(screen == Screen.SETTINGS, { screen = Screen.SETTINGS },
                    { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
            }
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (screen) {
                Screen.TICKET -> TicketScreen(store)
                Screen.CANCEL -> CancelScreen(store)
                Screen.SETTINGS -> SettingsScreen(store)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
            content()
        }
    }
}

@Composable
private fun StatusText(status: String) {
    if (status.isEmpty()) return
    val err = status.startsWith("ERR:")
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (err) Color(0xFFFDECEA) else MaterialTheme.colorScheme.primaryContainer
    ) {
        Text(status.removePrefix("ERR:"), Modifier.fillMaxWidth().padding(12.dp),
            color = if (err) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TicketScreen(store: Store) {
    val context = LocalContext.current
    var from by remember { mutableStateOf<Stop?>(null) }
    var to by remember { mutableStateOf<Stop?>(null) }
    var passengers by remember { mutableIntStateOf(1) }
    var mode by remember { mutableStateOf(PAYMENT_MODES[0]) }
    var copies by remember { mutableIntStateOf(1) }
    var lastTicket by remember { mutableStateOf(store.tickets().lastOrNull { !it.cancelled }) }
    var status by remember { mutableStateOf("") }
    var target by remember { mutableIntStateOf(0) } // 0 = from, 1 = to
    var nextLabel by remember { mutableStateOf(store.formatNumber(store.nextNumber)) }

    fun detect(which: Int) {
        status = "Detecting location..."
        requestLocation(context) { loc ->
            if (loc == null) {
                status = "Could not get GPS fix. Turn on Location and try again."
            } else {
                val s = nearestStop(loc.latitude, loc.longitude)
                if (which == 0) from = s else to = s
                status = String.format(Locale.US, "Detected near %s (%.4f, %.4f)", s.name, loc.latitude, loc.longitude)
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) detect(target) else status = "Location permission denied."
    }

    fun onGps(which: Int) {
        target = which
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) detect(which)
        else permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    val copyButton: @Composable (Modifier) -> Unit = { mod ->
        OutlinedButton(
            enabled = lastTicket != null,
            onClick = {
                val lt = lastTicket!!
                status = "Printing copy of ${lt.number}..."
                TicketPrinter.printTicket(context, store, lt, "** DUPLICATE COPY **") { msg ->
                    onMain { status = if (msg.startsWith("ERR:")) msg else "Copy of ${lt.number} printed." }
                }
            },
            modifier = mod
        ) { Text("PRINT COPY", fontWeight = FontWeight.Bold) }
    }

    val f = from
    val t = to
    val ready = f != null && t != null && f != t

    Section("JOURNEY") {
        StopPicker("From", from, { from = it }) { onGps(0) }
        StopPicker("To", to, { to = it }) { onGps(1) }
        if (f != null && t != null && f == t) {
            Text("Start and destination are the same.", color = MaterialTheme.colorScheme.error)
        }
    }

    Section("PASSENGERS & PAYMENT") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Passengers", Modifier.weight(1f), fontSize = 16.sp)
            FilledTonalIconButton({ if (passengers > 1) passengers-- }) { Text("-", fontSize = 20.sp) }
            Text("$passengers", Modifier.width(48.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center)
            FilledTonalIconButton({ passengers++ }) { Text("+", fontSize = 20.sp) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Copies of ticket", Modifier.weight(1f), fontSize = 16.sp)
            FilledTonalIconButton({ if (copies > 1) copies-- }) { Text("-", fontSize = 20.sp) }
            Text("$copies", Modifier.width(48.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center)
            FilledTonalIconButton({ if (copies < 10) copies++ }) { Text("+", fontSize = 20.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PAYMENT_MODES.forEach { m ->
                FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m) })
            }
        }
    }

    if (ready) {
        val calcFare = store.fare(f!!, t!!)
        var fareText by remember(f, t) { mutableStateOf(calcFare.toString()) }
        val fare = fareText.toIntOrNull() ?: 0
        OutlinedTextField(
            value = fareText,
            onValueChange = { fareText = it.filter(Char::isDigit).take(5) },
            label = { Text("Fare per passenger (Rs) - editable") },
            supportingText = { Text((if (store.hasCustomFare(f, t)) "Route price: Rs. " else "Calculated: Rs. ") + calcFare) },
            trailingIcon = {
                if (fare != calcFare) TextButton({ fareText = calcFare.toString() }) { Text("Reset") }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${f.name}  to  ${t.name}", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(String.format(Locale.US, "%.0f km  |  Rs. %d x %d", roadKm(f, t), fare, passengers),
                    color = MaterialTheme.colorScheme.secondary)
                Text("Rs. ${fare * passengers}", fontSize = 40.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                Text("Ticket no: $nextLabel", fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
            }
        }
        val total = fare * passengers
        var givenText by remember(f, t, mode) { mutableStateOf("") }
        val given = givenText.toIntOrNull() ?: 0
        if (mode == "Cash") {
            Section("CASH CALCULATOR") {
                OutlinedTextField(
                    value = givenText,
                    onValueChange = { givenText = it.filter(Char::isDigit).take(6) },
                    label = { Text("Cash given (Rs)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AssistChip({ givenText = total.toString() }, { Text("Exact") })
                    listOf(50, 100, 200, 500).forEach { n ->
                        AssistChip({ givenText = n.toString() }, { Text("$n") })
                    }
                }
                if (given > 0) {
                    if (given >= total) {
                        Text("Return change: Rs. ${given - total}", fontSize = 26.sp, fontWeight = FontWeight.Bold,
                            color = Color(0xFF2E7D32))
                    } else {
                        Text("Short by Rs. ${total - given}", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = fare > 0 && !(mode == "Cash" && given in 1 until total),
            onClick = {
                val ticket = store.issue(f, t, passengers, mode, fare, if (mode == "Cash") given else 0)
                givenText = ""
                lastTicket = ticket
                nextLabel = store.formatNumber(store.nextNumber)
                val n = copies
                fun printCopy(i: Int) {
                    status = if (n > 1) "Printing ${ticket.number} - copy $i of $n..." else "Printing ${ticket.number}..."
                    TicketPrinter.printTicket(context, store, ticket, if (n > 1) "COPY $i of $n" else null) { msg ->
                        onMain {
                            when {
                                msg.startsWith("ERR:") && i == 1 -> {
                                    store.revoke(ticket)
                                    lastTicket = store.tickets().lastOrNull { !it.cancelled }
                                    nextLabel = store.formatNumber(store.nextNumber)
                                    status = msg
                                }
                                msg.startsWith("ERR:") ->
                                    status = "ERR:Printed ${i - 1} of $n copies of ${ticket.number}. ${msg.removePrefix("ERR:")}"
                                i < n -> printCopy(i + 1)
                                else -> status = if (n > 1) "Ticket ${ticket.number} printed ($n copies)." else msg
                            }
                        }
                    }
                }
                printCopy(1)
            },
            modifier = Modifier.weight(2f).height(56.dp)
        ) { Text("PRINT TICKET", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        copyButton(Modifier.weight(1f).height(56.dp))
        }
    } else if (f == null || t == null) {
        Text("Choose From and To (or tap GPS) to see the fare.", color = MaterialTheme.colorScheme.secondary)
        copyButton(Modifier.fillMaxWidth().height(52.dp))
    }

    StatusText(status)
}

@Composable
fun CancelScreen(store: Store) {
    val context = LocalContext.current
    var tickets by remember { mutableStateOf(store.tickets().filterNot { it.cancelled }.reversed().take(20)) }
    var status by remember { mutableStateOf("") }
    val df = remember { SimpleDateFormat("dd-MM HH:mm", Locale.US) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Pick a recent ticket to cancel. The full amount is refunded and a cancellation receipt is printed.",
            color = MaterialTheme.colorScheme.secondary)
        StatusText(status)
        if (tickets.isEmpty()) Text("No active tickets.")
        tickets.forEach { t ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${t.number}  -  ${df.format(Date(t.time))}", fontWeight = FontWeight.SemiBold)
                    Text("${t.from} to ${t.to}  |  ${t.pax} pax  |  ${t.mode}")
                    Text("Rs. ${t.total}", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    Button(colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = {
                        val c = t.copy(cancelled = true, cancelTime = System.currentTimeMillis())
                        status = "Printing cancellation..."
                        TicketPrinter.printCancel(context, store, c) { msg ->
                            onMain {
                                if (!msg.startsWith("ERR:")) {
                                    store.markCancelled(t)
                                    tickets = tickets.filterNot { it.number == t.number && it.time == t.time }
                                }
                                status = msg
                            }
                        }
                    }) { Text("Cancel & Print") }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(store: Store) {
    val context = LocalContext.current
    var testMsg by remember { mutableStateOf("") }
    var operator by remember { mutableStateOf(store.operatorName) }
    var bus by remember { mutableStateOf(store.busNumber) }
    var conductor by remember { mutableStateOf(store.conductorId) }
    var gstin by remember { mutableStateOf(store.gstin) }
    var prefix by remember { mutableStateOf(store.prefix) }
    var auto by remember { mutableStateOf(store.autoCount) }
    var next by remember { mutableStateOf(store.nextNumber.toString()) }
    var rate by remember { mutableStateOf(store.ratePerKm.toString()) }
    var minFare by remember { mutableStateOf(store.minFare.toString()) }
    var feed by remember { mutableStateOf(store.feedLines.toString()) }
    var top by remember { mutableStateOf(store.topLines.toString()) }
    var rFrom by remember { mutableStateOf<Stop?>(null) }
    var rTo by remember { mutableStateOf<Stop?>(null) }
    var rPrice by remember { mutableStateOf("") }
    var fares by remember { mutableStateOf(store.customFares()) }
    var saved by remember { mutableStateOf(false) }

    Section("BUS DETAILS (printed on ticket)") {
        OutlinedTextField(operator, { operator = it; saved = false }, label = { Text("Company name") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(bus, { bus = it; saved = false }, label = { Text("Bus number") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(conductor, { conductor = it; saved = false }, label = { Text("Conductor ID") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(gstin, { gstin = it; saved = false }, label = { Text("GSTIN (optional)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
    }

    Section("DEFAULT RATE (routes without a set price)") {
        OutlinedTextField(rate, { rate = it.filter { c -> c.isDigit() || c == '.' }; saved = false },
            label = { Text("Rate per km (Rs)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(minFare, { minFare = it.filter(Char::isDigit); saved = false },
            label = { Text("Minimum fare (Rs)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth())
    }

    Section("ROUTE PRICES (same both ways, saved instantly)") {
        Text("Set a fixed price for any pair, e.g. A to B Rs 10, A to C Rs 25, A to D Rs 40.",
            color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp)
        StopPicker("From", rFrom, { rFrom = it })
        StopPicker("To", rTo, { rTo = it })
        OutlinedTextField(rPrice, { rPrice = it.filter(Char::isDigit).take(5) },
            label = { Text("Price (Rs)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth())
        val a = rFrom
        val b = rTo
        val p = rPrice.toIntOrNull() ?: 0
        Button(
            enabled = a != null && b != null && a != b && p > 0,
            onClick = {
                store.setCustomFare(a!!.name, b!!.name, p)
                fares = store.customFares()
                rPrice = ""
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("ADD / UPDATE PRICE") }
        if (fares.isNotEmpty()) HorizontalDivider()
        fares.toSortedMap().forEach { (k, price) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(k.replace("|", "  <->  "), Modifier.weight(1f))
                Text("Rs. $price", fontWeight = FontWeight.Bold)
                TextButton({ store.removeCustomFare(k); fares = store.customFares() }) { Text("Delete") }
            }
        }
    }

    Section("PRINTER") {
        OutlinedTextField(top, { top = it.filter(Char::isDigit).take(2); saved = false },
            label = { Text("Top padding (blank lines before logo)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(feed, { feed = it.filter(Char::isDigit).take(2); saved = false },
            label = { Text("Bottom padding (blank lines before dotted line)") }, singleLine = true,
            supportingText = { Text("Increase if the last line is not pushed out of the printer") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth())
    }

    Section("PRINT PREVIEW (paper, to scale of lines)") {
        val topN = (top.toIntOrNull() ?: 0).coerceIn(0, 30)
        val feedN = (feed.toIntOrNull() ?: 0).coerceIn(0, 30)
        val lineH = 14.dp
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("PRINTER HEAD  (paper feeds down)", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
            Surface(color = Color.White, shadowElevation = 3.dp, modifier = Modifier.width(230.dp)) {
                Column(Modifier.padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(lineH * topN).background(Color(0xFFFFF3E0)),
                        contentAlignment = Alignment.Center) {
                        if (topN > 0) Text("top: $topN blank", fontSize = 10.sp, color = Color(0xFFE8890C))
                    }
                    Image(painterResource(R.drawable.bus_logo), null, Modifier.size(54.dp))
                    Text(operator.ifBlank { "OASYS BUS" }, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.Black)
                    Text("BUS TICKET", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    Text("Ticket No: ${prefix}000001", fontSize = 11.sp, color = Color.Black)
                    Text("From : Chennai", fontSize = 11.sp, color = Color.Black)
                    Text("To   : Madurai", fontSize = 11.sp, color = Color.Black)
                    Text("TOTAL: Rs. 000", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    Text("Thank you. Happy journey!", fontSize = 10.sp, color = Color.Black)
                    Box(Modifier.fillMaxWidth().height(lineH * feedN).background(Color(0xFFE3F2FD)),
                        contentAlignment = Alignment.Center) {
                        if (feedN > 0) Text("bottom: $feedN blank", fontSize = 10.sp, color = Color(0xFF1B3A5C))
                    }
                    Text("- - - - - - - - - - - - - - - -", fontSize = 10.sp, color = Color.Black)
                }
            }
        }
        Button(onClick = {
            store.topLines = topN
            store.feedLines = feedN
            val a = STOPS.first { it.name == "Chennai" }
            val b = STOPS.first { it.name == "Madurai" }
            val sample = Ticket("TEST", a.name, b.name, roadKm(a, b).toInt(), 1, 0, "Cash", System.currentTimeMillis())
            testMsg = "Printing test..."
            TicketPrinter.printTicket(context, store, sample) { msg -> onMain { testMsg = msg } }
        }, modifier = Modifier.fillMaxWidth()) { Text("TEST PRINT (saves top & bottom padding)") }
        StatusText(testMsg)
    }

    Section("TICKET NUMBERING") {
        OutlinedTextField(prefix, { prefix = it; saved = false }, label = { Text("Ticket prefix") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Auto count (+1 after each ticket)", Modifier.weight(1f))
            Switch(auto, { auto = it; saved = false })
        }
        OutlinedTextField(next, { next = it.filter(Char::isDigit); saved = false },
            label = { Text("Next ticket number (count from)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth())
        Text("Next ticket: ${prefix}${(next.toLongOrNull() ?: 1L).toString().padStart(6, '0')}" +
            if (auto) "" else "  (auto count off - number stays fixed)",
            color = MaterialTheme.colorScheme.secondary)
    }

    Button(onClick = {
        store.operatorName = operator.trim()
        store.busNumber = bus.trim()
        store.conductorId = conductor.trim()
        store.gstin = gstin.trim()
        store.prefix = prefix.trim()
        store.topLines = (top.toIntOrNull() ?: 0).coerceIn(0, 30)
        store.feedLines = (feed.toIntOrNull() ?: 10).coerceIn(0, 30)
        store.ratePerKm = rate.toFloatOrNull() ?: store.ratePerKm
        store.minFare = minFare.toIntOrNull() ?: store.minFare
        store.autoCount = auto
        store.nextNumber = (next.toLongOrNull() ?: 1L).coerceAtLeast(1)
        saved = true
    }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("SAVE SETTINGS", fontWeight = FontWeight.Bold) }
    if (saved) StatusText("Settings saved. Company name shows after you return to Issue.")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopPicker(label: String, value: Stop?, onSelect: (Stop) -> Unit, onGps: (() -> Unit)? = null) {
    var expanded by remember { mutableStateOf(false) }
    var text by remember(value) { mutableStateOf(value?.name ?: "") }
    // Filter only while the user is typing; otherwise a selected name would hide every other stop.
    var typing by remember { mutableStateOf(false) }
    val matches = if (typing) STOPS.filter { it.name.contains(text, ignoreCase = true) } else STOPS

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposedDropdownMenuBox(expanded, { expanded = it; if (it) typing = false }, Modifier.weight(1f)) {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    typing = true
                    expanded = true
                    STOPS.firstOrNull { s -> s.name.equals(it.trim(), ignoreCase = true) }?.let(onSelect)
                },
                label = { Text(label) },
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(expanded, { expanded = false }) {
                (if (matches.isEmpty()) STOPS else matches).forEach { s ->
                    DropdownMenuItem(text = { Text(s.name) }, onClick = {
                        text = s.name
                        typing = false
                        onSelect(s)
                        expanded = false
                    })
                }
            }
        }
        if (onGps != null) FilledTonalButton(onGps) { Text("GPS") }
    }
}
