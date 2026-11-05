package cloud.trotter.log.strength.domain.library

class ExerciseSearch(query: String) {
    private val queryWords = words(query)

    fun matches(name: String): Boolean {
        if (queryWords.isEmpty()) return true
        val nameWords = words(name)
        return queryWords.all { queryWord -> nameWords.any { queryWord in it } }
    }

    private companion object {
        val synonyms = mapOf(
            "db" to "dumbbell",
            "bb" to "barbell",
            "kb" to "kettlebell",
            "sl" to "single-leg",
        )
        val wordSeparator = Regex("[^\\p{L}\\p{N}]+")

        fun words(text: String): List<String> = text.lowercase().split(wordSeparator)
            .filter { it.isNotEmpty() }
            .flatMap { (synonyms[it] ?: it).split(wordSeparator) }
    }
}
