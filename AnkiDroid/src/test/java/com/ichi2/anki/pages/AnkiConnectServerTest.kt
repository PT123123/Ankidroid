// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Anki Plus · AnkiConnect HTTP layer gate, 2026

package com.ichi2.anki.pages

import android.content.Context
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import fi.iki.elonen.NanoHTTPD
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * Gate for the HTTP wrapper (AnkiConnectServer) and the settings wiring
 * (AnkiConnectManager). The action semantics live in AnkiConnectDispatcherTest;
 * here we only prove the transport: real sockets, CORS, error envelopes, full
 * body reads, and that preference changes bind the NEW value even though the
 * listener fires before the value is persisted.
 */
@RunWith(AndroidJUnit4::class)
class AnkiConnectServerTest {
    private val http =
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    private fun testContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun post(
        base: String,
        body: String,
    ): JSONObject {
        val request =
            Request
                .Builder()
                .url(base)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        http.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            assertNotNull(response.header("Access-Control-Allow-Origin"))
            return JSONObject(response.body.string())
        }
    }

    @Before
    fun setUp() {
        AnkiDroidApp.sharedPrefs().edit { clear() }
    }

    @After
    fun tearDown() {
        AnkiConnectManager.stop()
        AnkiDroidApp.sharedPrefs().edit { clear() }
    }

    @Test
    fun versionOptionsAndWelcomeOverRealSockets() {
        val server = AnkiConnectServer(0)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            val base = server.baseUrl()
            val reply = post(base, """{"action":"version","version":6}""")
            assertEquals("reply=$reply", 6, reply.optInt("result", -1))
            assertTrue(reply.isNull("error"))

            val preflight =
                http
                    .newCall(
                        Request
                            .Builder()
                            .url(base)
                            .method("OPTIONS", null)
                            .build(),
                    ).execute()
            preflight.use {
                assertEquals(204, it.code)
                assertEquals("*", it.header("Access-Control-Allow-Origin"))
            }

            val welcome =
                http
                    .newCall(
                        Request
                            .Builder()
                            .url(base)
                            .get()
                            .build(),
                    ).execute()
            welcome.use {
                assertEquals(200, it.code)
                assertTrue(it.body.string().contains("AnkiConnect"))
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun malformedBodyAndLegacyVersionAreErrorEnvelopes() {
        val server = AnkiConnectServer(0)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            val base = server.baseUrl()
            val malformed = post(base, "this is not json")
            assertTrue(malformed.isNull("result"))
            assertTrue(malformed.getString("error").contains("invalid request"))

            val legacy = post(base, """{"action":"version","version":5}""")
            assertTrue(legacy.isNull("result"))
            assertTrue(legacy.getString("error").contains("unsupported version"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun largeBodyIsReadToTheEnd() {
        // A single socket read truncates bodies this size; the truncated JSON
        // would surface as "invalid request" instead of the action's result.
        // "version" needs no collection, so a full read answers 6 even here.
        val server = AnkiConnectServer(0)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            val padding = "x".repeat(2 * 1024 * 1024)
            val body =
                JSONObject()
                    .put("action", "version")
                    .put("version", 6)
                    .put("params", JSONObject().put("padding", padding))
                    .toString()
            val reply = post(server.baseUrl(), body)
            assertEquals("reply=${reply.optString("error")}", 6, reply.optInt("result", -1))
            assertTrue(reply.isNull("error"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun portChangeBindsTheNewPortBeforeItIsPersisted() {
        val prefs = AnkiDroidApp.sharedPrefs()
        val portKey = testContext().getString(R.string.ankiconnect_port_key)
        val oldPort = freePort()
        val newPort = freePort()
        prefs.edit { putString(portKey, oldPort.toString()) }

        AnkiConnectManager.start()
        assertTrue(AnkiConnectManager.isRunning())
        assertEquals(6, post("http://127.0.0.1:$oldPort/", """{"action":"version"}""").getInt("result"))

        // the listener contract: prefs still hold the OLD port at this point
        AnkiConnectManager.onPortChanged(newPort)
        assertEquals(oldPort.toString(), prefs.getString(portKey, null))
        assertEquals(6, post("http://127.0.0.1:$newPort/", """{"action":"version"}""").getInt("result"))
        val oldAddress =
            runCatching { post("http://127.0.0.1:$oldPort/", """{"action":"version"}""") }.exceptionOrNull()
        assertTrue("old port must be closed after the change", oldAddress is IOException)
    }

    @Test
    fun enableChangeStartsBeforeItIsPersisted() {
        val prefs = AnkiDroidApp.sharedPrefs()
        val enableKey = testContext().getString(R.string.ankiconnect_enable_key)
        val portKey = testContext().getString(R.string.ankiconnect_port_key)
        val port = freePort()
        prefs.edit {
            putBoolean(enableKey, false)
            putString(portKey, port.toString())
        }

        AnkiConnectManager.start()
        assertFalse(AnkiConnectManager.isRunning())

        // prefs still say disabled; the explicit new value must win
        AnkiConnectManager.onEnabledChanged(true)
        assertTrue(AnkiConnectManager.isRunning())
        assertEquals(6, post("http://127.0.0.1:$port/", """{"action":"version"}""").getInt("result"))

        AnkiConnectManager.onEnabledChanged(false)
        assertFalse(AnkiConnectManager.isRunning())
    }

    @Test
    fun portChangeWhileDisabledDoesNotBind() {
        val prefs = AnkiDroidApp.sharedPrefs()
        val enableKey = testContext().getString(R.string.ankiconnect_enable_key)
        prefs.edit { putBoolean(enableKey, false) }

        AnkiConnectManager.onPortChanged(freePort())
        assertFalse(AnkiConnectManager.isRunning())
    }
}
