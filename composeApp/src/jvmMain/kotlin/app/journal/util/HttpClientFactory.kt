package app.journal.util

import io.ktor.client.*
import io.ktor.client.engine.cio.*

actual fun createHttpClient(): HttpClient = HttpClient(CIO)
