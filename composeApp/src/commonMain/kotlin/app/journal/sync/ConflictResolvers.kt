package app.journal.sync

/**
 * Conflict resolution policy registry.
 *
 * Wire into Couchbase Lite Replicator like:
 *
 *   CollectionConfiguration(
 *     conflictResolver = { conflict ->
 *       val docType = conflict.localDocument?.getString("docType")
 *           ?: conflict.remoteDocument?.getString("docType") ?: ""
 *       // dispatch to strategy implementation:
 *       when (ConflictPolicy.strategyFor(docType)) {
 *         FIELD_LEVEL_MERGE  -> mergeFields(conflict)
 *         APPEND_ONLY        -> conflict.localDocument
 *         SIBLING_AND_FLAG   -> addRemoteAsSibling(conflict)
 *         REMOTE_SOURCE_WINS -> mergeAnnotations(conflict)  // keep local userAnnotations
 *         SET_UNION_MERGE    -> unionEdges(conflict)
 *         LOCAL_WINS_RETAIN_BOTH  -> conflict.localDocument
 *         LOCAL_WINS_NEVER_MERGE  -> conflict.localDocument
 *         DEFAULT_AUTOMATIC  -> null  // let Couchbase Lite decide
 *       }
 *     }
 *   )
 */
enum class ConflictStrategy {
    FIELD_LEVEL_MERGE, APPEND_ONLY, SIBLING_AND_FLAG,
    REMOTE_SOURCE_WINS, SET_UNION_MERGE,
    LOCAL_WINS_RETAIN_BOTH, LOCAL_WINS_NEVER_MERGE, DEFAULT_AUTOMATIC
}

object ConflictPolicy {
    private val map = mapOf(
        "session"       to ConflictStrategy.FIELD_LEVEL_MERGE,
        "person"        to ConflictStrategy.FIELD_LEVEL_MERGE,
        "dose"          to ConflictStrategy.APPEND_ONLY,
        "timelineEvent" to ConflictStrategy.APPEND_ONLY,
        "note"          to ConflictStrategy.SIBLING_AND_FLAG,
        "substance"     to ConflictStrategy.REMOTE_SOURCE_WINS,
        "effect"        to ConflictStrategy.REMOTE_SOURCE_WINS,
        "interaction"   to ConflictStrategy.REMOTE_SOURCE_WINS,
        "link"          to ConflictStrategy.SET_UNION_MERGE,
        "attachment"    to ConflictStrategy.LOCAL_WINS_RETAIN_BOTH,
        "device"        to ConflictStrategy.LOCAL_WINS_NEVER_MERGE,
        "syncConfig"    to ConflictStrategy.LOCAL_WINS_NEVER_MERGE
    )
    fun strategyFor(docType: String): ConflictStrategy =
        map[docType] ?: ConflictStrategy.DEFAULT_AUTOMATIC
}
