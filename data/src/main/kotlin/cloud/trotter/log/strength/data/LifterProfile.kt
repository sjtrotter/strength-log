package cloud.trotter.log.strength.data

/** The person, as the You screen edits them (design §4.2, #527): bodyweight in lb, stamped
 *  on each finished session; age, which turns on the older-adult restart rule for plans whose setup did not ask.
 *  Null is not entered. */
data class LifterProfile(val bodyweightLb: Double? = null, val age: Int? = null)
