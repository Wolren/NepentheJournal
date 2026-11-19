package app.journal.model

/**
 * Editorial popularity tiers, used only to break a search tie.
 *
 * Search already ranks an exact name above an analog; popularity decides the
 * order among equally good matches, so "2c" leads with 2C-B rather than
 * whichever analog sorts first alphabetically. The tiers are a curated
 * shortlist of the compounds the journal's readers actually talk about, not
 * measured usage data; a substance that is not listed ranks after every
 * listed one at the same match quality. Keys are catalog names in lowercase.
 */
object SubstancePopularity {

    /** The names most people mean when they name a drug. */
    const val TIER_HOUSEHOLD = 1

    /** Widely known, but not the first association. */
    const val TIER_KNOWN = 2

    /** Known to the community, rarely encountered outside it. */
    const val TIER_NICHE = 3

    /** Unlisted: sorts after every ranked substance with the same match quality. */
    const val TIER_UNRANKED = 4

    private val TIERS: Map<String, Int> = mapOf(
        // Tier 1: household names
        "lsd" to 1, "psilocybin mushrooms" to 1, "psilocin" to 1, "mescaline" to 1,
        "dmt" to 1, "5-meo-dmt" to 1, "2c-b" to 1, "ketamine" to 1,
        "pcp" to 1, "dextromethorphan" to 1, "nitrous" to 1, "mdma" to 1,
        "mda" to 1, "amphetamine" to 1, "methamphetamine" to 1, "cocaine" to 1,
        "methylphenidate" to 1, "caffeine" to 1, "nicotine" to 1, "modafinil" to 1,
        "tetrahydrocannabinol" to 1, "cannabidiol" to 1, "cannabis" to 1, "alcohol" to 1,
        "alprazolam" to 1, "diazepam" to 1, "clonazepam" to 1, "lorazepam" to 1,
        "ghb" to 1, "phenibut" to 1, "pregabalin" to 1, "gabapentin" to 1,
        "zolpidem" to 1, "heroin" to 1, "morphine" to 1, "codeine" to 1,
        "oxycodone" to 1, "fentanyl" to 1, "tramadol" to 1, "buprenorphine" to 1,
        "methadone" to 1, "kratom" to 1, "sertraline" to 1, "fluoxetine" to 1,
        "escitalopram" to 1, "bupropion" to 1, "quetiapine" to 1, "diphenhydramine" to 1,
        "lithium" to 1,
        // Tier 2: widely known
        "4-aco-dmt" to 2, "4-ho-met" to 2, "4-ho-mipt" to 2, "5-meo-mipt" to 2,
        "5-meo-dipt" to 2, "dipt" to 2, "5-meo-dalt" to 2, "bufotenin" to 2,
        "al-lad" to 2, "1p-lsd" to 2, "1cp-lsd" to 2, "eth-lad" to 2,
        "1p-eth-lad" to 2, "2c-c" to 2, "2c-d" to 2, "2c-e" to 2,
        "2c-i" to 2, "2c-p" to 2, "2c-t-2" to 2, "2c-t-7" to 2,
        "dob" to 2, "doi" to 2, "dom" to 2, "25i-nbome" to 2,
        "25b-nbome" to 2, "25c-nbome" to 2, "3-meo-pcp" to 2, "3-ho-pcp" to 2,
        "dmxe" to 2, "deschloroketamine" to 2, "2-fluorodeschloroketamine" to 2, "s-ketamine" to 2,
        "esketamine" to 2, "mephedrone" to 2, "methylone" to 2, "6-apb" to 2,
        "5-apb" to 2, "mdai" to 2, "mbdb" to 2, "butylone" to 2,
        "dextroamphetamine" to 2, "d-methamphetamine" to 2, "dl-amphetamine" to 2, "methcathinone" to 2,
        "phentermine" to 2, "armodafinil" to 2, "tapentadol" to 2, "dihydrocodeine" to 2,
        "hydromorphone" to 2, "acetylfentanyl" to 2, "desomorphine" to 2, "6-monoacetylmorphine" to 2,
        "mitragynine" to 2, "o-desmethyltramadol" to 2, "temazepam" to 2, "oxazepam" to 2,
        "nitrazepam" to 2, "flunitrazepam" to 2, "zopiclone" to 2, "eszopiclone" to 2,
        "phenobarbital" to 2, "baclofen" to 2, "f-phenibut" to 2, "flualprazolam" to 2,
        "citalopram" to 2, "paroxetine" to 2, "mirtazapine" to 2, "trazodone" to 2,
        "olanzapine" to 2, "aripiprazole" to 2, "risperidone" to 2, "haloperidol" to 2,
        "clozapine" to 2, "lamotrigine" to 2, "valproic acid" to 2, "psilocybe cubensis" to 2,
        "ayahuasca" to 2, "ibogaine" to 2, "salvinorin a" to 2, "muscimol" to 2,
        "amanita muscaria" to 2, "kava" to 2, "scopolamine" to 2, "atropine" to 2,
        "datura" to 2, "cannabigerol" to 2, "δ-8-tetrahydrocannabinol" to 2,
        // Tier 3: niche and research compounds
        "1b-lsd" to 3, "1d-lsd" to 3, "1s-lsd" to 3, "1v-lsd" to 3,
        "1cp-al-lad" to 3, "pargy-lad" to 3, "pro-lad" to 3, "4-aco-met" to 3,
        "4-aco-mipt" to 3, "4-aco-dipt" to 3, "4-ho-dipt" to 3, "4-ho-ept" to 3,
        "4-meo-mipt" to 3, "4-pro-dmt" to 3, "4-pro-met" to 3, "5-bromo-dmt" to 3,
        "5-meo-met" to 3, "2-me-dmt" to 3, "met" to 3, "mipt" to 3,
        "ept" to 3, "2c-b-fly" to 3, "2c-t-4" to 3, "2c-t-21" to 3,
        "2c-g" to 3, "2c-n" to 3, "2c-ip" to 3, "2c-ef" to 3,
        "2c-h" to 3, "2c-f" to 3, "don" to 3, "25n-nbome" to 3,
        "25d-nbome" to 3, "25p-nbome" to 3, "25e-nboh" to 3, "25b-nboh" to 3,
        "25c-nboh" to 3, "25i-nboh" to 3, "4-bromoamphetamine" to 3, "4-chloroamphetamine" to 3,
        "3-cl-pcp" to 3, "3-me-pcpy" to 3, "3-meo-pcpr" to 3, "3f-pcp" to 3,
        "3-eto-pcp" to 3, "4-meo-pcp" to 3, "o-pcp" to 3, "o-pcpr" to 3,
        "mxpcp" to 3, "bromoketamine" to 3, "ethketamine" to 3, "methoxyketamine" to 3,
        "butyrfentanyl" to 3, "4-fluorobutyrfentanyl" to 3, "acrylfentanyl" to 3, "parafluorofentanyl" to 3,
        "furanylfentanyl" to 3, "valerylfentanyl" to 3, "cyclopentyl-fentanyl" to 3, "methoxyacetyl-fentanyl" to 3,
        "n-(2c)-fentanyl" to 3, "benzodioxole-fentanyl" to 3, "isomethadone" to 3, "normethadone" to 3,
        "nicomorphine" to 3, "ethylmorphine" to 3, "acetyldihydrocodeine" to 3, "apomorphine" to 3,
        "4-chlorodiazepam" to 3, "delorazepam" to 3, "meclonazepam" to 3, "nitemazepam" to 3,
        "allobarbital" to 3, "amobarbital" to 3, "hexobarbital" to 3, "pentobarbital" to 3,
        "secobarbital" to 3, "propofol" to 3, "ethchlorvynol" to 3, "amanita pantherina" to 3,
        "datura (botany)" to 3,
    )

    /** Read-only view for tests: every ranked catalog name and its tier. */
    internal val tiers: Map<String, Int> get() = TIERS

    /** Popularity tier of [name], case-insensitive; [TIER_UNRANKED] when not listed. */
    fun tierOf(name: String): Int = TIERS[name.trim().lowercase()] ?: TIER_UNRANKED
}
