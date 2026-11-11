package com.stefanobrandi.aerochat.core

import com.stefanobrandi.aerochat.core.crypto.CryptoEngine
import com.stefanobrandi.aerochat.core.emergency.EmergencyService
import com.stefanobrandi.aerochat.core.entitlements.EntitlementManager
import com.stefanobrandi.aerochat.core.entitlements.EntitlementState
import com.stefanobrandi.aerochat.core.entitlements.FeatureDecision
import com.stefanobrandi.aerochat.core.entitlements.FeatureGate
import com.stefanobrandi.aerochat.core.entitlements.ProFeature
import com.stefanobrandi.aerochat.core.flight.BoardingPass
import com.stefanobrandi.aerochat.core.mesh.Reachability
import com.stefanobrandi.aerochat.core.mesh.isStoreAndForwardEligible
import com.stefanobrandi.aerochat.core.moderation.ContentFilter
import com.stefanobrandi.aerochat.core.protocol.MeshProtocol
import com.stefanobrandi.aerochat.core.protocol.MeshWire
import com.stefanobrandi.aerochat.core.protocol.rawBytes
import com.stefanobrandi.aerochat.core.protocol.toByteString
import com.stefanobrandi.aerochat.core.protocol.toHex
import com.stefanobrandi.aerochat.data.records.ConversationRecord
import com.stefanobrandi.aerochat.data.records.ConversationType
import com.stefanobrandi.aerochat.data.records.ProTier
import com.stefanobrandi.aerochat.proto.EmergencyBroadcast
import com.stefanobrandi.aerochat.proto.EmergencyStatus
import com.stefanobrandi.aerochat.proto.PacketType
import com.stefanobrandi.aerochat.proto.QuizAction
import com.stefanobrandi.aerochat.proto.QuizPacket
import com.stefanobrandi.aerochat.proto.QuizScore
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Funzioni della release 1.1: moderazione, carta d'imbarco, quiz, emergenze, pass (port di `LaunchFeaturesTests.swift`). */
class LaunchFeaturesTests {

    private fun instant(iso: String): Long = Instant.parse(iso).toEpochMilli()

    // MARK: - Filtro linguaggio

    @Test
    fun contentFilterMasksWholeWordsOnly() {
        assertTrue(ContentFilter.containsProfanity("Che cazzo di ritardo"))
        assertFalse("parola diversa, non filtrata", ContentFilter.containsProfanity("Il cazzuolo del muratore"))
        assertEquals("Che c**** di ritardo", ContentFilter.masked("Che cazzo di ritardo"))
        assertEquals("Buon volo a tutti", ContentFilter.masked("Buon volo a tutti"))
        // Accenti e maiuscole non aggirano il filtro.
        assertTrue(ContentFilter.containsProfanity("SCHEIßE, wieder Verspätung"))
        assertEquals("P*****", ContentFilter.masked("Putaín"))
        // Accento decomposto (segno combinante): stessa parola, l'iniziale resta un solo carattere visibile.
        assertEquals("P*****", ContentFilter.masked("Putai\u0301n"))
        assertEquals("ok, f***!", ContentFilter.masked("ok, fuck!"))
    }

    // MARK: - Carta d'imbarco (IATA BCBP)

    @Test
    fun boardingPassParsesStandardBarcode() {
        // Formato IATA 792, un segmento: nome, PNR, FCO->LHR, AZ 0204, giorno 314, classe Y, posto 12A.
        val raw = "M1ROSSI/MARIO         EABC123 FCOLHRAZ 0204 314Y012A0001 100"
        val pass = BoardingPass.fromBCBP(raw, now = instant("2026-11-10T12:00:00Z"))
        assertEquals("AZ", pass.carrier)
        assertEquals("204", pass.flightNumber)
        assertEquals("FCO", pass.origin)
        assertEquals("LHR", pass.destination)
        assertEquals("12A", pass.seat)
        assertEquals("AZ|204|2026-11-10", pass.canonicalKey)
        assertEquals("AZ 204", pass.flightCode)
        assertEquals("FCO → LHR", pass.routeText)
        assertEquals(instant("2026-11-10T00:00:00Z"), pass.departureDay)
        assertEquals(instant("2026-11-11T12:00:00Z"), pass.loungeExpiry)
        assertEquals("AZ 204 · FCO → LHR · 10 nov", pass.loungeTitle(java.util.Locale.ITALIAN))
    }

    @Test
    fun boardingPassJulianDayPicksNearestYear() {
        // 2 gennaio 2027: il giorno 365 appartiene al 2026 (31 dicembre), non al 2027.
        val date = BoardingPass.date(julianDay = 365, near = instant("2027-01-02T08:00:00Z"))
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
        assertEquals("2026-12-31", formatter.format(Instant.ofEpochMilli(date)))
        // E il giorno 2 scansionato il 30 dicembre e' gia' dell'anno dopo.
        val next = BoardingPass.date(julianDay = 2, near = instant("2026-12-30T08:00:00Z"))
        assertEquals("2027-01-02", formatter.format(Instant.ofEpochMilli(next)))
    }

    @Test
    fun boardingPassManualEntryAndDerivation() {
        val day = instant("2026-11-10T20:30:00Z")
        val a = requireNotNull(BoardingPass.fromFlightText("az 0204", departureDay = day))
        val b = requireNotNull(BoardingPass.fromFlightText("AZ-204", departureDay = day))
        // Stesso volo e stessa data: stesso gruppo e stessa chiave su ogni telefono.
        assertEquals(a.canonicalKey, b.canonicalKey)
        assertEquals(a.loungeGroupID, b.loungeGroupID)
        assertArrayEquals(a.loungeSecretKey, b.loungeSecretKey)
        assertEquals(32, a.loungeSecretKey.size)
        // UUID v4 valido (bit di versione e variante).
        val bytes = a.loungeGroupID.rawBytes()
        assertEquals(4, (bytes[6].toInt() and 0xFF) shr 4)
        assertEquals(0b10, (bytes[8].toInt() and 0xFF) shr 6)
        assertNull(BoardingPass.fromFlightText("204", departureDay = day))
        assertNull(BoardingPass.fromFlightText("ABCD", departureDay = day))
        assertEquals(a, BoardingPass(carrier = "AZ", flightNumber = "204", origin = null, destination = null, seat = null, departureDay = day))
    }

    /**
     * Vettore incrociato con iOS: valori calcolati con `hashlib.sha256` (python3) seguendo
     * PROTOCOL_CHANGES_1_1.md §2 per il canonico "AZ|204|2026-11-10".
     */
    @Test
    fun flightLoungeDerivationMatchesCrossPlatformVector() {
        val pass = requireNotNull(BoardingPass.fromFlightText("AZ204", departureDay = instant("2026-11-10T08:00:00Z")))
        assertEquals("AZ|204|2026-11-10", pass.canonicalKey)
        assertEquals("021f3ac145f067d79218faaf1d2186661e4b6fb5c89c1c450b93e05db2033202", pass.loungeSecretKey.toHex())
        assertEquals("4e5ed36a-a223-4e34-a5c9-c68ebaf63dc8", pass.loungeGroupID.toString())
        val lounge = ConversationRecord.flightLounge(pass)
        assertEquals("4e5ed36a-a223-4e34-a5c9-c68ebaf63dc8", lounge.id)
        assertEquals("b9070b8b2b885f61", lounge.recipientID.toHex())
        assertEquals("b9070b8b2b885f61", CryptoEngine.groupRecipientID(groupID = pass.loungeGroupID).toHex())
    }

    @Test
    fun boardingPassRejectsNonBarcode() {
        try {
            BoardingPass.fromBCBP("aeromesh://v1?x=abc")
            fail("dovrebbe lanciare")
        } catch (_: BoardingPass.ParseError) {
        }
        try {
            BoardingPass.fromBCBP("M1ROSSI/MARIO         EABC123 FCOLHRAZ 0204 999Y012A0001 100")
            fail("giorno giuliano non valido")
        } catch (e: BoardingPass.ParseError) {
            assertEquals(BoardingPass.ParseError.InvalidDate, e)
        }
    }

    @Test
    fun flightLoungeRecordIsFreeTextGroup() {
        val pass = requireNotNull(BoardingPass.fromFlightText("LH123", departureDay = instant("2026-11-10T20:30:00Z")))
        val lounge = ConversationRecord.flightLounge(pass)
        assertEquals(ConversationType.group, lounge.type)
        assertTrue(lounge.isFlightLounge)
        assertEquals(pass.canonicalKey, lounge.flightCode)
        assertNotNull(lounge.expiresAtMs)
        assertEquals(
            FeatureDecision.allowed,
            FeatureGate.decision(ProFeature.groupChat, userIsPro = false, conversation = lounge, reachability = Reachability.OutOfRange),
        )
        assertEquals(
            FeatureDecision.requiresPass,
            FeatureGate.decision(ProFeature.photo, userIsPro = false, conversation = lounge, reachability = Reachability.Direct(rssi = -50)),
        )
    }

    // MARK: - Protocollo: quiz ed emergenze

    @Test
    fun quizPacketRoundTrip() {
        val packet = QuizPacket.newBuilder()
            .setQuizId("abc123")
            .setAction(QuizAction.QUESTION)
            .setRound(2)
            .setTotalRounds(5)
            .setQuestion("Capitale dell'Australia?")
            .addAllOptions(listOf("Sydney", "Canberra"))
            .setHostNodeId(ByteArray(8) { 7 }.toByteString())
            .setDeadlineMs(1_800_000_000_000)
            .addScores(QuizScore.newBuilder().setNodeId(ByteArray(8) { 1 }.toByteString()).setNickname("Anna").setPoints(150))
            .build()
        val decoded = MeshWire.parseQuizPacket(MeshWire.serialize(packet))
        assertEquals(packet, decoded)
        assertEquals(12, PacketType.QUIZ.number)
        assertFalse(PacketType.QUIZ.isStoreAndForwardEligible)
    }

    @Test
    fun emergencyBroadcastRoundTrip() {
        val broadcast = EmergencyBroadcast.newBuilder()
            .setStatus(EmergencyStatus.NEED_HELP)
            .setText("Fila 23, passeggero svenuto")
            .setLatitude(41.8)
            .setLongitude(12.25)
            .setHasLocation(true)
            .setIssuedAtMs(1_800_000_000_000)
            .build()
        val decoded = MeshWire.parseEmergencyBroadcast(MeshWire.serialize(broadcast))
        assertEquals(broadcast, decoded)
        assertEquals(13, PacketType.EMERGENCY.number)
        assertTrue(PacketType.EMERGENCY.isStoreAndForwardEligible)
        val text = EmergencyService.systemText(nickname = "Luca", broadcast = broadcast)
        assertTrue(text.contains("🆘 Luca"))
        assertEquals("🆘 Luca: Ho bisogno di aiuto — Fila 23, passeggero svenuto — 📍 41.80000, 12.25000", text)
    }

    // MARK: - Pass

    @Test
    fun singleFlightPassTier() {
        assertEquals(ProTier.pass24h, ProTier.fromProductID(MeshProtocol.ProductID.pass24h))
        assertEquals("com.aeromesh.pass24h", MeshProtocol.ProductID.pass24h)
        assertEquals(86_400_000L, ProTier.pass24h.passDuration)
        assertTrue(ProTier.pass24h.isTimeLimited)
        assertFalse(ProTier.lifetime.isTimeLimited)
        assertEquals("pass_24h", ProTier.pass24h.rawValue)
        assertEquals(MeshProtocol.ProductID.pass24h, ProTier.pass24h.productID)
        assertTrue(MeshProtocol.ProductID.pass24h in EntitlementManager.productIDs)
        val now = System.currentTimeMillis()
        val active = EntitlementState(tier = ProTier.pass24h, validUntilMs = now + 3_600_000, source = EntitlementState.Source.storeKit)
        assertTrue(active.isPro())
        val expired = EntitlementState(tier = ProTier.pass24h, validUntilMs = now - 60_000, source = EntitlementState.Source.storeKit)
        assertFalse(expired.isPro())
    }
}
