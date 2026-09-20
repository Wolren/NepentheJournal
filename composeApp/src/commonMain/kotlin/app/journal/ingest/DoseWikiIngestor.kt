package app.journal.ingest

import app.journal.data.AppJson
import app.journal.data.JournalRepository
import app.journal.data.IJournalRepository
import app.journal.log.Log
import app.journal.model.*
import app.journal.util.currentTimeMillis
import app.journal.util.readBundledResource
import kotlinx.serialization.json.Json

/**
 * Ingests the bundled DoseWiki slim data into the repository.
 *
 * DoseWiki is the PRIMARY substance entity for Nepenthe Journal.
 * Every field DoseWiki carries wins over the PsychonautWiki seed:
 * matched substances are overwritten (not merely filled when empty),
 * and DoseWiki substances with no seed match are created as dw:{slug}
 * rows. The PW seed remains only as a fallback base for substances
 * DoseWiki does not cover.
 *
 * Data sourced (in priority order):
 * 1. DoseWiki open data (https://dose.wiki/open-data/SubstanceIndex.json
 *    plus the live v1 API at https://dose.wiki/api/v1): summary,
 *    classes, routes, dosage, duration, effects, interactions,
 *    harm potential, tolerance, chemistry. Substance prose is CC0 1.0;
 *    the interactions field keeps TripSit non-commercial attribution
 *    terms (recorded as sources ["dosewiki", "tripsit"]).
 * 2. PsychonautWiki seed: fallback for uncovered substances plus
 *    pharmacology enrichments (PubChem, ChEMBL, IUPHAR, PDSP, BindingDB).
 *
 * Call from DataInitializer after the PW seed has been loaded so
 * DoseWiki overwrites win.
 */
object DoseWikiIngestor {

    private const val RESOURCE_PATH = "/dosewiki_slim.json"

    /** Tag recorded in Substance.sources for DoseWiki-sourced rows. */
    const val SOURCE_TAG = "dosewiki"

    /** Source version stamped on substances DoseWiki writes. */
    const val SOURCE_VERSION = "dw-v1"

    private val json = AppJson.json

    private var ingested = false

    /** Reset the ingested flag — only needed for testing. */
    internal fun reset() {
        ingested = false
    }

    /**
     * Load and ingest DoseWiki data. Safe to call multiple times —
     * second call is a no-op.
     */
    fun ensureIngested(repo: IJournalRepository) {
        if (ingested) return

        val text = readBundledResource(RESOURCE_PATH)
            ?: run {
                Log.withTag("DoseWiki").w { "Resource $RESOURCE_PATH not found" }
                return
            }
        // Per-entry lenient decode: one malformed upstream entry must
        // never kill the whole ingest (proven 2026-09-20: a single
        // list-shaped subjective_effects.cognitive failed all 577).
        val substances: List<DoseWikiSubstance> = try {
            val elements = json.decodeFromString<
                kotlinx.serialization.json.JsonArray>(text)
            var skipped = 0
            elements.mapNotNull { element ->
                try {
                    json.decodeFromJsonElement(
                        DoseWikiSubstance.serializer(), element)
                } catch (e: Exception) {
                    skipped++
                    null
                }
            }.also { parsed ->
                if (skipped > 0) {
                    Log.withTag("DoseWiki").w {
                        "Skipped $skipped malformed slim entries, " +
                        "ingested ${parsed.size}"
                    }
                }
            }
        } catch (e: Exception) {
            Log.withTag("DoseWiki").e { "Failed to parse slim data: ${e.message}" }
            return
        }

        val now = currentTimeMillis()
        var totalEffectCount = 0
        var substanceUpdateCount = 0
        var substanceCreateCount = 0
        var interactionCount = 0

        // Mutable name lookup: grows as dw: rows are created so
        // interaction targets resolve regardless of entry order.
        val lookupByName = buildLookup(repo)

        // Pass 1: substances plus effects (creates dw: rows, overwrites
        // matched seed rows). Collect (entry, substanceId) for pass 2.
        val ingestedPairs = mutableListOf<Pair<DoseWikiSubstance, String>>()
        for (dw in substances) {
            val name = dw.title
            if (name.isBlank()) continue
            val existing = findSubstance(lookupByName, name, dw.identification?.alternative_names.orEmpty())

            val effectNames = mutableListOf<String>()
            if (existing == null) {
                val id = idFor(dw)
                totalEffectCount += ingestAllEffects(repo, dw, id, now, effectNames)
                val created = buildPrimarySubstance(dw, id, effectNames, now)
                repo.upsertSubstance(created)
                register(lookupByName, created)
                substanceCreateCount++
                ingestedPairs.add(dw to id)
            } else {
                totalEffectCount += ingestAllEffects(repo, dw, existing.id, now, effectNames)
                val updated = applyPrimaryFields(existing, dw, effectNames, now)
                repo.upsertSubstance(updated)
                substanceUpdateCount++
                ingestedPairs.add(dw to existing.id)
            }
        }

        // Pass 2: interactions, now that every endpoint ID is registered.
        for ((dw, id) in ingestedPairs) {
            interactionCount += ingestInteractions(repo, lookupByName, dw, id, now)
        }

        ingested = true
        Log.withTag("DoseWiki").i {
            "DoseWiki: primary ingest $totalEffectCount effects, " +
            "$interactionCount interactions, $substanceCreateCount created, " +
            "$substanceUpdateCount updated ($RESOURCE_PATH)"
        }
    }

    /**
     * Canonical DoseWiki-first ID: dw:{slug}, slugified title fallback.
     */
    internal fun idFor(dw: DoseWikiSubstance): String {
        val slug = dw.slug?.takeIf { it.isNotBlank() }
            ?: dw.title.lowercase().replace("\\s+".toRegex(), "-")
        return "dw:$slug"
    }

    /**
     * Build a full primary Substance row from a DoseWiki entry that has
     * no seed match. Chemical enrichments (PubChem CID, ChEMBL, IUPHAR,
     * PDSP, BindingDB) stay null here; the PW fallback seed or a later
     * pipeline run fills them when the compound resolves.
     */
    private fun buildPrimarySubstance(
        dw: DoseWikiSubstance,
        id: String,
        effectNames: List<String>,
        now: Long,
    ): Substance = Substance(
        id = id,
        name = dw.title,
        aliases = aliasesOf(dw),
        summary = dw.summary?.takeIf { it.isNotBlank() },
        substanceClass = SubstanceClassNormalizer.normalize(classesOf(dw)),
        routesOfAdministration = routesOf(dw),
        dosageBands = dosageBands(dw),
        durationProfile = durationProfile(dw),
        addictionPotential = addictionOf(dw),
        toxicity = toxicityOf(dw),
        crossTolerances = crossTolerancesOf(dw),
        effects = effectNames.distinct(),
        interactionClasses = interactionClassesOf(dw),
        sources = listOf(SOURCE_TAG),
        cachedAt = now,
        sourceVersion = SOURCE_VERSION,
        createdAt = now,
        updatedAt = now,
        deviceOrigin = "system",
    )

    /**
     * Overwrite a seed-matched substance with DoseWiki primary fields.
     * Every field DoseWiki carries wins; empty DoseWiki fields keep the
     * existing seed value so pharmacology enrichments survive.
     */
    private fun applyPrimaryFields(
        existing: Substance,
        dw: DoseWikiSubstance,
        effectNames: List<String>,
        now: Long,
    ): Substance {
        val dwBands = dosageBands(dw)
        val dwDuration = durationProfile(dw)
        val dwToxicity = toxicityOf(dw)
        val dwCross = crossTolerancesOf(dw)
        val dwRoutes = routesOf(dw)
        val dwClasses = SubstanceClassNormalizer.normalize(classesOf(dw))
        val dwInteractionClasses = interactionClassesOf(dw)
        return existing.copy(
            summary = dw.summary?.takeIf { it.isNotBlank() } ?: existing.summary,
            aliases = (existing.aliases + aliasesOf(dw)).distinct(),
            substanceClass = if (dwClasses.isNotEmpty()) dwClasses else existing.substanceClass,
            routesOfAdministration = if (dwRoutes.isNotEmpty()) dwRoutes else existing.routesOfAdministration,
            dosageBands = if (dwBands.isNotEmpty()) dwBands else existing.dosageBands,
            durationProfile = if (dwDuration.isNotEmpty()) dwDuration else existing.durationProfile,
            addictionPotential = addictionOf(dw) ?: existing.addictionPotential,
            toxicity = if (dwToxicity.isNotEmpty()) dwToxicity else existing.toxicity,
            crossTolerances = if (dwCross.isNotEmpty()) dwCross else existing.crossTolerances,
            effects = if (effectNames.isNotEmpty()) effectNames.distinct() else existing.effects,
            interactionClasses = if (dwInteractionClasses.isNotEmpty()) dwInteractionClasses else existing.interactionClasses,
            sources = (existing.sources + SOURCE_TAG).distinct(),
            sourceVersion = SOURCE_VERSION,
            updatedAt = now,
        )
    }

    // ------------------------------------------------------------------
    // Field extractors (DoseWiki slim shape -> Substance fields)
    // ------------------------------------------------------------------

    private fun aliasesOf(dw: DoseWikiSubstance): List<String> =
        dw.identification?.alternative_names.orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals(dw.title, ignoreCase = true) }
            .distinct()

    private fun classesOf(dw: DoseWikiSubstance): List<String> =
        (dw.classification?.chemical_class.orEmpty() +
            dw.classification?.psychoactive_class.orEmpty())
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun interactionClassesOf(dw: DoseWikiSubstance): List<String> =
        dw.classification?.psychoactive_class.orEmpty()
            .map { it.lowercase().replace(" ", "_") }
            .filter { it in InteractionClasses.ALL }

    private fun routesOf(dw: DoseWikiSubstance): List<String> =
        dw.dosage?.routes.orEmpty()
            .mapNotNull { it.route?.trim()?.takeIf { r -> r.isNotBlank() } }
            .distinct()

    /** Primary route: oral first, else the first route with dose ranges. */
    private fun primaryDoseRoute(dw: DoseWikiSubstance): DoseWikiRoute? {
        val routes = dw.dosage?.routes.orEmpty()
        if (routes.isEmpty()) return null
        routes.firstOrNull { it.route.equals("oral", ignoreCase = true) }?.let { return it }
        return routes.firstOrNull { it.dose_ranges != null } ?: routes.first()
    }

    private fun primaryDurationRoute(dw: DoseWikiSubstance): DoseWikiDurationRoute? {
        val routes = dw.duration?.routes.orEmpty()
        if (routes.isEmpty()) return null
        routes.firstOrNull { it.route.equals("oral", ignoreCase = true) }?.let { return it }
        return routes.firstOrNull { it.stages != null } ?: routes.first()
    }

    private fun fmtNum(v: Double): String =
        if (v == kotlin.math.floor(v)) v.toLong().toString() else v.toString()

    private fun fmtDose(r: DoseWikiRange?): String? {
        if (r?.min == null) return null
        val unit = r.unit?.trim().orEmpty()
        val suffix = if (unit.isNotBlank()) " $unit" else ""
        return if (r.max != null && r.max != r.min) {
            "${fmtNum(r.min!!)}-${fmtNum(r.max!!)}$suffix"
        } else {
            "${fmtNum(r.min!!)}$suffix"
        }
    }

    /** Flat band map, same shape the PW ingestor writes (moderate -> common). */
    private fun dosageBands(dw: DoseWikiSubstance): Map<String, String> {
        val ranges = primaryDoseRoute(dw)?.dose_ranges ?: return emptyMap()
        return buildMap {
            fmtDose(ranges.threshold)?.let { put("threshold", it) }
            fmtDose(ranges.light)?.let { put("light", it) }
            fmtDose(ranges.moderate)?.let { put("common", it) }
            fmtDose(ranges.strong)?.let { put("strong", it) }
            fmtDose(ranges.heavy)?.let { put("heavy", it) }
        }
    }

    private fun fmtStage(s: DoseWikiStage?): String? {
        if (s?.min == null) return null
        val unit = s.unit?.trim().orEmpty()
        val suffix = if (unit.isNotBlank()) " $unit" else ""
        return if (s.max != null && s.max != s.min) {
            "${fmtNum(s.min!!)}-${fmtNum(s.max!!)}$suffix"
        } else {
            "${fmtNum(s.min!!)}$suffix"
        }
    }

    /** Flat duration map, same keys the PW ingestor writes. */
    private fun durationProfile(dw: DoseWikiSubstance): Map<String, String> {
        val stages = primaryDurationRoute(dw)?.stages ?: return emptyMap()
        return buildMap {
            fmtStage(stages.onset)?.let { put("onset", it) }
            fmtStage(stages.come_up)?.let { put("comeup", it) }
            fmtStage(stages.peak)?.let { put("peak", it) }
            fmtStage(stages.offset)?.let { put("offset", it) }
            fmtStage(stages.after_effects)?.let { put("afterglow", it) }
            fmtStage(stages.total_duration)?.let { put("total", it) }
        }
    }

    private fun addictionOf(dw: DoseWikiSubstance): String? {
        val item = dw.harm_potential?.addiction ?: return null
        return item.description?.takeIf { it.isNotBlank() }
            ?: item.level?.takeIf { it.isNotBlank() }
    }

    private fun toxicityOf(dw: DoseWikiSubstance): List<String> {
        val harm = dw.harm_potential ?: return emptyList()
        val out = mutableListOf<String>()
        harm.toxicity?.organ_toxicity.orEmpty().forEach { organ ->
            val finding = organ.findings?.trim().orEmpty()
            val system = organ.system?.trim().orEmpty()
            when {
                system.isNotBlank() && finding.isNotBlank() -> out.add("$system: $finding")
                finding.isNotBlank() -> out.add(finding)
                system.isNotBlank() -> out.add(system)
            }
        }
        harm.psychosis?.description?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        harm.seizure?.description?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        return out
    }

    private fun crossTolerancesOf(dw: DoseWikiSubstance): List<String> =
        dw.tolerance?.cross_tolerance.orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    // ------------------------------------------------------------------
    // Effects
    // ------------------------------------------------------------------

    private fun ingestAllEffects(
        repo: IJournalRepository,
        dw: DoseWikiSubstance,
        substanceId: String,
        now: Long,
        effectNames: MutableList<String>,
    ): Int {
        var count = 0
        val se = dw.subjective_effects ?: return 0
        count += ingestEffectCategory(repo, se.cognitive, "cognitive", substanceId, now, effectNames)
        count += ingestEffectCategory(repo, se.physical, "physical", substanceId, now, effectNames)
        se.sensory?.let { sensory ->
            listOfNotNull(
                sensory.auditory, sensory.gustatory, sensory.tactile,
                sensory.visual, sensory.olfactory, sensory.multisensory
            ).forEach { cat ->
                cat.subcategories?.forEach { (_, group) ->
                    group.effects?.forEach { eff ->
                        effectNames.add(eff.name)
                        upsertEffect(repo, eff, "sensory", substanceId, now)
                        count++
                    }
                }
            }
        }
        return count
    }

    // ------------------------------------------------------------------
    // Interactions (dangerous/unsafe/caution with reasons)
    // ------------------------------------------------------------------

    private fun interactionId(a: String, b: String): String {
        val sorted = listOf(a, b).sorted()
        return "interaction:${sorted[0]}:${sorted[1]}"
    }

    /**
     * Split a DoseWiki interaction entry of the form "Name (reason text)"
     * into target name plus reason. Entries without a reason keep a null
     * description.
     */
    internal fun parseInteractionEntry(raw: String): Pair<String, String?> {
        val open = raw.indexOf(" (")
        if (open < 0) return raw.trim() to null
        val name = raw.substring(0, open).trim()
        var reason = raw.substring(open + 2).trim()
        if (reason.endsWith(")")) reason = reason.dropLast(1).trim()
        if (name.isEmpty()) return raw.trim() to null
        return name to reason.takeIf { it.isNotBlank() }
    }

    /**
     * Ingest DoseWiki interaction lists. Targets resolve against every
     * known substance (seed rows plus dw: rows created this run).
     * Unresolvable entries (class-level names like "Amphetamines") are
     * skipped: no placeholder nodes, the deterministic class checker
     * already covers those. DoseWiki interactions derive from TripSit
     * guidance, so sources keep both tags.
     */
    private fun ingestInteractions(
        repo: IJournalRepository,
        lookup: Map<String, Substance>,
        dw: DoseWikiSubstance,
        sourceId: String,
        now: Long,
    ): Int {
        val lists = dw.interactions ?: return 0
        var count = 0
        count += ingestInteractionList(repo, lookup, lists.dangerous, InteractionRisk.DANGEROUS, sourceId, now)
        count += ingestInteractionList(repo, lookup, lists.unsafe, InteractionRisk.UNSAFE, sourceId, now)
        count += ingestInteractionList(repo, lookup, lists.caution, InteractionRisk.UNCERTAIN, sourceId, now)
        return count
    }

    private fun ingestInteractionList(
        repo: IJournalRepository,
        lookup: Map<String, Substance>,
        entries: List<String>?,
        risk: InteractionRisk,
        sourceId: String,
        now: Long,
    ): Int {
        var count = 0
        entries.orEmpty().forEach { raw ->
            if (raw.isBlank()) return@forEach
            val (targetName, reason) = parseInteractionEntry(raw)
            val target = lookup[targetName.lowercase()] ?: return@forEach
            if (target.id == sourceId) return@forEach
            val sorted = listOf(sourceId, target.id).sorted()
            repo.upsertInteraction(
                Interaction(
                    id = interactionId(sourceId, target.id),
                    substanceAId = sorted[0],
                    substanceBId = sorted[1],
                    riskLevel = risk,
                    description = reason,
                    sources = listOf(SOURCE_TAG, "tripsit"),
                    createdAt = now,
                    updatedAt = now,
                )
            )
            count++
        }
        return count
    }

    // ------------------------------------------------------------------
    // Lookup helpers
    // ------------------------------------------------------------------

    /**
     * Build an O(1) name-to-substance lookup from name + aliases.
     */
    private fun buildLookup(repo: IJournalRepository): MutableMap<String, Substance> =
        buildMap<String, Substance> {
            for (sub in repo.substances.value) {
                put(sub.name.lowercase(), sub)
                for (alias in sub.aliases) {
                    putIfAbsent(alias.lowercase(), sub)
                }
            }
        }.toMutableMap()

    private fun register(lookup: MutableMap<String, Substance>, sub: Substance) {
        lookup[sub.name.lowercase()] = sub
        for (alias in sub.aliases) {
            lookup.putIfAbsent(alias.lowercase(), sub)
        }
    }

    /**
     * Find a substance by exact name or alias match using O(1) map lookup.
     */
    private fun findSubstance(
        lookup: Map<String, Substance>,
        name: String,
        aliases: List<String>,
    ): Substance? {
        lookup[name.lowercase()]?.let { return it }
        for (alias in aliases) {
            lookup[alias.lowercase()]?.let { return it }
        }
        return null
    }

    /**
     * Ingest effects from a single category (cognitive/physical) into the repo.
     * Returns the number of effects ingested.
     */
    private fun ingestEffectCategory(
        repo: IJournalRepository,
        effects: Map<String, DoseWikiEffectGroup>?,
        category: String,
        substanceId: String,
        now: Long,
        effectNames: MutableList<String>,
    ): Int {
        var count = 0
        effects?.forEach { (_, group) ->
            group.effects?.forEach { eff ->
                effectNames.add(eff.name)
                upsertEffect(repo, eff, category, substanceId, now)
                count++
            }
        }
        return count
    }

    /**
     * Upsert an Effect document from a DoseWiki effect entry.
     */
    private fun upsertEffect(
        repo: IJournalRepository,
        eff: DoseWikiEffect,
        category: String,
        substanceId: String,
        now: Long,
    ) {
        val id = "effect:dw:${substanceId}:${eff.name.hashCode().toLong() and 0x7FFFFFFF}"
        val effect = Effect(
            id = id,
            name = eff.name,
            description = eff.description?.takeIf { it.isNotBlank() },
            category = category,
            substanceIds = listOf(substanceId),
            url = null,
            createdAt = now,
            updatedAt = now,
            deviceOrigin = "system",
        )
        repo.upsertEffect(effect)
    }
}
