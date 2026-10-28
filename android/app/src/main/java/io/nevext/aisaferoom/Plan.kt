package io.nevext.aisaferoom

/**
 * What the house pays for. The subscription is the house's (one owner pays on Google Play, it counts for everyone in
 * it); the family is always free. Kept in homes/{id}.plan = { tier, until } and written only by the server, after it
 * checks the purchase with the Play (firestore.rules don't let the app touch it); the app only reads.
 *
 * Until the app is on the Play there is no plan in the house: everyone is "teste", with everything on.
 */
data class Plan(val tier: String, val until: Long?) {
    /** A paid plan or the 30-day trial past its date is back to Grátis (the app stays, only the extras stop). */
    val effective: String get() = if (until != null && until < System.currentTimeMillis()) "gratis" else tier

    val label: String get() = LABELS[effective] ?: "Teste"

    /** Cameras in the house. */
    val cameras: Int get() = when (effective) { "gratis" -> 1; "essencial" -> 2; else -> 5 }

    /** How long clips stay (0: no clips). */
    val clipDays: Int get() = when (effective) { "gratis" -> 0; "essencial" -> 7; else -> 30 }

    fun allows(f: Feature): Boolean = when (effective) {
        "gratis" -> false
        "essencial" -> f in setOf(Feature.Ai, Feature.Away, Feature.Clips)
        else -> true
    }

    enum class Feature { Ai, Away, Clips, Faces, Follow }

    companion object {
        val TEST = Plan("teste", null)
        private val LABELS = mapOf("teste" to "Teste", "gratis" to "Grátis", "essencial" to "Essencial", "familia" to "Família")

        @Suppress("UNCHECKED_CAST")
        fun from(data: Any?): Plan = (data as? Map<String, Any?>)?.let { p ->
            Plan(p["tier"] as? String ?: "teste", (p["until"] as? Number)?.toLong())
        } ?: TEST
    }
}
