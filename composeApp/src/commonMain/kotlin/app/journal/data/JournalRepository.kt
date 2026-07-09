package app.journal.data

import app.journal.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlin.random.Random

/**
 * In-memory journal repository. Uses same VaultRepository<T> interface pattern
 * that the Couchbase Lite layer will follow. Swapping to real CBL at runtime
 * means implementing VaultRepository<T> against CBL queries — the UI code
 * never changes because it talks to this abstraction.
 */
class JournalRepository private constructor() {

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _substances = MutableStateFlow<List<Substance>>(emptyList())
    val substances: StateFlow<List<Substance>> = _substances.asStateFlow()

    private val _doses = MutableStateFlow<List<Dose>>(emptyList())
    val doses: StateFlow<List<Dose>> = _doses.asStateFlow()

    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    private val _timelineEvents = MutableStateFlow<List<TimelineEvent>>(emptyList())
    val timelineEvents: StateFlow<List<TimelineEvent>> = _timelineEvents.asStateFlow()

    val recentSessions: Flow<List<Session>> = sessions.map { list ->
        list.sortedByDescending { it.startTime }.take(5)
    }

    val totalSessionCount: Flow<Int> = sessions.map { it.size }
    val totalSubstanceCount: Flow<Int> = substances.map { it.size }
    val pendingConflictCount: Flow<Int> = notes.map { list ->
        list.count { it.conflictSiblings.isNotEmpty() }
    }

    // --- Sessions ---

    fun upsertSession(session: Session) {
        _sessions.value = _sessions.value
            .filter { it.id != session.id }
            .plus(session)
    }

    fun getSession(id: String): Session? =
        _sessions.value.find { it.id == id }

    fun deleteSession(id: String) {
        _sessions.value = _sessions.value.filter { it.id != id }
        _doses.value = _doses.value.filter { it.sessionId != id }
        _notes.value = _notes.value.filter { it.sessionId != id }
        _timelineEvents.value = _timelineEvents.value.filter { it.sessionId != id }
    }

    // --- Doses ---

    fun upsertDose(dose: Dose) {
        _doses.value = _doses.value
            .filter { it.id != dose.id }
            .plus(dose)
    }

    fun dosesForSession(sessionId: String): List<Dose> =
        _doses.value.filter { it.sessionId == sessionId }

    // --- Substances ---

    fun upsertSubstance(substance: Substance) {
        _substances.value = _substances.value
            .filter { it.id != substance.id }
            .plus(substance)
    }

    fun getSubstance(id: String): Substance? =
        _substances.value.find { it.id == id }

    fun searchSubstances(query: String): List<Substance> {
        val q = query.lowercase()
        return _substances.value.filter {
            it.name.lowercase().contains(q) ||
            it.aliases.any { a -> a.lowercase().contains(q) }
        }
    }

    // --- Notes ---

    fun upsertNote(note: Note) {
        _notes.value = _notes.value
            .filter { it.id != note.id }
            .plus(note)
    }

    fun notesForSession(sessionId: String): List<Note> =
        _notes.value.filter { it.sessionId == sessionId }

    // --- Timeline ---

    fun upsertTimelineEvent(event: TimelineEvent) {
        _timelineEvents.value = _timelineEvents.value
            .filter { it.id != event.id }
            .plus(event)
    }

    fun eventsForSession(sessionId: String): List<TimelineEvent> =
        _timelineEvents.value.filter { it.sessionId == sessionId }
            .sortedBy { it.timestamp }

    // --- Seed data for development ---

    fun seedSampleData() {
        val now = 1725000000000L  // fixed seed timestamp
        val day = 86400000L

        // Sample substances
        listOf(
            Substance(
                id = "sub:lsd", name = "LSD", aliases = listOf("Acid", "Lucy"),
                summary = "A classical psychedelic from the lysergamide family, known for profound alterations of consciousness.",
                substanceClass = listOf("Classical Psychedelic", "Lysergamide"),
                routesOfAdministration = listOf("Oral", "Sublingual"),
                dosageBands = mapOf(
                    "threshold" to "15-25 µg", "light" to "25-75 µg",
                    "common" to "75-150 µg", "strong" to "150-300 µg",
                    "heavy" to "300+ µg"
                ),
                durationProfile = mapOf(
                    "onset" to "30-90 min", "comeup" to "1-2 h",
                    "peak" to "3-5 h", "offset" to "3-5 h",
                    "afterglow" to "6-12 h", "total" to "8-14 h"
                ),
                addictionPotential = "Very low",
                toxicity = listOf("Psychological distress in vulnerable individuals"),
                cachedAt = now, sourceVersion = "pwiki-v1",
                createdAt = now, updatedAt = now
            ),
            Substance(
                id = "sub:psilocybin", name = "Psilocybin", aliases = listOf("Magic Mushrooms", "Shrooms"),
                summary = "A naturally occurring psychedelic prodrug found in Psilocybe mushrooms.",
                substanceClass = listOf("Classical Psychedelic", "Tryptamine"),
                routesOfAdministration = listOf("Oral"),
                dosageBands = mapOf(
                    "threshold" to "0.25-0.5 g", "light" to "0.5-1.5 g",
                    "common" to "1.5-3.5 g", "strong" to "3.5-7 g",
                    "heavy" to "7+ g"
                ),
                durationProfile = mapOf(
                    "onset" to "20-60 min", "comeup" to "30-90 min",
                    "peak" to "2-3 h", "offset" to "1-2 h",
                    "total" to "4-7 h"
                ),
                addictionPotential = "Very low",
                toxicity = listOf("Psychological distress", "HPPD risk"),
                cachedAt = now, sourceVersion = "pwiki-v1",
                createdAt = now, updatedAt = now
            ),
            Substance(
                id = "sub:mdma", name = "MDMA", aliases = listOf("Molly", "Ecstasy", "E"),
                summary = "An empathogenic stimulant, primarily acting on serotonin release.",
                substanceClass = listOf("Empathogen", "Substituted Amphetamine"),
                routesOfAdministration = listOf("Oral", "Insufflated"),
                dosageBands = mapOf(
                    "threshold" to "30-50 mg", "light" to "50-80 mg",
                    "common" to "80-120 mg", "strong" to "120-180 mg",
                    "heavy" to "180+ mg"
                ),
                durationProfile = mapOf(
                    "onset" to "30-60 min", "comeup" to "30-45 min",
                    "peak" to "2-3 h", "offset" to "1-2 h",
                    "afterglow" to "12-24 h", "total" to "4-6 h"
                ),
                addictionPotential = "Moderate",
                toxicity = listOf("Serotonin syndrome", "Neurotoxicity risk with frequent use", "Hyperthermia"),
                cachedAt = now, sourceVersion = "pwiki-v1",
                createdAt = now, updatedAt = now
            ),
            Substance(
                id = "sub:ketamine", name = "Ketamine", aliases = listOf("K", "Special K", "Kitty"),
                summary = "A dissociative anesthetic with psychedelic properties at higher doses.",
                substanceClass = listOf("Dissociative", "Arylcyclohexylamine"),
                routesOfAdministration = listOf("Insufflated", "Intramuscular", "Oral", "Sublingual"),
                dosageBands = mapOf(
                    "threshold" to "5-15 mg (insufflated)", "light" to "15-30 mg",
                    "common" to "30-75 mg", "strong" to "75-150 mg",
                    "heavy" to "150+ mg"
                ),
                durationProfile = mapOf(
                    "onset" to "1-5 min (insufflated)", "comeup" to "5-15 min",
                    "peak" to "30-60 min", "offset" to "30-60 min",
                    "total" to "1-2 h"
                ),
                addictionPotential = "Moderate-High",
                toxicity = listOf("Bladder damage with frequent use", "Cognitive impairment"),
                cachedAt = now, sourceVersion = "pwiki-v1",
                createdAt = now, updatedAt = now
            ),
            Substance(
                id = "sub:cannabis", name = "Cannabis", aliases = listOf("Weed", "Marijuana", "THC"),
                summary = "A cannabinoid with psychoactive, sedative, and appetite-stimulating effects.",
                substanceClass = listOf("Cannabinoid"),
                routesOfAdministration = listOf("Inhaled", "Oral", "Vaporized"),
                dosageBands = mapOf(
                    "threshold" to "1-2.5 mg THC", "light" to "2.5-5 mg",
                    "common" to "5-15 mg", "strong" to "15-30 mg",
                    "heavy" to "30+ mg"
                ),
                durationProfile = mapOf(
                    "onset" to "1-10 min (inhaled)", "comeup" to "10-30 min",
                    "peak" to "1-3 h", "offset" to "1-2 h",
                    "total" to "2-6 h"
                ),
                addictionPotential = "Low-Moderate",
                toxicity = listOf("Anxiety in susceptible individuals", "CHS with heavy long-term use"),
                cachedAt = now, sourceVersion = "pwiki-v1",
                createdAt = now, updatedAt = now
            )
        ).forEach { upsertSubstance(it) }

        // Sample sessions
        val sessions = listOf(
            Session(
                id = "session:1", title = "Evening reflection with psilocybin",
                startTime = now - day * 3, endTime = now - day * 3 + 5 * 3600000L,
                tags = listOf("psilocybin", "introspective", "nature"),
                set = "Curious, calm, slightly tired from work",
                setting = "My apartment, sunset lighting, ambient music",
                intention = "To process recent life changes and gain clarity",
                outcome = "Profound emotional release — cried for 20 minutes, felt lighter afterwards",
                rating = 8, checkins = listOf(
                    CheckIn(now - day * 3 + 3600000L, 4.0f, mapOf("visuals" to 3f, "body_load" to 2f), "calm"),
                    CheckIn(now - day * 3 + 2 * 3600000L, 7.0f, mapOf("visuals" to 6f, "euphoria" to 8f), "awestruck"),
                ),
                createdAt = now - day * 4, updatedAt = now - day * 3, deviceOrigin = "desktop"
            ),
            Session(
                id = "session:2", title = "LSD at the botanical gardens",
                startTime = now - day * 10, endTime = now - day * 10 + 10 * 3600000L,
                tags = listOf("lsd", "outdoors", "social"),
                set = "Excited, well-rested",
                setting = "Public botanical gardens, sunny spring day with friends",
                intention = "Explore nature appreciation and group connection",
                outcome = "Beautiful day. Connected deeply with friends. The flowers were breathing fractal patterns.",
                rating = 9, isArchived = false,
                createdAt = now - day * 11, updatedAt = now - day * 10, deviceOrigin = "desktop"
            ),
            Session(
                id = "session:3", title = "MDMA with partner",
                startTime = now - day * 30, endTime = now - day * 30 + 5 * 3600000L,
                tags = listOf("mdma", "intimate", "therapeutic"),
                set = "Loving, open, communicative",
                setting = "Home, comfortable, dim lighting, soft music",
                intention = "Enhance emotional intimacy and communication",
                outcome = "Spent 4 hours talking about our relationship. Very healing. Took 120mg with 60mg redose.",
                rating = 10,
                createdAt = now - day * 31, updatedAt = now - day * 30, deviceOrigin = "mobile"
            ),
            Session(
                id = "session:4", title = "Low-dose ketamine for creativity",
                startTime = now - day * 5, endTime = now - day * 5 + 2 * 3600000L,
                tags = listOf("ketamine", "creative", "low_dose"),
                set = "Focused, creative",
                setting = "Studio with music production setup",
                intention = "Explore creative flow state",
                outcome = "Produced three musical ideas. The dissociation helped me hear mixes differently.",
                rating = 7,
                createdAt = now - day * 6, updatedAt = now - day * 5, deviceOrigin = "desktop"
            ),
            Session(
                id = "session:5", title = "Cannabis and journaling",
                startTime = now - day * 1, endTime = now - day * 1 + 3 * 3600000L,
                tags = listOf("cannabis", "journaling", "evening"),
                set = "Relaxed, reflective",
                setting = "Couch, tea, notebook",
                intention = "Weekly reflection and planning",
                outcome = "Filled 6 pages. Good insights about work-life balance.",
                rating = 6,
                createdAt = now - day * 2, updatedAt = now - day * 1, deviceOrigin = "mobile"
            )
        )
        sessions.forEach { upsertSession(it) }

        // Sample doses
        listOf(
            Dose("dose:1", sessionId = "session:1", substanceId = "sub:psilocybin",
                routeOfAdministration = "Oral", amount = 2.5, unit = "g dried",
                timestamp = now - day * 3, createdAt = now - day * 4, updatedAt = now - day * 3, deviceOrigin = "desktop"),
            Dose("dose:2", sessionId = "session:2", substanceId = "sub:lsd",
                routeOfAdministration = "Oral", amount = 150.0, unit = "µg",
                timestamp = now - day * 10, createdAt = now - day * 11, updatedAt = now - day * 10, deviceOrigin = "desktop"),
            Dose("dose:3", sessionId = "session:3", substanceId = "sub:mdma",
                routeOfAdministration = "Oral", amount = 120.0, unit = "mg",
                timestamp = now - day * 30, createdAt = now - day * 31, updatedAt = now - day * 30, deviceOrigin = "mobile"),
            Dose("dose:4", sessionId = "session:3", substanceId = "sub:mdma",
                routeOfAdministration = "Oral", amount = 60.0, unit = "mg",
                timestamp = now - day * 30 + 90 * 60000L, createdAt = now - day * 31, updatedAt = now - day * 30, deviceOrigin = "mobile",
                redosing = true),
            Dose("dose:5", sessionId = "session:4", substanceId = "sub:ketamine",
                routeOfAdministration = "Insufflated", amount = 25.0, unit = "mg",
                timestamp = now - day * 5, createdAt = now - day * 6, updatedAt = now - day * 5, deviceOrigin = "desktop"),
            Dose("dose:6", sessionId = "session:5", substanceId = "sub:cannabis",
                routeOfAdministration = "Vaporized", amount = 15.0, unit = "mg THC",
                timestamp = now - day * 1, createdAt = now - day * 2, updatedAt = now - day * 1, deviceOrigin = "mobile")
        ).forEach { upsertDose(it) }
    }

    companion object {
        val instance: JournalRepository by lazy {
            JournalRepository().apply { seedSampleData() }
        }
    }
}

