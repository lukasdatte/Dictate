package net.devemperor.dictate.companion.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import org.slf4j.event.Level

/**
 * Method, path, status. **Never the body.**
 *
 * The body of a dispatch is the user's dictation and the body of a pair is a secret; either one in
 * a log file would undo the care taken everywhere else. The explicit format is what enforces it —
 * Ktor's default formatter is body-free today, but this way it stays that way on purpose.
 */
fun Application.installCallLogging() = install(CallLogging) {
    level = Level.INFO
    format { call ->
        "${call.request.httpMethod.value} ${call.request.path()} -> ${call.response.status()}"
    }
}
