package net.devemperor.dictate.companion

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.zxing.BarcodeFormat
import com.sun.jna.Platform
import io.ktor.server.application.Application
import net.devemperor.dictate.shared.protocol.HealthResponse
import net.devemperor.dictate.shared.protocol.ProtocolCodec
import net.devemperor.dictate.shared.protocol.Validations

/**
 * Entry point of the desktop companion — a placeholder until the Ktor server (`wd-4`),
 * the SQLDelight history (`wd-5`) and the Compose UI (`wd-8`) land.
 *
 * Everything in this file is a **compile-and-resolve probe** for the `:companion` version
 * matrix (chunk `wd-0`): Compose Desktop 1.8.2, Ktor 3.1.3, SQLDelight 2.1.0, JNA 5.19.1 and
 * ZXing 3.5.4 must all be consumable under Kotlin 2.1.20. Compiling is the proof — a library
 * built with a newer Kotlin would be rejected here, before any real code depends on it.
 */
fun main() {
    println("Dictate Companion — scaffolding only (wd-0). Server, DB and UI land in wd-4..wd-8.")
    println(CompanionBuildProbe.describe())
}

object CompanionBuildProbe {

    /** Touches one type per pinned dependency so the compiler has to read its metadata. */
    fun describe(): String = buildString {
        append("shared=").append(healthJson()).append(' ')
        append("ktor=").append(Application::class.java.simpleName).append(' ')
        append("sqldelight=").append(JdbcSqliteDriver.IN_MEMORY).append(' ')
        append("jna=").append(Platform.isWindows()).append(' ')
        append("zxing=").append(BarcodeFormat.QR_CODE.name)
    }

    /** The companion will answer `/v1/health` with exactly this, once it has a server (`wd-4`). */
    private fun healthJson(): String = ProtocolCodec.encode(
        HealthResponse(serverName = "companion", appVersion = "0.0.0", canInsert = Platform.isWindows()),
        HealthResponse.serializer(),
        Validations.healthResponse,
    )
}

/** Proves the Compose compiler plugin is applied and Compose Desktop's Material 3 resolves. */
@Composable
fun CompanionProbeText(label: String) {
    Text(text = label)
}
