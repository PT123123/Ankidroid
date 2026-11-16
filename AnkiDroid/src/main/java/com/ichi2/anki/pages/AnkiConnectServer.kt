// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Anki Plus · AnkiConnect-compatible local API (port of
// the foosoft/anki-connect desktop add-on), 2026

package com.ichi2.anki.pages

import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException

/**
 * AnkiConnect-compatible HTTP endpoint: POST JSON
 * `{"action", "version", "params"}` answered as `{"result", "error"}`.
 * Bound to 127.0.0.1 only, so tools on the device (or over `adb forward`)
 * can reach it while nothing on the LAN can. CORS headers are present because
 * upstream clients (Yomichan) run from browser origins.
 *
 * Lifecycle is owned by [AnkiConnectManager]: started with the DeckPicker and
 * stopped on its destruction, restarted immediately when the user flips the
 * settings toggle.
 */
class AnkiConnectServer(
    port: Int,
) : NanoHTTPD(LOCALHOST, port) {
    fun baseUrl(): String = "http://$LOCALHOST:$listeningPort/"

    override fun serve(session: IHTTPSession): Response =
        when (session.method) {
            Method.OPTIONS -> corsResponse(newFixedLengthResponse(Response.Status.NO_CONTENT, null, null))
            Method.GET ->
                corsResponse(
                    newFixedLengthResponse(Response.Status.OK, "text/html", WELCOME_PAGE),
                )
            Method.POST -> handlePost(session)
            else -> newFixedLengthResponse(null)
        }

    private fun handlePost(session: IHTTPSession): Response {
        val body =
            try {
                JSONObject(String(readBody(session), Charsets.UTF_8))
            } catch (exception: Exception) {
                Timber.w(exception, "ankiconnect: malformed request")
                return errorResponse("invalid request: ${exception.message}")
            }

        val action = body.optString("action")
        val reply = JSONObject()
        try {
            // getLong returns Long; comparing it to the Int VERSION with != is
            // always true (boxed types differ), so widen the constant instead
            val version = if (body.has("version")) body.getLong("version") else VERSION.toLong()
            if (version != VERSION.toLong()) {
                throw IllegalArgumentException("unsupported version: $version (use $VERSION)")
            }
            val params = body.optJSONObject("params") ?: JSONObject()
            val result = runBlocking { AnkiConnectDispatcher.dispatch(action, params) }
            reply.put("result", result)
            reply.put("error", JSONObject.NULL)
        } catch (exception: Exception) {
            Timber.w(exception, "ankiconnect: action %s failed", action)
            reply.put("result", JSONObject.NULL)
            reply.put("error", exception.message ?: exception.toString())
        }
        return corsResponse(
            newFixedLengthResponse(Response.Status.OK, "application/json", reply.toString()),
        )
    }

    /**
     * Reads exactly Content-Length bytes. A single InputStream.read on a socket
     * routinely returns short reads for large bodies (storeMediaFile uploads),
     * which would truncate the JSON — so loop until the body is complete.
     */
    private fun readBody(session: IHTTPSession): ByteArray {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        val bytes = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val read = session.inputStream.read(bytes, offset, contentLength - offset)
            if (read < 0) break
            offset += read
        }
        return bytes
    }

    private fun errorResponse(message: String): Response {
        val reply = JSONObject().put("result", JSONObject.NULL).put("error", message)
        return corsResponse(
            newFixedLengthResponse(Response.Status.OK, "application/json", reply.toString()),
        )
    }

    private fun corsResponse(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Headers", "*")
        response.addHeader("Access-Control-Allow-Methods", "POST, OPTIONS")
        return response
    }

    companion object {
        const val LOCALHOST = "127.0.0.1"
        const val VERSION = AnkiConnectDispatcher.VERSION

        private const val WELCOME_PAGE =
            "<html><head><title>anki-plus AnkiConnect</title></head><body>" +
                "<h1>AnkiConnect</h1>" +
                "<p>POST JSON to this endpoint, e.g. " +
                "<code>{\"action\": \"version\", \"version\": 6}</code></p></body></html>"
    }
}

/**
 * Owns the single running [AnkiConnectServer]. Started with the DeckPicker and
 * stopped on its destruction; the settings toggle calls [onEnabledChanged] /
 * [onPortChanged] directly. Failures never crash the app — they only log.
 */
object AnkiConnectManager {
    private var server: AnkiConnectServer? = null

    fun isRunning(): Boolean = server != null

    /** DeckPicker lifecycle entry: reads the persisted settings. */
    fun start() {
        if (!enabledInPrefs()) {
            Timber.i("ankiconnect: disabled by user")
            return
        }
        bind(portFromPrefs())
    }

    fun stop() {
        server?.stop()
        server = null
    }

    // Preference change listeners fire BEFORE the new value is persisted, so
    // they must hand the new value over explicitly — re-reading prefs here
    // would bind the old port / skip the start the user just asked for.

    fun onEnabledChanged(enabled: Boolean) {
        if (enabled) {
            bind(portFromPrefs())
        } else {
            stop()
        }
    }

    fun onPortChanged(port: Int) {
        stop()
        if (enabledInPrefs()) {
            bind(port)
        }
    }

    private fun enabledInPrefs(): Boolean {
        val key = AnkiDroidApp.appResources.getString(R.string.ankiconnect_enable_key)
        return AnkiDroidApp.sharedPrefs().getBoolean(key, true)
    }

    private fun portFromPrefs(): Int {
        val key = AnkiDroidApp.appResources.getString(R.string.ankiconnect_port_key)
        return AnkiDroidApp.sharedPrefs().getString(key, null)?.toIntOrNull() ?: DEFAULT_PORT
    }

    private fun bind(port: Int) {
        if (server != null) {
            return
        }
        try {
            server = AnkiConnectServer(port).also { it.start(SOCKET_READ_TIMEOUT_MILLIS, true) }
            Timber.i("ankiconnect: listening on %s:%d", AnkiConnectServer.LOCALHOST, port)
        } catch (exception: IOException) {
            Timber.w(exception, "ankiconnect: failed to bind port %d", port)
            server = null
        }
    }

    private const val DEFAULT_PORT = 8765
    private const val SOCKET_READ_TIMEOUT_MILLIS = 10_000
}
