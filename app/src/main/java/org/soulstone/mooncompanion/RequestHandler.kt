package org.soulstone.mooncompanion

import android.util.Log
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
 * Mirrors scripts/moon_phone_emulator/moon_phone.py on the Flipper-firmware
 * side. Parses an RPC_TX write, dispatches it, and returns a serialized
 * MoonPhoneMessage to notify back on RPC_RX. Returns null when the inbound
 * frame is junk and there's nothing worth replying with.
 */
class RequestHandler(private val state: PhoneState) {

    /** Drops the latitude slightly each tick so visual tests show motion. */
    private val startLatE7 = 407395680       // 40.739568 N — Flatiron
    private val startLonE7 = -739909780      // -73.990978 E

    fun onRpcTxWrite(payload: ByteArray): ByteArray? {
        val request = try {
            MoonRequest.parseFrom(payload)
        } catch (e: InvalidProtocolBufferException) {
            Log.w(TAG, "Could not parse inbound MoonRequest: ${e.message}")
            return null
        }

        Log.i(TAG, "RX request_id=${request.requestId} kind=${request.payloadCase}")
        val response = dispatch(request) ?: return null
        val envelope = MoonPhoneMessage.newBuilder().setResponse(response).build()
        return envelope.toByteArray()
    }

    fun buildPositionEvent(): ByteArray {
        state.positionTick += 1
        val event = MoonEvent.newBuilder()
            .setPositionUpdate(makePosition(state.positionTick))
            .build()
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
        Log.i(TAG, "Paired! Issued auth_token=${token.joinToString("") { "%02x".format(it) }}")
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
        return MoonResponse.newBuilder()
            .setRequestId(req.requestId)
            .setStatus(MoonStatus.MOON_OK)
            .setPosition(makePosition(state.positionTick))
            .build()
    }

    private fun handleSubscribePosition(req: MoonRequest): MoonResponse {
        state.positionSubscribed = true
        state.positionIntervalMs =
            req.subscribePosition.intervalMs.toLong().coerceAtLeast(500)
        Log.i(TAG, "Position subscription on (interval=${state.positionIntervalMs}ms)")
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
        Log.i(TAG, "Flipper notification [${n.title}] ${n.body} priority=${n.priority}")
        // Real POSTNotifications wiring comes with the notification milestone.
        return MoonResponse.newBuilder()
            .setRequestId(req.requestId)
            .setStatus(MoonStatus.MOON_OK)
            .setNotification(NotificationAck.newBuilder().setDelivered(true))
            .build()
    }

    private fun makePosition(tick: Int): PositionData =
        PositionData.newBuilder()
            .setLatE7(startLatE7 + tick * 50)        // ~5mm drift per tick
            .setLonE7(startLonE7 + tick * 30)
            .setAltMm(15_000)
            .setAccuracyMm(2_500)
            .setSpeedMmps(1_200)
            .setHeadingCdeg(tick * 18 % 36_000)
            .setTimestampMs(System.currentTimeMillis())
            .setFixQuality(FixQuality.FIX_3D)
            .setSatellites(9)
            .setSourceId(ByteString.copyFromUtf8("android1"))
            .build()

    companion object {
        private const val TAG = "MoonHandler"
    }
}
