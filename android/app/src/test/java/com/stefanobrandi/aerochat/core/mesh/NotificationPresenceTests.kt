package com.stefanobrandi.aerochat.core.mesh

import com.stefanobrandi.aerochat.core.crypto.IdentityKeys
import com.stefanobrandi.aerochat.data.AppDatabase
import com.stefanobrandi.aerochat.data.records.ConversationRecord
import com.stefanobrandi.aerochat.data.records.DeliveryStatus
import com.stefanobrandi.aerochat.data.records.IdentityRecord
import com.stefanobrandi.aerochat.data.records.MessageRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Quando mostrare il banner di un messaggio ricevuto (stesse regole di `NotificationPresence` iOS). */
class NotificationPresenceTests {

    private fun showing(
        resumed: Boolean = true,
        locked: Boolean = false,
        screenOn: Boolean = true,
        visible: String? = "chat-A",
        conversation: String = "chat-A",
    ) = NotificationPresence.isShowing(
        isAppResumed = resumed,
        isKeyguardLocked = locked,
        isScreenOn = screenOn,
        visibleConversationID = visible,
        conversationID = conversation,
    )

    @Test
    fun suppressedOnlyForTheOpenChatOnAnUnlockedForegroundScreen() {
        assertTrue(showing())
    }

    @Test
    fun notifiesInBackground() {
        assertFalse(showing(resumed = false))
    }

    @Test
    fun notifiesWithScreenLockedEvenIfTheChatWasOpen() {
        assertFalse(showing(locked = true))
    }

    @Test
    fun notifiesWithScreenOffWithoutKeyguard() {
        assertFalse(showing(screenOn = false))
    }

    @Test
    fun notifiesInForegroundForAnotherChatOrTab() {
        assertFalse(showing(visible = "chat-B"))
        assertFalse(showing(visible = null))
    }
}

/** Il router non deve perdere l'ACK se la notifica fallisce (il mittente ritrasmetterebbe per sempre). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotifierFailureTests : MeshTestCase() {

    private class ThrowingNotifier : MeshNotifier {
        @Volatile var calls = 0
        override suspend fun notify(message: MessageRecord, conversation: ConversationRecord) {
            calls += 1
            throw IllegalStateException("NotificationManager non disponibile")
        }
    }

    @Test
    fun ackIsSentEvenIfTheNotificationThrows() = meshTest {
        val alice = node("Alice")
        // Bob costruito a mano con un notifier che lancia.
        val identity = IdentityKeys.generate()
        val database = AppDatabase.makeTemporary(context)
        database.identities.save(IdentityRecord(identity = identity, nickname = "Bob", avatarColorHex = "00E5FF"))
        val transport = FakeTransport(localNodeID = identity.nodeID)
        val notifier = ThrowingNotifier()
        val router = MeshRouterActor(
            identity = identity, database = database, transport = transport, nickname = "Bob",
            notifier = notifier,
            configuration = MeshRouterActor.Configuration(relayBackoff = 0L..0L, maintenanceInterval = 3_600_000L),
        )
        router.start()
        try {
            val bob = TestNode("Bob", identity, database, transport, router, RecordingNotifier())
            TestNode.pair(alice, bob)
            FakeMeshNetwork.connect(alice.transport, transport)

            val conversation = alice.directConversation(with = bob)
            val sent = alice.router.sendText("Sei a bordo?", conversation = conversation)
            assertTrue(eventually { database.messages.fetch(msgID = sent.msgID) != null })
            assertTrue(eventually {
                alice.database.messages.fetch(msgID = sent.msgID)?.deliveryStatus == DeliveryStatus.deliveredAck
            })
            assertEquals(1, notifier.calls)
            assertTrue(eventually { alice.database.relayQueue.stats().count == 0 })
        } finally {
            router.stop()
            database.close()
        }
    }
}
