package com.example.smartcric

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class SmartTv(val name: String, val ip: String, val type: TvType, val dialAppUrl: String? = null)

enum class TvType { LG_WEBOS, DIAL_DEVICE }

sealed class CastState {
    object Idle : CastState()
    object Discovering : CastState()
    data class Found(val tvs: List<SmartTv>) : CastState()
    data class Connecting(val tv: SmartTv) : CastState()
    data class WaitingForPairing(val tv: SmartTv) : CastState()
    data class Launching(val tv: SmartTv) : CastState()
    object Success : CastState()
    data class Failure(val reason: String) : CastState()
}

object SmartTvManager {
    val stateFlow = MutableStateFlow<CastState>(CastState.Idle)
    private var webSocket: WebSocketClient? = null
    private const val PREFS_NAME = "smart_tv_prefs"
    private const val TAG = "SmartTV"

    fun discoverTvs(context: Context, scope: CoroutineScope) {
        stateFlow.value = CastState.Discovering
        scope.launch {
            val tvs = ssdpDiscover(context)
            stateFlow.value = CastState.Found(tvs)
        }
    }

    fun connectAndCast(tv: SmartTv, streamUrl: String, context: Context, scope: CoroutineScope) {
        when (tv.type) {
            TvType.LG_WEBOS -> connectLgSsap(tv, streamUrl, context)
            TvType.DIAL_DEVICE -> scope.launch { launchViaDial(tv, streamUrl) }
        }
    }

    fun reset() {
        webSocket?.close()
        webSocket = null
        stateFlow.value = CastState.Idle
    }

    private fun connectLgSsap(tv: SmartTv, streamUrl: String, context: Context) {
        stateFlow.value = CastState.Connecting(tv)
        val savedKey = loadClientKey(context, tv.ip)
        webSocket?.close()

        // Try secure WebSocket first (webOS 4.0+ requires wss:// on port 3001)
        val sslUri = URI("wss://${tv.ip}:3001")
        Log.w(TAG, "Connecting to LG TV via WSS: $sslUri")

        webSocket = LgSsapClient(
            uri = sslUri,
            clientKey = savedKey,
            streamUrl = streamUrl,
            tv = tv,
            onStateChange = { stateFlow.value = it },
            onClientKey = { key -> saveClientKey(context, tv.ip, key) },
            onFallbackToPlain = {
                // Fall back to ws:// port 3000 for older webOS
                Log.w(TAG, "WSS failed, falling back to WS on port 3000")
                val plainUri = URI("ws://${tv.ip}:3000")
                webSocket = LgSsapClient(
                    uri = plainUri,
                    clientKey = savedKey,
                    streamUrl = streamUrl,
                    tv = tv,
                    onStateChange = { stateFlow.value = it },
                    onClientKey = { key -> saveClientKey(context, tv.ip, key) },
                    onFallbackToPlain = null,
                    useSsl = false
                ).also { it.connect() }
            },
            useSsl = true
        ).also { it.connect() }
    }

    private suspend fun launchViaDial(tv: SmartTv, streamUrl: String) = withContext(Dispatchers.IO) {
        stateFlow.value = CastState.Launching(tv)
        try {
            val appUrl = tv.dialAppUrl
            if (appUrl == null) {
                stateFlow.value = CastState.Failure("No DIAL endpoint found for this TV")
                return@withContext
            }

            // Try common browser app IDs for different TV brands
            val browserApps = listOf(
                "com.webos.app.browser",   // LG webOS
                "org.nickel.browser",      // Samsung Tizen (older)
                "Browser",                 // Generic DIAL
                "browser"                  // Generic DIAL
            )

            var launched = false
            for (app in browserApps) {
                try {
                    val url = URL("$appUrl$app")
                    Log.w(TAG, "Trying DIAL launch: $url")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "text/plain;charset=UTF-8")
                    conn.doOutput = true
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.outputStream.use { it.write(streamUrl.toByteArray()) }

                    val code = conn.responseCode
                    Log.w(TAG, "DIAL response for $app: $code")
                    conn.disconnect()

                    if (code in 200..299) {
                        launched = true
                        break
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "DIAL app $app failed: ${e.message}")
                }
            }

            stateFlow.value = if (launched) CastState.Success
            else CastState.Failure("Could not launch browser on this TV via DIAL.")
        } catch (e: Exception) {
            stateFlow.value = CastState.Failure(e.message ?: "DIAL launch failed")
        }
    }

    private suspend fun ssdpDiscover(context: Context): List<SmartTv> = withContext(Dispatchers.IO) {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifiManager.createMulticastLock("smartcric_ssdp")
        lock.setReferenceCounted(true)
        lock.acquire()

        val discovered = mutableListOf<SmartTv>()

        try {
            val socket = DatagramSocket()
            socket.soTimeout = 4000

            val searchTargets = listOf(
                "urn:lge-com:service:webos-second-screen:1",
                "urn:dial-multiscreen-org:service:dial:1",
                "urn:schemas-upnp-org:device:MediaRenderer:1",
                "ssdp:all"
            )

            val group = InetAddress.getByName("239.255.255.250")

            for (st in searchTargets) {
                val message = buildString {
                    append("M-SEARCH * HTTP/1.1\r\n")
                    append("HOST: 239.255.255.250:1900\r\n")
                    append("MAN: \"ssdp:discover\"\r\n")
                    append("MX: 3\r\n")
                    append("ST: $st\r\n")
                    append("\r\n")
                }.toByteArray()

                val packet = DatagramPacket(message, message.size, group, 1900)
                socket.send(packet)

                val buf = ByteArray(4096)
                try {
                    while (true) {
                        val response = DatagramPacket(buf, buf.size)
                        socket.receive(response)
                        val text = String(response.data, 0, response.length)
                        val ip = response.address.hostAddress ?: continue

                        if (discovered.any { it.ip == ip }) continue

                        val isLg = text.contains("LG", ignoreCase = true) ||
                                text.contains("webOS", ignoreCase = true) ||
                                st.contains("lge-com")

                        val isDial = st.contains("dial-multiscreen-org") ||
                                text.contains("DIAL", ignoreCase = true)

                        val isTvBrand = text.contains("Samsung", ignoreCase = true) ||
                                text.contains("Sony", ignoreCase = true) ||
                                text.contains("Vizio", ignoreCase = true) ||
                                text.contains("Philips", ignoreCase = true) ||
                                text.contains("Hisense", ignoreCase = true)

                        if (isLg) {
                            val name = extractTvName(text, ip, "LG webOS TV")
                            discovered.add(SmartTv(name, ip, TvType.LG_WEBOS))
                        } else if (isDial || isTvBrand) {
                            val location = extractHeader(text, "LOCATION")
                            val dialUrl = if (location != null) getDialAppUrl(location) else null
                            if (dialUrl != null) {
                                val name = extractTvName(text, ip, "Smart TV")
                                discovered.add(SmartTv(name, ip, TvType.DIAL_DEVICE, dialUrl))
                            }
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    // Expected — no more responses
                }
            }
            socket.close()
        } catch (e: Exception) {
            Log.w(TAG, "SSDP discovery error: ${e.message}")
        } finally {
            lock.release()
        }

        Log.w(TAG, "Discovered ${discovered.size} TV(s)")
        discovered
    }

    private fun getDialAppUrl(locationUrl: String): String? {
        return try {
            val conn = URL(locationUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val appUrl = conn.getHeaderField("Application-URL")
            conn.disconnect()
            appUrl
        } catch (_: Exception) { null }
    }

    private fun extractHeader(ssdpResponse: String, headerName: String): String? {
        return ssdpResponse.lines()
            .firstOrNull { it.startsWith("$headerName:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()
    }

    private fun extractTvName(ssdpResponse: String, ip: String, defaultPrefix: String): String {
        val serverLine = ssdpResponse.lines()
            .firstOrNull { it.startsWith("SERVER:", ignoreCase = true) }
        val brand = when {
            serverLine?.contains("LG", ignoreCase = true) == true -> "LG"
            serverLine?.contains("Samsung", ignoreCase = true) == true -> "Samsung"
            serverLine?.contains("Sony", ignoreCase = true) == true -> "Sony"
            serverLine?.contains("Vizio", ignoreCase = true) == true -> "Vizio"
            serverLine?.contains("Philips", ignoreCase = true) == true -> "Philips"
            serverLine?.contains("Hisense", ignoreCase = true) == true -> "Hisense"
            serverLine?.contains("webOS", ignoreCase = true) == true -> "webOS"
            else -> null
        }
        return if (brand != null) "$brand TV ($ip)" else "$defaultPrefix ($ip)"
    }

    private fun saveClientKey(context: Context, ip: String, key: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("key_$ip", key).apply()
    }

    private fun loadClientKey(context: Context, ip: String): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("key_$ip", null)
    }
}

@Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
private class LgSsapClient(
    uri: URI,
    private val clientKey: String?,
    private val streamUrl: String,
    private val tv: SmartTv,
    private val onStateChange: (CastState) -> Unit,
    private val onClientKey: (String) -> Unit,
    private val onFallbackToPlain: (() -> Unit)?,
    private val useSsl: Boolean = false
) : WebSocketClient(uri) {

    private val msgId = AtomicInteger(0)
    private var registered = false

    init {
        if (useSsl) {
            // Trust all certificates — LG TVs use self-signed certs
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAll, java.security.SecureRandom())
            setSocketFactory(sslContext.socketFactory)
        }
    }

    override fun onOpen(handshakedata: ServerHandshake?) {
        Log.w("SmartTV", "WebSocket opened, sending register")
        onStateChange(CastState.WaitingForPairing(tv))
        send(buildRegisterMessage())
    }

    override fun onMessage(message: String?) {
        if (message == null) return
        Log.w("SmartTV", "WS message: ${message.take(200)}")
        try {
            val json = JSONObject(message)
            val type = json.optString("type")

            when (type) {
                "registered" -> {
                    registered = true
                    val key = json.optJSONObject("payload")?.optString("client-key")
                    if (!key.isNullOrEmpty()) onClientKey(key)
                    onStateChange(CastState.Launching(tv))
                    send(buildLaunchMessage())
                }
                "response" -> {
                    if (registered) {
                        onStateChange(CastState.Success)
                        close()
                    }
                }
                "error" -> {
                    val err = json.optString("error", "Unknown error")
                    onStateChange(CastState.Failure(err))
                    close()
                }
            }
        } catch (_: Exception) { }
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        Log.w("SmartTV", "WS closed: code=$code reason=$reason remote=$remote")
        if (!registered) {
            val current = SmartTvManager.stateFlow.value
            if (current is CastState.WaitingForPairing || current is CastState.Connecting) {
                if (onFallbackToPlain != null) {
                    onFallbackToPlain.invoke()
                } else {
                    onStateChange(CastState.Failure("Connection closed. Pairing may have been rejected on the TV."))
                }
            }
        }
    }

    override fun onError(ex: Exception?) {
        Log.w("SmartTV", "WS error: ${ex?.message}")
        if (onFallbackToPlain != null && !registered) {
            onFallbackToPlain.invoke()
        } else {
            onStateChange(CastState.Failure(ex?.message ?: "WebSocket error"))
        }
    }

    private fun buildRegisterMessage(): String {
        val manifest = JSONObject().apply {
            put("manifestVersion", 1)
            put("permissions", JSONArray(listOf(
                "LAUNCH", "LAUNCH_WEBAPP", "CONTROL_INPUT_TEXT",
                "CONTROL_MOUSE_AND_KEYBOARD", "READ_RUNNING_APPS"
            )))
        }
        val payload = JSONObject().apply {
            put("forcePairing", false)
            put("pairingType", "PROMPT")
            if (clientKey != null) put("client-key", clientKey)
            put("manifest", manifest)
        }
        return JSONObject().apply {
            put("type", "register")
            put("id", "reg_${msgId.incrementAndGet()}")
            put("payload", payload)
        }.toString()
    }

    private fun buildLaunchMessage(): String {
        return JSONObject().apply {
            put("type", "request")
            put("id", "launch_${msgId.incrementAndGet()}")
            put("uri", "ssap://system.launcher/open")
            put("payload", JSONObject().put("target", streamUrl))
        }.toString()
    }
}
