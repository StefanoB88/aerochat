package com.stefanobrandi.aerochat.core.flight

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ricordo di un volo (solo locale): contatori minimi raccolti mentre la Cabina del volo e' attiva, per la
 * cartolina "La tua cartolina di volo e' pronta ✈️" a fine viaggio. Nessun contenuto ne' nome altrui:
 * solo conteggi e `node_id` (hex) delle persone incontrate per contarle una volta.
 */
data class FlightTrip(
    /** Chiave canonica del volo `CARRIER|FLIGHT|yyyy-MM-dd` (come `BoardingPass.canonicalKey`). */
    val key: String,
    /** Titolo della Cabina del volo (es. "AZ 204 · FCO → LHR · 10 nov"). */
    val title: String,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    /** Persone diverse incontrate durante il volo (vicini via PRESENCE o mittenti in Cabina). */
    val people: Set<String> = emptySet(),
    val messagesSent: Int = 0,
    val messagesReceived: Int = 0,
    val gamesPlayed: Int = 0,
    val gamesWon: Int = 0,
    /** Salti massimi di un messaggio ricevuto durante il volo (record di telefoni in catena). */
    val longestHop: Int = 0,
    /** Partite gia' contate (gameID), per non contarle due volte. */
    val countedGames: Set<String> = emptySet(),
    /** Notifica "cartolina pronta" gia' pubblicata (una sola volta per volo). */
    val notified: Boolean = false,
) {
    val isActive: Boolean get() = endedAtMs == null

    /** "AZ 204" dalla chiave canonica. */
    val flightCode: String
        get() {
            val parts = key.split('|')
            return if (parts.size >= 2) "${parts[0]} ${parts[1]}" else key
        }

    /** "2026-11-10" dalla chiave canonica. */
    val flightDate: String get() = key.split('|').getOrNull(2).orEmpty()

    internal fun toJSON(): JSONObject = JSONObject()
        .put("key", key)
        .put("title", title)
        .put("startedAtMs", startedAtMs)
        .put("endedAtMs", endedAtMs ?: JSONObject.NULL)
        .put("people", JSONArray(people.toList()))
        .put("messagesSent", messagesSent)
        .put("messagesReceived", messagesReceived)
        .put("gamesPlayed", gamesPlayed)
        .put("gamesWon", gamesWon)
        .put("longestHop", longestHop)
        .put("countedGames", JSONArray(countedGames.toList()))
        .put("notified", notified)

    companion object {
        internal fun fromJSON(json: JSONObject): FlightTrip = FlightTrip(
            key = json.getString("key"),
            title = json.optString("title"),
            startedAtMs = json.getLong("startedAtMs"),
            endedAtMs = if (json.isNull("endedAtMs")) null else json.getLong("endedAtMs"),
            people = json.optJSONArray("people")?.strings()?.toSet() ?: emptySet(),
            messagesSent = json.optInt("messagesSent"),
            messagesReceived = json.optInt("messagesReceived"),
            gamesPlayed = json.optInt("gamesPlayed"),
            gamesWon = json.optInt("gamesWon"),
            longestHop = json.optInt("longestHop"),
            countedGames = json.optJSONArray("countedGames")?.strings()?.toSet() ?: emptySet(),
            notified = json.optBoolean("notified"),
        )

        private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
    }
}

/**
 * Viaggi salvati in SharedPreferences (JSON), con un fallback in memoria per i test JVM. Si tengono gli
 * ultimi [maxTrips] voli.
 */
class TripMemoryStore(private val preferences: SharedPreferences?) {

    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE))

    private var memory: Map<String, FlightTrip> = load()

    @Synchronized
    fun all(): List<FlightTrip> = memory.values.sortedByDescending { it.startedAtMs }

    @Synchronized
    fun trip(key: String): FlightTrip? = memory[key]

    @Synchronized
    fun save(trip: FlightTrip) {
        val updated = LinkedHashMap(memory)
        updated[trip.key] = trip
        // Solo gli ultimi voli: i piu' vecchi si dimenticano.
        val kept = updated.values.sortedByDescending { it.startedAtMs }.take(maxTrips).associateBy { it.key }
        memory = kept
        preferences?.edit()?.putString(storageKey, JSONArray(kept.values.map { it.toJSON() }).toString())?.apply()
    }

    private fun load(): Map<String, FlightTrip> {
        val raw = preferences?.getString(storageKey, null) ?: return emptyMap()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { FlightTrip.fromJSON(array.getJSONObject(it)) }.associateBy { it.key }
        }.getOrDefault(emptyMap())
    }

    companion object {
        const val preferencesName = "aeromesh.trips"
        private const val storageKey = "trips"
        const val maxTrips = 12
    }
}

/**
 * Logica del ricordo del viaggio (pura, testabile):
 * - un volo inizia quando compare la sua Cabina del volo attiva;
 * - finisce quando la Cabina scade (o sparisce) oppure quando la modalita' aereo si spegne dopo essere
 *   stata accesa con il volo in corso;
 * - alla fine il viaggio viene restituito **una sola volta** per la notifica della cartolina.
 */
class TripMemory(private val store: TripMemoryStore) {

    /** Modalita' aereo accesa mentre un volo era in corso (in attesa dello spegnimento). */
    private var airplaneSeenDuringTrip = false

    /** Volo in corso (il piu' recente), se c'e'. */
    @get:Synchronized
    val activeTrip: FlightTrip? get() = store.all().firstOrNull { it.isActive }

    fun trip(key: String): FlightTrip? = store.trip(key)

    /** Ultimo viaggio concluso (per la cartolina dopo il tocco sulla notifica). */
    fun lastEndedTrip(): FlightTrip? = store.all().firstOrNull { !it.isActive }

    /**
     * Aggiorna con le Cabine del volo attive (chiave canonica -> titolo) e lo stato della modalita' aereo.
     * Ritorna i viaggi appena conclusi da notificare (ciascuno una volta sola).
     */
    @Synchronized
    fun update(activeFlights: Map<String, String>, airplaneMode: Boolean, now: Long): List<FlightTrip> {
        // Nuovi voli: una Cabina gia' conclusa non riparte (la Cabina resta valida per 36 ore).
        for ((key, title) in activeFlights) {
            if (store.trip(key) == null) store.save(FlightTrip(key = key, title = title, startedAtMs = now))
        }
        val active = store.all().filter { it.isActive }
        val ended = ArrayList<FlightTrip>()
        val landed = airplaneSeenDuringTrip && !airplaneMode && active.isNotEmpty()
        for (trip in active) {
            if (landed || !activeFlights.containsKey(trip.key)) ended += trip.copy(endedAtMs = now)
        }
        airplaneSeenDuringTrip = when {
            landed -> false
            airplaneMode && active.any { activeFlights.containsKey(it.key) } -> true
            else -> airplaneSeenDuringTrip && active.isNotEmpty()
        }
        val toNotify = ArrayList<FlightTrip>()
        for (trip in ended) {
            val final = if (trip.notified) trip else trip.copy(notified = true).also { toNotify += it }
            store.save(final)
        }
        return toNotify
    }

    /** Persona incontrata (PRESENCE o mittente in Cabina) durante il volo in corso. */
    @Synchronized
    fun recordPerson(nodeHex: String) {
        val trip = activeTrip ?: return
        if (trip.people.contains(nodeHex)) return
        store.save(trip.copy(people = trip.people + nodeHex))
    }

    @Synchronized
    fun recordMessage(outgoing: Boolean, hops: Int? = null) {
        val trip = activeTrip ?: return
        store.save(
            if (outgoing) trip.copy(messagesSent = trip.messagesSent + 1)
            else trip.copy(messagesReceived = trip.messagesReceived + 1, longestHop = maxOf(trip.longestHop, hops ?: 0)),
        )
    }

    /** Partita conclusa durante il volo (contata una volta): classifica del volo. */
    @Synchronized
    fun recordGameFinished(gameID: String, won: Boolean) {
        val trip = activeTrip ?: return
        if (trip.countedGames.contains(gameID)) return
        store.save(
            trip.copy(
                gamesPlayed = trip.gamesPlayed + 1,
                gamesWon = trip.gamesWon + if (won) 1 else 0,
                countedGames = trip.countedGames + gameID,
            ),
        )
    }
}
