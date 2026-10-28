package com.tnbus.ticketing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Prints to the built-in thermal printer of the OASYS P10 through the system library
 * com.pos.sdk (POIPrinterManager). Accessed by reflection so the app still builds and runs
 * on devices that do not have the library.
 */
object TicketPrinter {
    private const val PKG = "com.pos.sdk.printer."
    private const val DASH = "--------------------------------"

    private class Line(
        val text: String, val size: Float = 24f, val center: Boolean = false, val bold: Boolean = false,
        val bitmap: Bitmap? = null,
    )

    private fun fmt(ms: Long) = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.US).format(Date(ms))

    /** Logo scaled to the 58mm paper and thresholded to pure black/white for the thermal head. */
    private fun logo(context: Context): Bitmap? {
        val src = BitmapFactory.decodeResource(context.resources, R.drawable.bus_logo) ?: return null
        val w = 110
        val h = src.height * w / src.width
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
            px[i] = if (lum < 128) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun header(context: Context, s: Store, out: MutableList<Line>) {
        repeat(s.topLines) { out += Line(" ", 24f, true) }
        logo(context)?.let { out += Line("", center = true, bitmap = it) }
        out += Line(s.operatorName.ifBlank { "OASYS BUS" }, 32f, true, true)
        if (s.gstin.isNotBlank()) out += Line("GSTIN: ${s.gstin}", 22f, true)
    }

    private fun busInfo(s: Store, out: MutableList<Line>) {
        if (s.busNumber.isNotBlank()) out += Line("Bus No : ${s.busNumber}")
        if (s.conductorId.isNotBlank()) out += Line("Cond ID: ${s.conductorId}")
    }

    fun printTicket(context: Context, s: Store, t: Ticket, copy: String? = null, onDone: (String) -> Unit) {
        val l = mutableListOf<Line>()
        header(context, s, l)
        l += Line("BUS TICKET", 26f, true, true)
        if (copy != null) l += Line(copy, 22f, true)
        l += Line(DASH, 22f, true)
        l += Line("Ticket No: ${t.number}", 26f, bold = true)
        l += Line("Date/Time: ${fmt(t.time)}")
        busInfo(s, l)
        l += Line(DASH, 22f, true)
        l += Line("From : ${t.from}", 26f)
        l += Line("To   : ${t.to}", 26f)
        l += Line("Dist : ${t.km} km")
        l += Line("Passengers: ${t.pax}")
        l += Line("Fare/Pax  : Rs. ${t.fare}")
        l += Line(DASH, 22f, true)
        l += Line("TOTAL: Rs. ${t.total}", 34f, true, true)
        l += Line("Paid by: ${t.mode}", 24f, true)
        if (t.mode == "Cash" && t.given > 0) {
            l += Line("Cash given: Rs. ${t.given}", 24f, true)
            l += Line("Change    : Rs. ${t.given - t.total}", 24f, true, true)
        }
        l += Line(DASH, 22f, true)
        l += Line("Non-transferable. Keep this", 20f, true)
        l += Line("ticket until journey ends.", 20f, true)
        l += Line("Thank you. Happy journey!", 22f, true)
        run(context, l, s.feedLines, onDone, "Ticket ${t.number} printed.")
    }

    fun printCancel(context: Context, s: Store, t: Ticket, onDone: (String) -> Unit) {
        val l = mutableListOf<Line>()
        header(context, s, l)
        l += Line("** CANCELLED **", 30f, true, true)
        l += Line("CANCELLATION RECEIPT", 24f, true, true)
        l += Line(DASH, 22f, true)
        l += Line("Ticket No: ${t.number}", 26f, bold = true)
        l += Line("Issued   : ${fmt(t.time)}")
        l += Line("Cancelled: ${fmt(t.cancelTime)}")
        busInfo(s, l)
        l += Line(DASH, 22f, true)
        l += Line("${t.from} to ${t.to}", 24f)
        l += Line("Passengers: ${t.pax}")
        l += Line("Paid by: ${t.mode}")
        l += Line(DASH, 22f, true)
        l += Line("REFUND: Rs. ${t.total}", 32f, true, true)
        l += Line(DASH, 22f, true)
        l += Line("This ticket is no longer valid.", 20f, true)
        run(context, l, s.feedLines, onDone, "Cancellation of ${t.number} printed.")
    }

    private fun run(context: Context, lines: List<Line>, feed: Int, onDone: (String) -> Unit, okMsg: String) {
        try {
            val managerCls = Class.forName(PKG + "POIPrinterManager")
            val lineCls = Class.forName(PKG + "models.PrintLine")
            val textCls = Class.forName(PKG + "models.TextPrintLine")
            val listenerCls = Class.forName(PKG + "POIPrinterManager\$IPrinterListener")

            val centerPos = lineCls.getField("CENTER").getInt(null)
            val leftPos = lineCls.getField("LEFT").getInt(null)
            val manager = managerCls.getConstructor(Context::class.java).newInstance(context)

            managerCls.getMethod("open").invoke(manager)
            val bitmapCls = Class.forName(PKG + "models.BitmapPrintLine")
            // Padding is printed as blank lines and the last line printed is the tear line, so the
            // head really prints (and feeds paper) all the way to the tear point.
            val all = lines + List(feed) { Line(" ", 24f, true) } + Line("- - - - - - - - - - - - - - - -", 22f, true)
            for (ln in all) {
                if (ln.bitmap != null) {
                    val b = bitmapCls.getConstructor(Bitmap::class.java).newInstance(ln.bitmap)
                    lineCls.getMethod("setPosition", Int::class.javaPrimitiveType).invoke(b, centerPos)
                    managerCls.getMethod("addPrintLine", lineCls).invoke(manager, b)
                    continue
                }
                val line = textCls.getConstructor().newInstance()
                textCls.getMethod("setContent", String::class.java).invoke(line, ln.text)
                textCls.getMethod("setSize", Float::class.javaPrimitiveType).invoke(line, ln.size)
                textCls.getMethod("setBold", Boolean::class.javaPrimitiveType).invoke(line, ln.bold)
                lineCls.getMethod("setPosition", Int::class.javaPrimitiveType)
                    .invoke(line, if (ln.center) centerPos else leftPos)
                managerCls.getMethod("addPrintLine", lineCls).invoke(manager, line)
            }
            managerCls.getMethod("lineWrap", Int::class.javaPrimitiveType).invoke(manager, 1)

            val close = { runCatching { managerCls.getMethod("close").invoke(manager) } }
            val listener = Proxy.newProxyInstance(
                listenerCls.classLoader, arrayOf(listenerCls),
                InvocationHandler { _, method, args ->
                    when (method.name) {
                        "onFinish" -> { close(); onDone(okMsg) }
                        "onError" -> { close(); onDone("ERR:Print error ${args?.get(0)}: ${args?.get(1)}") }
                    }
                    null
                })
            managerCls.getMethod("beginPrint", listenerCls).invoke(manager, listener)
        } catch (e: ClassNotFoundException) {
            onDone("ERR:Printer not available on this device.")
        } catch (e: Exception) {
            val cause = e.cause ?: e
            onDone("ERR:Print failed: ${cause.javaClass.simpleName}: ${cause.message}")
        }
    }
}
