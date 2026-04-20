package org.soulstone.mooncompanion

import android.location.Location
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.soulstone.mooncompanion.proto.BulkKind
import org.soulstone.mooncompanion.proto.FixQuality
import org.soulstone.mooncompanion.proto.HttpHeader
import org.soulstone.mooncompanion.proto.HttpResponse
import org.soulstone.mooncompanion.proto.MoonEvent
import org.soulstone.mooncompanion.proto.MoonPhoneMessage
import org.soulstone.mooncompanion.proto.MoonRequest
import org.soulstone.mooncompanion.proto.MoonResponse
import org.soulstone.mooncompanion.proto.MoonStatus
import org.soulstone.mooncompanion.proto.NotificationAck
import org.soulstone.mooncompanion.proto.OpenBulkChannelResponse
import org.soulstone.mooncompanion.proto.PairResponse
import org.soulstone.mooncompanion.proto.PositionData
import org.soulstone.mooncompanion.proto.TimeData
import java.io.IOException
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Parses an RPC_TX write from the Flipper, dispatches it, and returns a
 * serialized MoonPhoneMessage to notify back on RPC_RX. Returns null when
 * the inbound frame is junk and there's nothing worth replying with.
 *
 * GPS comes from FusedLocationProviderClient via the injected LocationProvider.
 * If no fix is available yet (indoors, permission denied, airplane mode)
 * position-related responses come back as MOON_NOT_AVAILABLE rather than
 * fabricating coordinates — the Flipper side decides how to show that.
 */
class RequestHandler(
    private val state: PhoneState,
    private val locations: LocationProvider,
    private val bulk: BulkChannelServer,
) {

    /** How old a fix is allowed to be before we treat it as "no fix". */
    private val maxFixAgeMs: Long = 30_000L

    fun onRpcTxWrite(payload: ByteArray): ByteArray? {
        val request = try {
            MoonRequest.parseFrom(payload)
        } catch (e: InvalidProtocolBufferException) {
            DebugLog.w(TAG, "Could not parse inbound MoonRequest: ${e.message}")
            return null
        }

        DebugLog.i(TAG, "RX request_id=${request.requestId} kind=${request.payloadCase}")
        val response = dispatch(request) ?: return null
        val envelope = MoonPhoneMessage.newBuilder().setResponse(response).build()
        return envelope.toByteArray()
    }

    /**
     * Build a position event to push to subscribers. Returns null when we
     * have no usable fix yet — the service loop should skip this tick
     * rather than spam the Flipper with NOT_AVAILABLE events.
     */
    fun buildPositionEvent(): ByteArray? {
        val position = currentPositionOrNull() ?: return null
        state.positionTick += 1
        val event = MoonEvent.newBuilder().setPositionUpdate(position).build()
        return MoonPhoneMessage.newBuilder().setEvent(event).build().toByteArray()
    }

    private fun dispatch(req: MoonRequest): MoonResponse? {
        // PairRequest is the one unauthenticated request we honour.
        if (req.payloadCase == MoonRequest.PayloadCase.PAIR) {
            return handlePair(req)
        }

        val unauth = checkAuth(req)
        if (unauth != null) return unauth

        return when (req.payloadCase) {
            MoonRequest.PayloadCase.GET_POSITION         -> handleGetPosition(req)
            MoonRequest.PayloadCase.SUBSCRIBE_POSITION   -> handleSubscribePosition(req)
            MoonRequest.PayloadCase.UNSUBSCRIBE_POSITION -> handleUnsubscribePosition(req)
            MoonRequest.PayloadCase.GET_TIME             -> handleGetTime(req)
            MoonRequest.PayloadCase.SEND_NOTIFICATION    -> handleSendNotification(req)
            MoonRequest.PayloadCase.OPEN_BULK_CHANNEL    -> handleOpenBulkChannel(req)
            MoonRequest.PayloadCase.CLOSE_BULK_CHANNEL   -> handleCloseBulkChannel(req)
            MoonRequest.PayloadCase.HTTP                 -> handleHttp(req)
            else -> MoonResponse.newBuilder()
                .setRequestId(req.requestId)
                .setStatus(MoonStatus.MOON_UNSUPPORTED_REQUEST)
                .build()
        }
    }

    private fun handlePair(req: MoonRequest): MoonResponse {
        val token = state.issueAuthToken()
        DebugLog.i(TAG, "Paired! Issued auth_token=${token.joinToString("") { "%02x".format(it) }}")
        return MoonResponse.newBuilder()
            .setRequestId(req.requestId)
            .setStatus(MoonStatus.MOON_OK)
            .setPair(
                PairResponse.newBuilder()
                    .setAuthToken(ByteString.copyFrom(token))
                    .setPhoneName("Moon Companion Android")
            )
            .build()
    }

    private fun checkAuth(req: MoonRequest): MoonResponse? {
        val valid = state.paired && req.authToken.toByteArray().contentEquals(state.authToken)
        if (valid) return null
        return MoonResponse.newBuilder()
            .setRequestId(req.requestId)
            .setStatus(MoonStatus.MOON_UNAUTHORIZED)
            .build()
    }

    private fun handleGetPosition(req: MoonRequest): MoonResponse {
        val position = currentPositionOrNull()
        val builder = MoonResponse.newBuilder().setRequestId(req.requestId)
        return if (position != null) {
            builder.setStatus(MoonStatus.MOON_OK).setPosition(position).build()
        } else {
            builder.setStatus(MoonStatus.MOON_NOT_AVAILABLE).build()
        }
    }

    private fun handleSubscribePosition(req: MoonRequest): MoonResponse {
        state.positionSubscribed = true
        state.positionIntervalMs =
            req.subscribePosition.intervalMs.toLong().coerceAtLeast(500)
        DebugLog.i(TAG, "Position subscription on (interval=${state.positionIntervalMs}ms)")
        return MoonResponse.newBuilder()
            .setRequestId(req.requestId)
            .setStatus(MoonStatus.MOON_OK)
            .build()
    }

    private fun handleUnsubscribePosition(req: MoonRequest): MoonResponse {
        state.positionSubscribed = false
        return MoonResponse.newBuilder()
            .setRequestId(req.requestId)
            .setStatus(MoonStatus.MOON_OK)
            .build()
    }

    private fun handleGetTime(req: MoonRequest): MoonResponse {
        val tz = TimeZone.getDefault()
        val now = System.currentTimeMillis()
        return MoonResponse.newBuilder()
            .setRequestId(req.requestId)
            .setStatus(MoonStatus.MOON_OK)
            .setTime(
                TimeData.newBuilder()
                    .setUnixMs(now)
                    .setTzOffsetSeconds(tz.getOffset(now) / 1000)
                    .setTzName(tz.id)
            )
            .build()
    }

    private fun handleOpenBulkChannel(req: MoonRequest): MoonResponse {
        val r = req.openBulkChannel
        val psm = bulk.psm
        if (psm <= 0) {
            DebugLog.w(TAG, "OpenBulkChannel: server socket not ready")
            return MoonResponse.newBuilder()
                .setRequestId(req.requestId)
                .setStatus(MoonStatus.MOON_NOT_AVAILABLE)
                .build()
        }
        val kind = when (r.kind) {
            BulkKind.BULK_DOWNLOAD_FAP    -> BulkChannelServer.BulkKindLocal.DOWNLOAD_FAP
            BulkKind.BULK_UPLOAD_FILE     -> BulkChannelServer.BulkKindLocal.UPLOAD_FILE
            BulkKind.BULK_FIRMWARE_UPDATE -> BulkChannelServer.BulkKindLocal.FIRMWARE_UPDATE
            BulkKind.BULK_ECHO_TEST       -> BulkChannelServer.BulkKindLocal.ECHO_TEST
            else                          -> BulkChannelServer.BulkKindLocal.UNSPECIFIED
        }
        val session = bulk.beginSession(kind, r.name ?: "")
        DebugLog.i(
            TAG,
            "Open bulk channel: kind=$kind name=${r.name} -> psm=$psm, bytes=${session.totalBytes}"
        )
        return MoonResponse.newBuilder()
            .setRequestId(req.requestId)
            .setStatus(MoonStatus.MOON_OK)
            .setOpenBulkChannel(
                OpenBulkChannelResponse.newBuilder()
                    .setSpsm(psm)
                    .setTotalBytes(session.totalBytes)
                    .setSessionId(ByteString.copyFrom(session.sessionId))
            )
            .build()
    }

    private fun handleCloseBulkChannel(req: MoonRequest): MoonResponse {
        bulk.cancelSession(req.closeBulkChannel.sessionId.toByteArray())
        return MoonResponse.newBuilder()
            .setRequestId(req.requestId)
            .setStatus(MoonStatus.MOON_OK)
            .build()
    }

    /**
     * Execute an HTTP(S) request through OkHttp and marshal the result.
     *
     * v1 is inline-only: the response body must fit in a single GATT
     * notification alongside status code + headers (total envelope
     * ≤ ~180 B after protobuf + GATT framing). Anything larger returns
     * MOON_NOT_AVAILABLE — FAPs needing large payloads should use the
     * existing bulk-channel API (BULK_DOWNLOAD_FAP) directly. v1.1 will
     * add automatic bulk streaming for oversize HTTP responses.
     *
     * Runs synchronously on the caller's thread (typically a BLE GATT
     * server thread). OkHttp's read timeout bounds how long that thread
     * can be parked.
     */
    private fun handleHttp(req: MoonRequest): MoonResponse {
        val h = req.http
        val method = h.method.ifEmpty { "GET" }.uppercase()
        val url = h.url
        if (url.isEmpty()) {
            DebugLog.w(TAG, "http: empty URL")
            return httpFailure(req, MoonStatus.MOON_NOT_AVAILABLE)
        }

        val timeoutMs = if (h.timeoutMs > 0) h.timeoutMs.toLong() else DEFAULT_HTTP_TIMEOUT_MS
        val client = OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs + 5_000, TimeUnit.MILLISECONDS)
            .build()

        val contentType = h.headersList
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value?.toMediaTypeOrNull()
        val body = when {
            h.body.size() == 0 -> null
            else -> h.body.toByteArray().toRequestBody(contentType)
        }

        val builder = Request.Builder().url(url).method(method, body)
        for (hdr in h.headersList) {
            // OkHttp rejects headers with control characters; skip silently.
            try {
                builder.addHeader(hdr.key, hdr.value)
            } catch (_: IllegalArgumentException) {
                DebugLog.w(TAG, "http: dropped invalid header ${hdr.key}")
            }
        }

        DebugLog.i(TAG, "http: $method $url timeout=${timeoutMs}ms")

        return try {
            client.newCall(builder.build()).execute().use { response ->
                val bodyBytes = response.body?.bytes() ?: ByteArray(0)
                if (bodyBytes.size > MAX_INLINE_BODY) {
                    DebugLog.w(
                        TAG,
                        "http: response body ${bodyBytes.size} B > ${MAX_INLINE_BODY} B inline cap — " +
                            "use bulk-channel for large downloads (v1.1 will auto-bulk)"
                    )
                    return httpFailure(req, MoonStatus.MOON_NOT_AVAILABLE)
                }

                val httpResp = HttpResponse.newBuilder()
                    .setStatusCode(response.code)
                    .setBody(ByteString.copyFrom(bodyBytes))

                for (name in response.headers.names()) {
                    for (value in response.headers.values(name)) {
                        httpResp.addHeaders(
                            HttpHeader.newBuilder().setKey(name).setValue(value)
                        )
                    }
                }

                MoonResponse.newBuilder()
                    .setRequestId(req.requestId)
                    .setStatus(MoonStatus.MOON_OK)
                    .setHttp(httpResp)
                    .build()
            }
        } catch (e: SSLException) {
            DebugLog.w(TAG, "http: TLS failure for $url: ${e.message}")
            httpFailure(req, MoonStatus.MOON_NOT_AVAILABLE)
        } catch (e: IOException) {
            DebugLog.w(TAG, "http: IO failure for $url: ${e.message}")
            httpFailure(req, MoonStatus.MOON_NOT_AVAILABLE)
        } catch (e: IllegalArgumentException) {
            DebugLog.w(TAG, "http: invalid request ($method $url): ${e.message}")
            httpFailure(req, MoonStatus.MOON_NOT_AVAILABLE)
        }
    }

    private fun httpFailure(req: MoonRequest, status: MoonStatus): MoonResponse =
        MoonResponse.newBuilder()
            .setRequestId(req.requestId)
            .setStatus(status)
            .build()

    private fun handleSendNotification(req: MoonRequest): MoonResponse {
        val n = req.sendNotification
        DebugLog.i(TAG, "Flipper notification [${n.title}] ${n.body} priority=${n.priority}")
        // Real POSTNotifications wiring comes with the notification milestone.
        return MoonResponse.newBuilder()
            .setRequestId(req.requestId)
            .setStatus(MoonStatus.MOON_OK)
            .setNotification(NotificationAck.newBuilder().setDelivered(true))
            .build()
    }

    private fun currentPositionOrNull(): PositionData? {
        val fix = locations.current() ?: return null
        val ageMs = System.currentTimeMillis() - fix.time
        if (ageMs > maxFixAgeMs) {
            DebugLog.d(TAG, "Skipping stale fix (age=${ageMs}ms)")
            return null
        }
        return toProto(fix)
    }

    private fun toProto(fix: Location): PositionData {
        val builder = PositionData.newBuilder()
            .setLatE7((fix.latitude * 1e7).toInt())
            .setLonE7((fix.longitude * 1e7).toInt())
            .setAccuracyMm((fix.accuracy * 1_000f).toInt())
            .setSpeedMmps((fix.speed * 1_000f).toInt())
            .setTimestampMs(fix.time)
            .setSourceId(ByteString.copyFromUtf8("android1"))

        // Optional fields — only populate when the platform gave us a value.
        if (fix.hasAltitude()) {
            builder.setAltMm((fix.altitude * 1_000.0).toInt())
            builder.setFixQuality(FixQuality.FIX_3D)
        } else {
            builder.setFixQuality(FixQuality.FIX_2D)
        }
        if (fix.hasBearing()) {
            // Bearing is 0-360 degrees; proto wants 0-35999 centidegrees.
            builder.setHeadingCdeg(((fix.bearing * 100f).toInt() % 36_000))
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "MoonHandler"
        /**
         * Conservative inline cap on HTTP response body. Total MoonResponse
         * envelope (status + headers + body) must fit in one GATT
         * notification; the Flipper-side transport rejects payloads > 244 B
         * and does no reassembly in v1. Leaves ~90 B for headers + envelope.
         */
        private const val MAX_INLINE_BODY = 150
        private const val DEFAULT_HTTP_TIMEOUT_MS = 30_000L
    }
}
