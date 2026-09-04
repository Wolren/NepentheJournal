package app.journal.model
import kotlinx.serialization.Serializable

enum class PersonRole { PARTICIPANT, SITTER, OBSERVER }

/**
 * A person known to the journal. The PARTICIPANT assigned to a session owns
 * that trip's demographics: the dose.wiki trip report export reads age,
 * gender, height, weight and medications from the assigned person, falling
 * back to the session's legacy [Session.profile] only when no person is set.
 *
 * Height and weight are free text with the unit inside ("5 ft 8 in", "68 kg"),
 * matching the dose.wiki subject contract which has no numeric fields.
 *
 * Conflict: FIELD_LEVEL_MERGE. People are device-local profile data and are
 * deliberately excluded from sync replication and tombstones.
 */
@Serializable
data class Person(
    override val id: String,

    override val createdAt: Long,
    override val updatedAt: Long,
    override val deviceOrigin: String,
    val displayName: String,
    val role: PersonRole = PersonRole.PARTICIPANT,
    val contactInfo: String? = null,
    val notes: String? = null,
    val linkedSessionIds: List<String> = emptyList(),
    /** Age in years. Rendered as a string on the dose.wiki wire. */
    val age: Int? = null,
    /** Free text, no option list. */
    val gender: String? = null,
    /** Free text with the unit inside, e.g. "5 ft 8 in" or "175 cm". */
    val height: String? = null,
    /** Free text with the unit inside, e.g. "150 lb" or "68 kg". */
    val weight: String? = null,
    /** Free prose, e.g. "None" or current prescriptions. */
    val medications: String? = null,
    /** Author contact for dose.wiki editors. Never exported without mayContact. */
    val contactEmail: String? = null,
    /** Editors may write to [contactEmail] about an exported report. */
    val mayContact: Boolean = false
) : VaultDocument
