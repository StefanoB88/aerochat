package com.example.aerochat.core.games

import kotlin.random.Random

/**
 * Battaglia navale 10x10 con flotta ridotta (4-3-3-2) per partite brevi tra un decollo e un atterraggio.
 * La disposizione delle proprie navi resta locale: in rete viaggiano solo colpi ed esiti.
 *
 * Su iOS e' una `struct` (valore): qui e' una classe mutabile con [copy] esplicito.
 */
class Battleship(ships: List<Ship>) {

    enum class Outcome(val rawValue: Int) {
        miss(1),
        hit(2),
        sunk(3);

        companion object {
            fun fromRawValue(rawValue: Int): Outcome? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }

    sealed class Failure(message: String) : Exception(message) {
        data object outOfBounds : Failure("outOfBounds")
        data object alreadyShot : Failure("alreadyShot")
    }

    data class Ship(val cells: List<Int>, val hits: Set<Int> = emptySet()) {
        val isSunk: Boolean get() = hits.size == cells.size
    }

    private val ships: MutableList<Ship> = ships.toMutableList()
    private val received: MutableSet<Int> = LinkedHashSet()
    private val shots: MutableMap<Int, Outcome> = LinkedHashMap()

    /** Le nostre navi (segreto locale). */
    val myShips: List<Ship> get() = ships.toList()

    /** Colpi ricevuti dall'avversario. */
    val shotsReceived: Set<Int> get() = received.toSet()

    /** I nostri colpi con esito. */
    val myShots: Map<Int, Outcome> get() = shots.toMap()

    /** Navi avversarie affondate (contate dai REPORT `sunk`). */
    var enemyShipsSunk: Int = 0
        private set

    val allMyShipsSunk: Boolean get() = ships.all { it.isSunk }
    val enemyFleetDestroyed: Boolean get() = enemyShipsSunk >= fleet.size
    val myShipsRemaining: Int get() = ships.count { !it.isSunk }

    /** Colpo ricevuto: aggiorna le navi e restituisce l'esito da riportare. */
    fun receiveShot(cell: Int): Outcome {
        if (cell !in 0 until cellCount) throw Failure.outOfBounds
        if (received.contains(cell)) throw Failure.alreadyShot
        received.add(cell)
        for (index in ships.indices) {
            val ship = ships[index]
            if (ship.cells.contains(cell)) {
                val updated = ship.copy(hits = ship.hits + cell)
                ships[index] = updated
                return if (updated.isSunk) Outcome.sunk else Outcome.hit
            }
        }
        return Outcome.miss
    }

    /** Esito del nostro colpo (dal REPORT avversario). */
    fun recordMyShot(cell: Int, outcome: Outcome) {
        shots[cell] = outcome
        if (outcome == Outcome.sunk) enemyShipsSunk += 1
    }

    fun canShoot(cell: Int): Boolean = cell in 0 until cellCount && shots[cell] == null

    /** Mappa pubblica dei nostri colpi (100 byte: 0 sconosciuto, 1 acqua, 2 colpito, 3 affondato). */
    val shotsSnapshot: ByteArray
        get() {
            val bytes = ByteArray(cellCount)
            for ((cell, outcome) in shots) {
                if (cell in 0 until cellCount) bytes[cell] = outcome.rawValue.toByte()
            }
            return bytes
        }

    fun copy(): Battleship = restore(ships, received, shots, enemyShipsSunk)

    override fun equals(other: Any?): Boolean =
        other is Battleship && ships == other.ships && received == other.received && shots == other.shots &&
            enemyShipsSunk == other.enemyShipsSunk

    override fun hashCode(): Int {
        var result = ships.hashCode()
        result = 31 * result + received.hashCode()
        result = 31 * result + shots.hashCode()
        result = 31 * result + enemyShipsSunk
        return result
    }

    override fun toString(): String =
        "Battleship(myShips=$ships, shotsReceived=$received, myShots=$shots, enemyShipsSunk=$enemyShipsSunk)"

    companion object {
        const val size = 10
        const val cellCount = size * size
        val fleet: List<Int> = listOf(4, 3, 3, 2)

        /** Flotta casuale senza sovrapposizioni (Swift `randomFleet(using:)` / `randomFleet()`). */
        fun randomFleet(using: Random = Random.Default): List<Ship> {
            val occupied = HashSet<Int>()
            val ships = ArrayList<Ship>()
            for (length in fleet) {
                var placed = false
                var attempts = 0
                while (!placed && attempts < 500) {
                    attempts += 1
                    val horizontal = using.nextBoolean()
                    val row = using.nextInt(0, size)
                    val column = using.nextInt(0, size)
                    val cells = (0 until length).mapNotNull { offset ->
                        val r = if (horizontal) row else row + offset
                        val c = if (horizontal) column + offset else column
                        if (r < size && c < size) r * size + c else null
                    }
                    if (cells.size != length || cells.any { occupied.contains(it) }) continue
                    occupied.addAll(cells)
                    ships.add(Ship(cells))
                    placed = true
                }
            }
            return ships
        }

        fun isValidFleet(ships: List<Ship>): Boolean {
            if (ships.map { it.cells.size }.sorted() != fleet.sorted()) return false
            val all = ships.flatMap { it.cells }
            return all.toSet().size == all.size && all.all { it in 0 until cellCount }
        }

        /** Ricostruzione da `messages.game_state`. */
        internal fun restore(
            ships: List<Ship>,
            shotsReceived: Set<Int>,
            myShots: Map<Int, Outcome>,
            enemyShipsSunk: Int,
        ): Battleship = Battleship(ships).also {
            it.received.addAll(shotsReceived)
            it.shots.putAll(myShots)
            it.enemyShipsSunk = enemyShipsSunk
        }
    }
}
