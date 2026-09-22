package app.journal.net

import io.ktor.client.*

/**
 * Creates an HttpClient using the platform-default engine.
 * jvmMain: CIO, iosMain: Darwin
 */
expect fun createHttpClient(): HttpClient
