package com.stefanobrandi.aerochat.core.quiz

import android.content.Context
import androidx.annotation.StringRes
import com.stefanobrandi.aerochat.R

/** Domanda del quiz di cabina: testo, 4 opzioni e indice di quella giusta. */
data class QuizQuestion(
    val text: String,
    val options: List<String>,
    val correctIndex: Int,
)

/**
 * Banca domande integrata (viaggi, geografia, aviazione): il conduttore le invia nella propria lingua.
 * Testi in `res/values-xx/strings_quiz.xml` (stesse traduzioni del catalogo iOS); le opzioni solo numeriche
 * non vengono tradotte.
 */
object QuizQuestionBank {

    private sealed interface Option {
        class Resource(@StringRes val id: Int) : Option
        class Literal(val text: String) : Option
    }

    private class Entry(@StringRes val text: Int, val options: List<Option>, val correctIndex: Int)

    private fun q(@StringRes text: Int, options: List<Option>, correctIndex: Int) = Entry(text, options, correctIndex)
    private fun o(@StringRes id: Int): Option = Option.Resource(id)
    private fun lit(text: String): Option = Option.Literal(text)

    private val entries: List<Entry> = listOf(
        q(R.string.quiz_q1, listOf(o(R.string.quiz_q1_a), o(R.string.quiz_q1_b), o(R.string.quiz_q1_c), o(R.string.quiz_q1_d)), 2),
        q(R.string.quiz_q2, listOf(o(R.string.quiz_q2_a), o(R.string.quiz_q2_b), o(R.string.quiz_q2_c), o(R.string.quiz_q2_d)), 1),
        q(R.string.quiz_q3, listOf(o(R.string.quiz_q3_a), o(R.string.quiz_q3_b), o(R.string.quiz_q3_c), o(R.string.quiz_q3_d)), 1),
        q(R.string.quiz_q4, listOf(o(R.string.quiz_q4_a), o(R.string.quiz_q4_b), o(R.string.quiz_q4_c), o(R.string.quiz_q4_d)), 2),
        q(R.string.quiz_q5, listOf(lit("5"), lit("7"), lit("9"), lit("11")), 3),
        q(R.string.quiz_q6, listOf(o(R.string.quiz_q6_a), o(R.string.quiz_q6_b), o(R.string.quiz_q6_c), o(R.string.quiz_q6_d)), 0),
        q(R.string.quiz_q7, listOf(o(R.string.quiz_q7_a), o(R.string.quiz_q7_b), o(R.string.quiz_q7_c), o(R.string.quiz_q7_d)), 1),
        q(R.string.quiz_q8, listOf(o(R.string.quiz_q8_a), o(R.string.quiz_q8_b), o(R.string.quiz_q8_c), o(R.string.quiz_q8_d)), 1),
        q(R.string.quiz_q9, listOf(o(R.string.quiz_q9_a), o(R.string.quiz_q9_b), o(R.string.quiz_q9_c), o(R.string.quiz_q9_d)), 2),
        q(R.string.quiz_q10, listOf(lit("2"), lit("3"), lit("4"), lit("5")), 2),
        q(R.string.quiz_q11, listOf(o(R.string.quiz_q11_a), o(R.string.quiz_q11_b), o(R.string.quiz_q11_c), o(R.string.quiz_q11_d)), 2),
        q(R.string.quiz_q12, listOf(o(R.string.quiz_q12_a), o(R.string.quiz_q12_b), o(R.string.quiz_q12_c), o(R.string.quiz_q12_d)), 2),
        q(R.string.quiz_q13, listOf(o(R.string.quiz_q13_a), o(R.string.quiz_q13_b), o(R.string.quiz_q13_c), o(R.string.quiz_q13_d)), 1),
        q(R.string.quiz_q14, listOf(o(R.string.quiz_q14_a), o(R.string.quiz_q14_b), o(R.string.quiz_q14_c), o(R.string.quiz_q14_d)), 1),
        q(R.string.quiz_q15, listOf(o(R.string.quiz_q15_a), o(R.string.quiz_q15_b), o(R.string.quiz_q15_c), o(R.string.quiz_q15_d)), 0),
        q(R.string.quiz_q16, listOf(o(R.string.quiz_q16_a), o(R.string.quiz_q16_b), o(R.string.quiz_q16_c), o(R.string.quiz_q16_d)), 2),
        q(R.string.quiz_q17, listOf(o(R.string.quiz_q17_a), o(R.string.quiz_q17_b), o(R.string.quiz_q17_c), o(R.string.quiz_q17_d)), 2),
        q(R.string.quiz_q18, listOf(o(R.string.quiz_q18_a), o(R.string.quiz_q18_b), o(R.string.quiz_q18_c), o(R.string.quiz_q18_d)), 0),
        q(R.string.quiz_q19, listOf(o(R.string.quiz_q19_a), o(R.string.quiz_q19_b), o(R.string.quiz_q19_c), o(R.string.quiz_q19_d)), 1),
        q(R.string.quiz_q20, listOf(o(R.string.quiz_q20_a), o(R.string.quiz_q20_b), o(R.string.quiz_q20_c), o(R.string.quiz_q20_d)), 1),
        q(R.string.quiz_q21, listOf(o(R.string.quiz_q21_a), o(R.string.quiz_q21_b), o(R.string.quiz_q21_c), o(R.string.quiz_q21_d)), 2),
        q(R.string.quiz_q22, listOf(o(R.string.quiz_q22_a), o(R.string.quiz_q22_b), o(R.string.quiz_q22_c), o(R.string.quiz_q22_d)), 1),
        q(R.string.quiz_q23, listOf(o(R.string.quiz_q23_a), o(R.string.quiz_q23_b), o(R.string.quiz_q23_c), o(R.string.quiz_q23_d)), 1),
        q(R.string.quiz_q24, listOf(lit("6"), lit("10"), lit("18"), lit("24")), 2),
    )

    /** Numero di domande disponibili. */
    val count: Int get() = entries.size

    /** Le domande nella lingua corrente dell'app. */
    fun questions(context: Context): List<QuizQuestion> = entries.map { entry ->
        QuizQuestion(
            text = context.getString(entry.text),
            options = entry.options.map { option ->
                when (option) {
                    is Option.Resource -> context.getString(option.id)
                    is Option.Literal -> option.text
                }
            },
            correctIndex = entry.correctIndex,
        )
    }
}
