package org.soulstone.mooncompanion

import android.location.Location
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import org.soulstone.mooncompanion.proto.FixQuality
import org.soulstone.mooncompanion.proto.MoonEvent
import org.soulstone.mooncompanion.proto.MoonPhoneMessage
import org.soulstone.mooncompanion.proto.MoonRequest
import org.soulstone.mooncompanion.proto.MoonResponse
import org.soulstone.mooncompanion.proto.MoonStatus
import org.soulstone.mooncompanion.proto.NotificationAck
import org.soulstone.mooncompanion.proto.PairResponse
import org.soulstone.mooncompanion.proto.PositionData
import org.soulstone.mooncompanion.proto.TimeData
import java.util.TimeZone

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
            MoonRequest.PayloadCase.GET_POSITION       -> handleGetPosition(req)
            MoonRequest.PayloadCase.SUBSCRIBE_POSITION -> handleSubscribePosition(req)
            MoonRequest.PayloadCase.UNSUBSCRIBE_POSITION -> handleUnsubscribePosition(req)
            MoonRequest.PayloadCase.GET_TIME           -> handleGetTime(req)
            MoonRequest.PayloadCase.SEND_NOTIFICATION  -> handleSendNotification(req)
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
    }
}
