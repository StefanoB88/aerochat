package com.stefanobrandi.aerochat.ui.components

import android.app.Activity
import android.util.Log
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import kotlinx.coroutines.CancellationException

/**
 * Play In-App Review (RELEASE_1_4_SPEC.md §B5). Le condizioni (3 giorni, 5 aperture, 90 giorni) le decide
 * `ReviewPrompt`; Google Play puo' comunque non mostrare nulla (quota): nessun errore per l'utente.
 */
object InAppReview {
    suspend fun launch(activity: Activity) {
        try {
            val manager = ReviewManagerFactory.create(activity)
            val info = manager.requestReview()
            manager.launchReview(activity, info)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Senza Play Store o senza rete: la richiesta si perde in silenzio.
            Log.i("AeroMesh.Review", "recensione non disponibile: $e")
        }
    }
}
