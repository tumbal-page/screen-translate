package com.example.screentranslator

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.CopyOnWriteArrayList

object AppLog {
    private const val MAX = 500
    val entries = CopyOnWriteArrayList<String>()

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        add("D/$tag: $msg")
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e(tag, msg, t)
        add("E/$tag: $msg${t?.let { " | ${it.message}" } ?: ""}")
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        add("I/$tag: $msg")
    }

    private fun add(line: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
            .format(java.util.Date())
        entries.add("[$ts] $line")
        if (entries.size > MAX) entries.removeAt(0)
    }
}

class LogServer(port: Int = 8765) : NanoHTTPD(port) {
    override fun serve(session: IHTTPSession): Response {
        val logs = AppLog.entries.joinToString("\n")
        return when (session.uri) {
            "/logs" -> newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", logs)
            else -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", """
                <!DOCTYPE html><html><head>
                <meta charset="utf-8">
                <title>App Log</title>
                <style>body{background:#111;color:#0f0;font-family:monospace;font-size:12px;padding:8px}
                pre{white-space:pre-wrap;word-break:break-all}</style>
                <script>
                function refresh(){fetch('/logs').then(r=>r.text()).then(t=>{
                  document.getElementById('log').textContent=t;
                  window.scrollTo(0,document.body.scrollHeight);
                });}
                setInterval(refresh,1000);window.onload=refresh;
                </script></head>
                <body><pre id="log">Loading...</pre></body></html>
            """.trimIndent())
        }
    }
}
