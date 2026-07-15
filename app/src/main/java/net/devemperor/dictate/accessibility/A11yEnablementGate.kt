package net.devemperor.dictate.accessibility

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * Answers "may we read the screen, and if not, what is missing?" — and builds
 * the Intent that sends the user to fix it.
 *
 * # Two different questions
 *
 * [isServiceEnabled] reports the user's **setting**; [DictateAccessibilityService.isConnected]
 * reports whether the service is **bound right now**. They disagree in a real
 * window — just after the user flips the switch, and after the system restarts
 * the service — during which the setting says yes but a read returns null. UI
 * that asks "is this configured?" wants the former; the read path wants the
 * latter. Conflating them produces a screen that claims the feature is on while
 * every dictation silently ships no context.
 *
 * # An app cannot enable its own accessibility service
 *
 * There is no API, by design — it is a security boundary. All we can do is
 * explain and open the settings screen ([settingsIntent]).
 *
 * # Sideloaded builds hit "Restricted setting" first
 *
 * Android 13+ greys out the accessibility toggle for apps installed by a
 * non-session installer, which is what "open the APK and install" is. The
 * switch is visible but refuses to move, with no explanation of what to do. The
 * way through is App info → ⋮ → *Allow restricted settings* — see
 * [appInfoIntent], which is why this class exposes both destinations. Play
 * installs are exempt (they use the session installer), so this only bites the
 * sideload path — which is the one this app ships on.
 */
object A11yEnablementGate {

    /**
     * Whether the user has switched Dictate's accessibility service on in
     * system settings.
     *
     * Uses [AccessibilityManager] rather than parsing
     * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` — same answer, public
     * API, no string splitting to get subtly wrong.
     */
    @JvmStatic
    fun isServiceEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as? AccessibilityManager ?: return false
        val expected = ComponentName(context, DictateAccessibilityService::class.java)
        return manager
            .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                runCatching { ComponentName.unflattenFromString(info.id) }.getOrNull() == expected
            }
    }

    /**
     * The system accessibility settings list.
     *
     * Deliberately NOT deep-linked to our own entry: the extras trick for that
     * (`:settings:fragment_args_key`) is undocumented and OEM-dependent — it
     * highlights the row on some builds and lands on an unrelated screen on
     * others. A reliable list beats an unreliable shortcut when the user is
     * already being asked to do something unusual.
     */
    @JvmStatic
    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * This app's "App info" screen — where *Allow restricted settings* lives on
     * sideloaded Android 13+ installs.
     */
    @JvmStatic
    fun appInfoIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
