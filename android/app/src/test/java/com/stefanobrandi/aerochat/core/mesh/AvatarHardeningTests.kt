package com.stefanobrandi.aerochat.core.mesh

import android.graphics.Bitmap
import android.graphics.Color
import com.stefanobrandi.aerochat.core.media.WebPEncoder
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Regressione: un avatar ricevuto entro i 12 KB ma con dimensioni enormi non viene salvato. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AvatarHardeningTests : MeshTestCase() {

    private fun webp(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(236, 72, 153))
        return WebPEncoder.encode(bitmap, quality = 0.5f).also { bitmap.recycle() }
    }

    @Test
    fun oversizedAvatarIsDropped() = meshTest {
        val alice = node("Alice")
        val bob = node("Bob")
        TestNode.pair(alice, bob)
        FakeMeshNetwork.connect(alice.transport, bob.transport)

        val huge = webp(4_000, 1)
        assertTrue(huge.size <= com.stefanobrandi.aerochat.core.protocol.MeshProtocol.maxAvatarBytes)
        val now = System.currentTimeMillis()
        alice.router.setProfile(
            LocalProfile(nickname = "Alice XL", avatarColorHex = "EC4899", avatarImage = huge, updatedAtMs = now),
            announce = true,
        )
        // Il resto del profilo arriva, l'avatar no.
        assertTrue(eventually { bob.database.peers.fetch(nodeID = alice.nodeID)?.nickname == "Alice XL" })
        assertNull(bob.database.peers.fetch(nodeID = alice.nodeID)?.avatarImage)

        // Un avatar normale (128 px) viene accettato.
        val normal = webp(128, 128)
        alice.router.setProfile(
            LocalProfile(nickname = "Alice", avatarColorHex = "EC4899", avatarImage = normal, updatedAtMs = now + 1),
            announce = true,
        )
        assertTrue(eventually { bob.database.peers.fetch(nodeID = alice.nodeID)?.avatarImage?.contentEquals(normal) == true })
    }
}
