package com.stefanobrandi.aerochat.core.growth

import android.content.Context
import android.content.SharedPreferences
import com.stefanobrandi.aerochat.core.flight.FlightTrip
import org.json.JSONArray
import org.json.JSONObject

/**
 * Passaporto di viaggio (RELEASE_1_4_SPEC.md §B9): statistiche cumulative di tutti i voli (non solo gli ultimi
 * del ricordo del viaggio), aggiornate alla fine di ogni volo, e badge sbloccati con la loro data.
 */
data class PassportStats(
    val flights: Int = 0,
    val peopleMet: Int = 0,
    val messages: Int = 0,
    val gamesPlayed: Int = 0,
    val gamesWon: Int = 0,
    /** Record di telefoni in catena (salti massimi di un messaggio ricevuto in volo). */
    val longestChain: Int = 0,
    /** Badge sbloccati: id -> istante (epoch ms). */
    val badges: Map<PassportBadge, Long> = emptyMap(),
    /** Voli gia' contati (chiave canonica), per non contarli due volte. */
    val countedFlights: Set<String> = emptySet(),
) {
    internal fun toJSON(): JSONObject = JSONObject()
        .put("flights", flights)
        .put("peopleMet", peopleMet)
        .put("messages", messages)
        .put("gamesPlayed", gamesPlayed)
        .put("gamesWon", gamesWon)
        .put("longestChain", longestChain)
        .put("badges", JSONObject().apply { badges.forEach { (badge, at) -> put(badge.rawValue, at) } })
        .put("countedFlights", JSONArray(countedFlights.toList()))

    companion object {
        internal fun fromJSON(json: JSONObject): PassportStats {
            val badges = json.optJSONObject("badges")
            val counted = json.optJSONArray("countedFlights")
            return PassportStats(
                flights = json.optInt("flights"),
                peopleMet = json.optInt("peopleMet"),
                messages = json.optInt("messages"),
                gamesPlayed = json.optInt("gamesPlayed"),
                gamesWon = json.optInt("gamesWon"),
                longestChain = json.optInt("longestChain"),
                badges = PassportBadge.entries.mapNotNull { badge ->
                    if (badges?.has(badge.rawValue) == true) badge to badges.getLong(badge.rawValue) else null
                }.toMap(),
                countedFlights = counted?.let { array -> (0 until array.length()).map { array.getString(it) }.toSet() } ?: emptySet(),
            )
        }
    }
}

/** Badge del passaporto, con la soglia che li sblocca. */
enum class PassportBadge(val rawValue: String, val isUnlocked: (PassportStats) -> Boolean) {
    firstFlight("firstFlight", { it.flights >= 1 }),
    fiveFlights("fiveFlights", { it.flights >= 5 }),
    tenFlights("tenFlights", { it.flights >= 10 }),
    tenPeople("tenPeople", { it.peopleMet >= 10 }),
    fiftyPeople("fiftyPeople", { it.peopleMet >= 50 }),
    firstWin("firstWin", { it.gamesWon >= 1 }),
    tenWins("tenWins", { it.gamesWon >= 10 }),
    hundredMessages("hundredMessages", { it.messages >= 100 }),
}

/** Logica pura del passaporto: aggiunge un volo concluso e sblocca i badge raggiunti. */
object TravelPassport {

    /** Voli contati ricordati al massimo (per l'idempotenza; i contatori restano comunque). */
    private const val maxCountedFlights = 200

    /** Risultato: statistiche aggiornate e badge appena sbloccati. */
    data class Update(val stats: PassportStats, val newBadges: List<PassportBadge>)

    fun record(trip: FlightTrip, into: PassportStats, now: Long): Update {
        val stats = into
        if (stats.countedFlights.contains(trip.key)) return Update(stats, emptyList())
        val counted = (stats.countedFlights + trip.key).toList().takeLast(maxCountedFlights).toSet()
        val updated = stats.copy(
            flights = stats.flights + 1,
            peopleMet = stats.peopleMet + trip.people.size,
            messages = stats.messages + trip.messagesSent + trip.messagesReceived,
            gamesPlayed = stats.gamesPlayed + trip.gamesPlayed,
            gamesWon = stats.gamesWon + trip.gamesWon,
            longestChain = maxOf(stats.longestChain, trip.longestHop),
            countedFlights = counted,
        )
        return unlockBadges(updated, now)
    }

    /** Sblocca i badge con la soglia raggiunta (quelli gia' sbloccati tengono la loro data). */
    fun unlockBadges(stats: PassportStats, now: Long): Update {
        val fresh = PassportBadge.entries.filter { !stats.badges.containsKey(it) && it.isUnlocked(stats) }
        if (fresh.isEmpty()) return Update(stats, emptyList())
        return Update(stats.copy(badges = stats.badges + fresh.associateWith { now }), fresh)
    }
}

/** Passaporto salvato in SharedPreferences (JSON), con un fallback in memoria per i test JVM. */
class TravelPassportStore(private val preferences: SharedPreferences?) {

    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE))

    private var memory: PassportStats = load()

    @Synchronized
    fun stats(): PassportStats = memory

    /** Volo concluso: contatori aggiornati; ritorna i badge appena sbloccati. */
    @Synchronized
    fun record(trip: FlightTrip, now: Long): List<PassportBadge> {
        val update = TravelPassport.record(trip, into = memory, now = now)
        if (update.stats != memory) save(update.stats)
        return update.newBadges
    }

    private fun save(stats: PassportStats) {
        memory = stats
        preferences?.edit()?.putString(storageKey, stats.toJSON().toString())?.apply()
    }

    private fun load(): PassportStats {
        val raw = preferences?.getString(storageKey, null) ?: return PassportStats()
        return runCatching { PassportStats.fromJSON(JSONObject(raw)) }.getOrDefault(PassportStats())
    }

    companion object {
        const val preferencesName = "aeromesh.passport"
        private const val storageKey = "passport"
    }
}
