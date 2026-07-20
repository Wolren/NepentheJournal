package app.journal.data

import app.journal.log.Log
import app.journal.model.*
import app.journal.util.currentTimeMillis
import kotlin.random.Random

/**
 * Generates rich session test data for debugging.
 *
 * Uses substances already loaded from seed or disk (never creates its own).
 * If the repo has fewer than 2 substances, generation is skipped.
 */
object FuzzSeed {

    private val rng = Random(42) // deterministic seed for reproducibility

    // Consumer names for multi-consumer testing
    private val consumers = listOf(null, "Alex", "Sam", "Jordan", "Casey")

    // Set/mindset pool
    private val sets = listOf(
        "Curious and open",
        "Anxious but excited",
        "Well-rested and calm",
        "Tired from work, seeking insight",
        "Happy and energetic",
        "Reflective, contemplative mood",
        "Slightly nervous, good company",
        "Feeling creative and inspired",
        "Peaceful, meditative state",
        "Social and talkative"
    )

    // Setting/environment pool
    private val settings = listOf(
        "My apartment, dim lighting, ambient music",
        "Botanical gardens, sunny afternoon",
        "Beach at sunset with friends",
        "Cozy living room, candles, soft blankets",
        "Nature trail through the forest",
        "Electronic music festival",
        "Studio with art supplies and instruments",
        "Rooftop overlooking the city at night",
        "Quiet cabin in the woods",
        "Friend's house, comfortable living room"
    )

    // Intention pool
    private val intentions = listOf(
        "Process recent life changes and gain clarity",
        "Explore creative flow for music production",
        "Deepen emotional connection with partner",
        "Nature appreciation and group bonding",
        "Therapeutic self-discovery",
        "Have fun at the festival",
        "Meditation and spiritual exploration",
        "Work through anxiety patterns",
        "Celebrate a milestone with friends",
        "Microdose for focus and productivity"
    )

    // Outcome pool
    private val outcomes = listOf(
        "Profound emotional release, cried for 20 minutes, felt lighter afterwards",
        "Beautiful day. Connected deeply with friends. The flowers were breathing fractal patterns.",
        "Spent hours talking about our relationship. Very healing experience.",
        "Produced three musical ideas. The dissociation helped me hear mixes differently.",
        "Filled 6 pages in journal. Good insights about work-life balance.",
        "Intense visuals but overwhelming at times. Learned to surrender.",
        "Gentle, pleasant experience. Good conversations and laughter.",
        "Challenging comeup but beautiful peak. Felt reborn after.",
        "Mild effects, mostly enhanced sensory perception. Nice walk in nature.",
        "Deep introspective journey. Faced some difficult emotions but came out stronger."
    )

    // ROA pool
    private val roas = listOf("Oral", "Sublingual", "Insufflated", "Vaporized", "Inhaled", "Intramuscular")

    /**
     * Generates and inserts session test data into the repository.
     * Uses existing substances (from seed/disk) only -- will not create fake ones.
     * Skips entirely if fewer than 2 substances are present.
     */
    fun generate(repo: JournalRepository) {
        try {
            val now = currentTimeMillis()
            val dayMs = 86400000L
            val hourMs = 3600000L

            val substances = repo.substances.value
            if (substances.size < 2) {
                Log.withTag("FuzzSeed").w { "Need at least 2 substances, have ${substances.size}. Skipping." }
                return
            }

            // --- Generate sessions spanning 6 months ---
            val sixMonthsAgo = now - 180L * dayMs
            val sessionCount = 25 + rng.nextInt(15) // 25-39 sessions

            val sessions = mutableListOf<Session>()
            val allDoses = mutableListOf<Dose>()
            val allTimelineEvents = mutableListOf<TimelineEvent>()

            for (i in 0 until sessionCount) {
                val sessionStart = sixMonthsAgo + rng.nextLong(170L * dayMs)
                val durationHours = (2 + rng.nextInt(10)).toLong()
                val sessionEnd = sessionStart + durationHours * hourMs
                val consumer = consumers[rng.nextInt(consumers.size)]
                val rating = if (rng.nextFloat() < 0.85f) 3 + rng.nextInt(8) else null

                // Pick 1-3 substances for this session
                val comboSize = if (rng.nextFloat() < 0.3f) 2 else if (rng.nextFloat() < 0.1f) 3 else 1
                val comboSubstances = substances.shuffled(rng).take(comboSize)

                val session = Session(
                    id = "session:fuzz_${i}",
                    title = generateTitle(comboSubstances, i),
                    startTime = sessionStart,
                    endTime = sessionEnd,
                    set = sets[rng.nextInt(sets.size)],
                    setting = settings[rng.nextInt(settings.size)],
                    intention = intentions[rng.nextInt(intentions.size)],
                    outcome = if (rng.nextFloat() < 0.7f) outcomes[rng.nextInt(outcomes.size)] else null,
                    rating = rating,
                    checkins = (1..rng.nextInt(0, 4)).map { ci ->
                        CheckIn(
                            timestamp = sessionStart + ci * durationHours * hourMs / 4,
                            overallIntensity = (3 + rng.nextInt(8)).toFloat(),
                            effectScores = mapOf(
                                "visuals" to rng.nextInt(10).toFloat(),
                                "euphoria" to rng.nextInt(10).toFloat(),
                                "body_load" to rng.nextInt(10).toFloat()
                            ),
                            mood = listOf("calm", "focused", "euphoric", "introspective", "tired", "awestruck")[rng.nextInt(6)],
                            notes = if (rng.nextFloat() < 0.3f) "Feeling good, music is amazing" else null
                        )
                    },
                    isFavorite = rng.nextFloat() < 0.2f,
                    consumerName = consumer,
                    createdAt = sessionStart - dayMs,
                    updatedAt = sessionStart,
                    deviceOrigin = "fuzz"
                )
                sessions.add(session)

                // Doses for each substance in the combo
                comboSubstances.forEachIndexed { idx, sub ->
                    val doseCount = if (rng.nextFloat() < 0.2f) 2 else 1
                    for (d in 0 until doseCount) {
                        val amt = when {
                            sub.name.contains("LSD") -> 50.0 + rng.nextInt(250)
                            sub.name.contains("Psilocybin") || sub.name.contains("psilocybin") -> 0.5 + rng.nextDouble(4.0)
                            sub.name.contains("MDMA") -> 60.0 + rng.nextInt(100)
                            sub.name.contains("Ketamine") || sub.name.contains("ketamine") -> 10.0 + rng.nextInt(60)
                            sub.name.contains("Cannabis") || sub.name.contains("cannabis") -> 5.0 + rng.nextInt(25)
                            else -> 10.0 + rng.nextDouble(200.0)
                        }
                        allDoses.add(Dose(
                            id = "dose:fuzz_${i}_${idx}_${d}",
                            sessionId = session.id,
                            substanceId = sub.id,
                            routeOfAdministration = roas[rng.nextInt(roas.size)],
                            amount = (amt * 100).toLong() / 100.0,
                            unit = if (amt < 1) "mg" else if (sub.name.contains("LSD")) "\u00B5g" else "mg",
                            timestamp = sessionStart + if (d > 0) hourMs * (1 + rng.nextInt(3)) else 0L,
                            redosing = d > 0,
                            isDoseEstimate = rng.nextFloat() < 0.15f,
                            estimatedDoseStandardDeviation = if (rng.nextFloat() < 0.15f) rng.nextDouble(amt * 0.2).let { (it * 100).toLong() / 100.0 } else null,
                            createdAt = sessionStart - dayMs,
                            updatedAt = sessionStart,
                            deviceOrigin = session.deviceOrigin
                        ))
                    }
                }

                // Timeline events for some sessions
                if (rng.nextFloat() < 0.5f) {
                    val eventTypes = TimelineEventType.entries
                    val baseTime = sessionStart
                    val eventLabels = listOf("First effects", "Coming up", "At peak", "Starting to come down", "Afterglow")
                    (0..rng.nextInt(1, 4)).forEach { ei ->
                        allTimelineEvents.add(TimelineEvent(
                            id = "event:fuzz_${i}_${ei}",
                            sessionId = session.id,
                            timestamp = baseTime + ei * durationHours * hourMs / 4,
                            eventType = eventTypes[ei.coerceAtMost(eventTypes.size - 1)],
                            label = eventLabels[ei.coerceAtMost(eventLabels.size - 1)],
                            body = if (rng.nextFloat() < 0.4f) "Noticing visual patterns emerging" else null,
                            intensity = if (rng.nextFloat() < 0.5f) (3 + rng.nextInt(7)).toFloat() else null,
                            createdAt = sessionStart,
                            updatedAt = sessionStart,
                            deviceOrigin = session.deviceOrigin
                        ))
                    }
                }
            }

            // Insert everything in a single bulk pass
            repo.bulkInsert(
                sessions = sessions,
                doses = allDoses,
                timelineEvents = allTimelineEvents
            )

            // Add interactions from the real seed data for substances present
            if (substances.size >= 3) {
                val shuffled = substances.shuffled(rng)
                for (i in 0 until (shuffled.size / 2).coerceAtMost(10)) {
                    val a = shuffled[i * 2]
                    val b = shuffled[i * 2 + 1]
                    val risk = InteractionRisk.entries[rng.nextInt(InteractionRisk.entries.size)]
                    repo.upsertInteraction(Interaction(
                        id = "interaction:fuzz_${a.id}_${b.id}",
                        substanceAId = a.id,
                        substanceBId = b.id,
                        riskLevel = risk,
                        description = "Generated interaction for testing",
                        sources = listOf("fuzz"),
                        createdAt = now,
                        updatedAt = now,
                        deviceOrigin = "fuzz"
                    ))
                }
            }
        } catch (e: Exception) {
            Log.withTag("FuzzSeed").e(e) { "Fuzz generation failed" }
        }
    }

    private fun generateTitle(substances: List<Substance>, index: Int): String {
        val prefix = listOf(
            "Evening", "Afternoon", "Morning", "Late night", "Sunset",
            "Day trip", "Night", "Weekend", "Solo", "Group"
        )
        val suffix = listOf(
            "exploration", "session", "journey", "experience", "adventure",
            "reflection", "hang", "vibes", "trip", "ceremony"
        )
        val subNames = substances.joinToString(" + ") { it.name }
        return "${prefix[rng.nextInt(prefix.size)]} $subNames ${suffix[rng.nextInt(suffix.size)]}"
    }
}
