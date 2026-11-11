package com.stefanobrandi.aerochat.core.games

/**
 * Tris: tabellone 3x3, X (chi invita) muove per primo.
 *
 * Su iOS e' una `struct` (valore): qui e' una classe mutabile con [copy] esplicito; `equals` confronta
 * tabellone e turno come l'`Equatable` sintetizzato di Swift.
 */
class TicTacToe() {

    enum class Mark(val rawValue: Int) {
        empty(0),
        x(1),
        o(2);

        val opponent: Mark
            get() = when (this) {
                x -> o
                o -> x
                empty -> empty
            }

        companion object {
            fun fromRawValue(rawValue: Int): Mark? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }

    sealed class Failure(message: String) : Exception(message) {
        data object outOfBounds : Failure("outOfBounds")
        data object cellTaken : Failure("cellTaken")
        data object notYourTurn : Failure("notYourTurn")
        data object finished : Failure("finished")
    }

    private val cells: Array<Mark> = Array(9) { Mark.empty }

    /** Le 9 celle (riga * 3 + colonna). */
    val board: List<Mark> get() = cells.toList()

    var next: Mark = Mark.x
        private set

    /** `board_snapshot` (9 byte 0/1/2) per la rete. */
    val snapshot: ByteArray get() = ByteArray(9) { cells[it].rawValue.toByte() }

    val winner: Mark?
        get() {
            for (line in lines) {
                val first = cells[line[0]]
                if (first != Mark.empty && first == cells[line[1]] && first == cells[line[2]]) return first
            }
            return null
        }

    val isDraw: Boolean get() = winner == null && cells.none { it == Mark.empty }
    val isFinished: Boolean get() = winner != null || isDraw

    /** Swift `mutating func play(_:as:)`. */
    fun play(cell: Int, mark: Mark) {
        if (isFinished) throw Failure.finished
        if (cell !in 0 until 9) throw Failure.outOfBounds
        if (mark != next) throw Failure.notYourTurn
        if (cells[cell] != Mark.empty) throw Failure.cellTaken
        cells[cell] = mark
        next = mark.opponent
    }

    fun copy(): TicTacToe = TicTacToe(cells.toList(), next)

    private constructor(marks: List<Mark>, next: Mark) : this() {
        for (i in 0 until 9) cells[i] = marks[i]
        this.next = next
    }

    override fun equals(other: Any?): Boolean =
        other is TicTacToe && cells.contentEquals(other.cells) && next == other.next

    override fun hashCode(): Int = 31 * cells.contentHashCode() + next.hashCode()

    override fun toString(): String = "TicTacToe(board=${cells.joinToString("") { it.rawValue.toString() }}, next=$next)"

    companion object {
        val lines: List<List<Int>> = listOf(
            listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8),
            listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8),
            listOf(0, 4, 8), listOf(2, 4, 6),
        )

        /** Swift `init?(snapshot:)`: ricostruisce dal `board_snapshot` ricevuto dalla rete, `null` se incoerente. */
        operator fun invoke(snapshot: ByteArray): TicTacToe? {
            if (snapshot.size != 9) return null
            val marks = snapshot.map { Mark.fromRawValue(it.toInt() and 0xFF) ?: return null }
            val xs = marks.count { it == Mark.x }
            val os = marks.count { it == Mark.o }
            if (xs != os && xs != os + 1) return null
            return TicTacToe(marks, if (xs == os) Mark.x else Mark.o)
        }

        /** Ricostruzione da `messages.game_state` (nessuna validazione: stato locale). */
        internal fun restore(marks: List<Mark>, next: Mark): TicTacToe? =
            if (marks.size == 9) TicTacToe(marks, next) else null
    }
}
