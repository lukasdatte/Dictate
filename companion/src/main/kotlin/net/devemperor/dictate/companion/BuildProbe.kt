package net.devemperor.dictate.companion

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.google.zxing.BarcodeFormat
import com.sun.jna.Platform

/**
 * A compile-and-resolve probe for the `:companion` version matrix (chunk `wd-0`).
 *
 * It touches one type per pinned dependency whose *real* consumer has not landed yet — SQLDelight
 * (`wd-5`), JNA (`wd-7`), ZXing and Compose (`wd-8`). Compiling is the proof: a library built with
 * a Kotlin newer than 2.1.20 would be rejected here, long before real code depends on it.
 *
 * **Delete this file once `wd-8` lands** — by then every one of these is exercised for real.
 */
object CompanionBuildProbe {

    fun describe(): String = buildString {
        append("sqldelight=").append(JdbcSqliteDriver.IN_MEMORY).append(' ')
        append("jna=").append(Platform.isWindows()).append(' ')
        append("zxing=").append(BarcodeFormat.QR_CODE.name)
    }
}

/** Proves the Compose compiler plugin is applied and Compose Desktop's Material 3 resolves. */
@Composable
fun CompanionProbeText(label: String) {
    Text(text = label)
}
