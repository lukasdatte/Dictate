package net.devemperor.dictate.shared

import io.konform.validation.Validation
import io.konform.validation.constraints.minLength
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request

/**
 * Compile-and-resolve probe for the `:shared` version matrix (chunk `wd-0`).
 *
 * It exists for one reason: **compiling is the proof**. The dependency versions of this
 * module were derived from release notes, not from a build — this file forces the Kotlin
 * compiler to actually consume kotlinx-serialization, Konform and OkHttp metadata under
 * Kotlin 2.1.20 / jvmTarget 1.8. If any of them were built with a newer Kotlin, the build
 * fails here instead of failing later inside real protocol code.
 *
 * Superseded by the real protocol types in `wd-1`; delete it once they exist.
 *
 * Pure and platform-free — no `android.*`, no coroutines, no Ktor (ADR-0015).
 */
object SharedBuildProbe {

    @Serializable
    data class Probe(val name: String)

    private val validation = Validation<Probe> {
        Probe::name { minLength(1) }
    }

    /** Round-trips [name] through the JSON codec and the validation DSL. */
    fun encodeAndValidate(name: String): String? {
        val probe = Probe(name)
        if (validation(probe).errors.isNotEmpty()) return null
        return Json.encodeToString(Probe.serializer(), probe)
    }

    /** Touches OkHttp so its metadata is resolved at compile time too. */
    fun probeRequestUrl(url: String): String =
        Request.Builder().url(url).build().url.toString()
}
