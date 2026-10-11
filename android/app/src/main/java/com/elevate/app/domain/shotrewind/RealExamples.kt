package com.elevate.app.domain.shotrewind

/** One `comparisons.response.v1` candidate (contracts §8). */
data class ComparisonCandidate(
    val shotId: String,
    val clipId: String,
    val distance: Double,
    val matchedFeatures: List<String>,
    val role: String,
    val evidenceWon: Int,
    val evidenceOf: Int,
    val provenance: String,
    val attribution: String?,
)

/** A real clip to show beside the player's own, with the labels the screen must carry. */
data class RealExample(
    val candidate: ComparisonCandidate,
    /** Whose footage: `YOUR MATCH`, `SHARED BY …`, `TEAM REFERENCE`, `PUBLIC · …`. */
    val sourceLabel: String,
    /** `SIMILAR MOMENT`, `WORKED FOR YOU · n/N` or `COACH PICK`. */
    val roleLabel: String,
)

/** Real examples to show, and whether the server only had a SIMULATED stand-in. */
data class Examples(val real: List<RealExample>, val simulatedOnly: Boolean)

object RealExamples {
    private val PROVENANCE_ORDER = listOf("own", "shared", "team_reference", "public_cc")

    /**
     * Server candidates as labelled real examples, ordered as `12` §4 prefers (own, shared, team,
     * public CC; then nearest). A `simulated` candidate is never a real example. A candidate that
     * cannot carry an honest label is dropped: shared or public footage without attribution, an
     * unknown role or provenance, a `worked_for_you` without a valid count, or the query shot.
     */
    fun from(queryShotId: String, candidates: List<ComparisonCandidate>): Examples {
        val real = candidates.asSequence()
            .filter { it.role != "simulated" && it.shotId != queryShotId }
            .mapNotNull { c -> label(c)?.let { (s, r) -> RealExample(c, s, r) } }
            .sortedWith(compareBy({ PROVENANCE_ORDER.indexOf(it.candidate.provenance) }, { it.candidate.distance }))
            .toList()
        return Examples(real, simulatedOnly = real.isEmpty() && candidates.any { it.role == "simulated" })
    }

    private fun label(c: ComparisonCandidate): Pair<String, String>? {
        val who = c.attribution?.takeIf { it.isNotBlank() }
        val source = when (c.provenance) {
            "own" -> "YOUR MATCH"
            "shared" -> who?.let { "SHARED BY $it" } ?: return null
            "team_reference" -> "TEAM REFERENCE"
            "public_cc" -> who?.let { "PUBLIC · $it" } ?: return null
            else -> return null
        }
        val role = when (c.role) {
            "similar_moment" -> "SIMILAR MOMENT"
            "worked_for_you" ->
                if (c.evidenceOf > 0 && c.evidenceWon in 0..c.evidenceOf) "WORKED FOR YOU · ${c.evidenceWon}/${c.evidenceOf}" else return null
            "coach_pick" -> "COACH PICK"
            else -> return null
        }
        return source to role
    }
}
