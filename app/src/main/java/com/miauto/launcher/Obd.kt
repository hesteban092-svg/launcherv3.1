package com.miauto.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Conexión a un ELM327 por Bluetooth (SPP) o WiFi (TCP). */
class ElmLink {
    private var bt: BluetoothSocket? = null
    private var tcp: Socket? = null
    private var ins: InputStream? = null
    private var outs: OutputStream? = null

    @SuppressLint("MissingPermission")
    fun open(type: String, target: String) {
        if (type == "wifi") {
            val host = target.substringBefore(":").ifBlank { "192.168.0.10" }
            val port = target.substringAfter(":", "35000").toIntOrNull() ?: 35000
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 4000)
            tcp = s; ins = s.getInputStream(); outs = s.getOutputStream()
        } else {
            val ctx = Obd.appCtx
            if (ctx != null && Build.VERSION.SDK_INT >= 31 &&
                ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) throw IOException("Falta permiso de Bluetooth")
            val ad = BluetoothAdapter.getDefaultAdapter() ?: throw IOException("Sin Bluetooth")
            if (!ad.isEnabled) throw IOException("Bluetooth apagado")
            var found = findBonded(ad, target)
            if (found == null && ctx != null) {          // no emparejado: intenta emparejarlo solo
                elmTryPair(ctx, ad)
                found = findBonded(ad, target)
            }
            val dev = found ?: throw IOException("ELM no emparejado (emparéjalo en Ajustes Android)")
            ad.cancelDiscovery()
            val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
            val attempts = listOf<() -> BluetoothSocket>(
                { dev.createInsecureRfcommSocketToServiceRecord(uuid) },
                { dev.createRfcommSocketToServiceRecord(uuid) },
                { dev.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType).invoke(dev, 1) as BluetoothSocket },
                { dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(dev, 1) as BluetoothSocket }
            )
            var s: BluetoothSocket? = null
            var err: Exception? = null
            for (mk in attempts) {
                var c: BluetoothSocket? = null
                try {
                    c = mk()
                    c.connect()
                    s = c
                    break
                } catch (e: Exception) {
                    err = e
                    try { c?.close() } catch (_: Exception) {}
                    Thread.sleep(600)
                }
            }
            if (s == null) throw IOException(err?.message ?: "no conecta")
            bt = s; ins = s.inputStream; outs = s.outputStream
        }
    }

    @SuppressLint("MissingPermission")
    private fun findBonded(ad: BluetoothAdapter, target: String): BluetoothDevice? {
        val bonded = ad.bondedDevices ?: return null
        if (target.isNotBlank()) {
            bonded.firstOrNull { (it.name ?: "").equals(target, true) || it.address.equals(target, true) }?.let { return it }
        }
        return bonded.firstOrNull {
            val n = it.name ?: ""
            n.contains("OBD", true) || n.contains("ELM", true) || n.contains("V-LINK", true) || n.contains("VLINK", true)
        }
    }

    /** Envía un comando y espera hasta el prompt '>' (máx. 4 s). */
    fun send(cmd: String): String {
        val o = outs ?: throw IOException("sin conexión")
        val i = ins ?: throw IOException("sin conexión")
        while (i.available() > 0) i.read()
        o.write((cmd + "\r").toByteArray()); o.flush()
        val sb = StringBuilder()
        val end = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < end) {
            if (i.available() > 0) {
                val ch = i.read().toChar()
                if (ch == '>') break
                sb.append(ch)
            } else Thread.sleep(15)
        }
        return sb.toString().replace('\r', '\n').trim()
    }

    fun close() {
        try { bt?.close() } catch (_: Exception) {}
        try { tcp?.close() } catch (_: Exception) {}
        bt = null; tcp = null; ins = null; outs = null
    }
}

/** Intenta emparejar solo un ELM327 cercano (PIN 1234). Mejor esfuerzo; si falla, se empareja a mano. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
fun elmTryPair(ctx: Context, ad: BluetoothAdapter) {
    if (Build.VERSION.SDK_INT >= 31 &&
        ctx.checkSelfPermission("android.permission.BLUETOOTH_SCAN") != PackageManager.PERMISSION_GRANTED
    ) return
    val done = CountDownLatch(1)
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            when (i.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val n = d.name ?: ""
                    if (n.contains("OBD", true) || n.contains("ELM", true) || n.contains("V-LINK", true)) {
                        ad.cancelDiscovery(); d.createBond()
                    }
                }
                "android.bluetooth.device.action.PAIRING_REQUEST" -> {
                    try { d.setPin("1234".toByteArray()) } catch (_: Exception) {}
                    try { abortBroadcast() } catch (_: Exception) {}
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED ->
                    if (d.bondState == BluetoothDevice.BOND_BONDED) done.countDown()
            }
        }
    }
    val f = IntentFilter().apply {
        addAction(BluetoothDevice.ACTION_FOUND)
        addAction("android.bluetooth.device.action.PAIRING_REQUEST")
        addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
    }
    try {
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        else ctx.registerReceiver(receiver, f)
        ad.startDiscovery()
        done.await(25, TimeUnit.SECONDS)
    } catch (_: Exception) {
    } finally {
        try { ad.cancelDiscovery() } catch (_: Exception) {}
        try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
    }
}

/** Evalúa fórmulas tipo Torque: A-40, (A*256+B)/4, etc. */
object Formula {
    fun eval(f: String, vars: Map<Char, Double>): Double = P(f.replace(" ", ""), vars).expr()

    private class P(val s: String, val vars: Map<Char, Double>) {
        var i = 0
        fun expr(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]; val t = term(); v = if (op == '+') v + t else v - t
            }
            return v
        }
        fun term(): Double {
            var v = factor()
            while (i < s.length && (s[i] == '*' || s[i] == '/')) {
                val op = s[i++]; val t = factor(); v = if (op == '*') v * t else v / t
            }
            return v
        }
        fun factor(): Double {
            if (i >= s.length) throw IllegalArgumentException("fórmula")
            val c = s[i]
            if (c == '-') { i++; return -factor() }
            if (c == '(') { i++; val v = expr(); i++; return v }
            if (c.isLetter()) { i++; return vars[c.uppercaseChar()] ?: throw IllegalArgumentException("byte") }
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(st, i).toDouble()
        }
    }
}

/**
 * Lee PIDs en segundo plano. Reglas (una por línea):
 *   NOMBRE|header|comando|fórmula     (NOMBRE: COOLANT, ATF)
 * Ejemplo: COOLANT||0105|A-40      ATF|7E1|221286|A-40
 */
object Obd {
    data class Pid(val name: String, val header: String, val cmd: String, val formula: String)

    val values = HashMap<String, Double?>()
    /** Ultima respuesta cruda de cada PID (solo para diagnostico en pantalla). */
    val raw = HashMap<String, String>()
    fun rawLine(): String =
        if (raw.isEmpty()) "" else "\n" + raw.entries.joinToString("   ") { "${it.key}: ${it.value}" }
    @Volatile var status = "ELM: sin iniciar"
    val listeners = ArrayList<() -> Unit>()
    @Volatile private var running = false
    private var thread: Thread? = null

    val DEFAULT_PIDS = "COOLANT|7E1|0105|A-40\n" +
        "# ATF: lo fija la TCM al arrancar el motor (no es lectura continua)\n" +
        "ATF|7E1|221286|A-40"

    @Volatile var appCtx: Context? = null

    fun parse(text: String): List<Pid> = text.lines().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull {
            val p = it.split("|").map { s -> s.trim() }
            if (p.size >= 4) Pid(p[0].uppercase(), p[1].uppercase(), p[2].uppercase(), p[3]) else null
        }

    private fun fireUpdate() = listeners.toList().forEach { it() }
    private fun publish(s: String) { status = s; fireUpdate() }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    fun start(ctx: Context) {
        stop()
        val app = ctx.applicationContext
        appCtx = app
        val type = Prefs.elmType(app)
        val target = Prefs.elmTarget(app)
        val pids = parse(Prefs.pids(app))
        if (pids.isEmpty()) { publish("ELM: sin PIDs"); return }
        if (type == "wifi" && target.isBlank()) { publish("ELM: sin configurar"); return }
        running = true
        thread = Thread {
            while (running) {
                val link = ElmLink()
                try {
                    publish("ELM: conectando…")
                    link.open(type, target)
                    for (c in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP6", "ATCAF1", "ATAT1")) link.send(c)
                    publish("ELM: conectado")
                    var header = ""
                    var silent = 0
                    while (running) {
                        var blank = true
                        for (p in pids) {
                            if (p.header != header) {
                                if (p.header.isEmpty()) {
                                    link.send("ATSH7DF"); link.send("ATAR")
                                } else {
                                    link.send("ATSH" + p.header)
                                    val rid = p.header.toIntOrNull(16)?.plus(8)
                                    if (p.header.length == 3 && rid != null) link.send("ATCRA" + String.format("%03X", rid))
                                }
                                header = p.header
                            }
                            var resp = link.send(p.cmd)
                            if (resp.contains("NO DATA")) resp = link.send(p.cmd)
                            raw[p.name] = resp.replace("\n", " | ").take(28).ifBlank { "(vacio)" }
                            if (resp.isNotBlank()) blank = false
                            val v = decode(p, resp)
                            // El ATF se conserva (la TCM lo fija al arrancar); el resto se borra si no hay dato.
                            if (v != null || p.name != "ATF") values[p.name] = v
                        }
                        // Si el ELM no devuelve nada, ni "NO DATA", varias veces seguidas: la conexion murio.
                        if (blank) { if (++silent >= 4) throw IOException("ELM sin respuesta") } else silent = 0
                        val st = if (pids.any { values[it.name] != null }) "ELM: conectado"
                                 else "ELM: conectado, sin datos de los PIDs"
                        if (st != status) publish(st) else fireUpdate()
                        Thread.sleep(1500)
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    publish("ELM: " + (e.message ?: e.javaClass.simpleName).take(45) + " (reintento)")
                    try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
                } finally {
                    link.close()
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun decode(p: Pid, resp: String): Double? {
        try {
            val svc = p.cmd.substring(0, 2).toInt(16) + 0x40
            val expected = String.format("%02X", svc) + p.cmd.substring(2)
            val hex = StringBuilder()
            for (raw in resp.uppercase().lines()) {
                val line = raw.substringAfter(':').replace(" ", "")
                if (line.isNotEmpty() && line.all { it in "0123456789ABCDEF" }) hex.append(line)
            }
            val idx = hex.indexOf(expected)
            if (idx < 0) return null
            val data = hex.substring(idx + expected.length)
            val vars = HashMap<Char, Double>()
            var n = 0
            while (n * 2 + 2 <= data.length && n < 8) {
                vars['A' + n] = data.substring(n * 2, n * 2 + 2).toInt(16).toDouble(); n++
            }
            return Formula.eval(p.formula, vars)
        } catch (_: Exception) { return null }
    }
}
