package net.devemperor.dictate.core

import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import net.devemperor.dictate.BuildConfig
import net.devemperor.dictate.R
import net.devemperor.dictate.ai.AIProvider

/**
 * Controls the info bar (update, rate, donate, error messages).
 *
 * Extracted from DictateInputMethodService.showInfo().
 * Manages infoCl visibility and button click handlers for all 11 info types.
 *
 * Respects KeyboardStateManager constraints: info bar is never shown in
 * small mode or when QWERTZ/Emoji is the active content area.
 */
class InfoBarController(
    private val infoCl: ConstraintLayout,
    private val infoTv: TextView,
    private val infoYesButton: Button,
    private val infoNoButton: Button,
    private val openSettings: () -> Unit,
    private val startActivityAction: (Intent) -> Unit,
    private val sp: SharedPreferences,
    private val resources: Resources,
    private val themeProvider: () -> Resources.Theme
) {
    // Track whether we should suppress showing (set by KeyboardStateManager)
    private var suppressDisplay = false

    /** Called by KeyboardStateManager when content area or small mode changes. */
    fun onStateChanged(contentArea: ContentArea, isSmallMode: Boolean) {
        suppressDisplay = isSmallMode || contentArea != ContentArea.MAIN_BUTTONS
        if (suppressDisplay) {
            dismiss()
        }
    }

    fun dismiss() {
        infoCl.visibility = View.GONE
    }

    fun showInfo(type: String) = showInfo(type, null)

    fun showInfo(type: String, providerName: String?) {
        if (suppressDisplay) return

        infoCl.visibility = View.VISIBLE
        infoNoButton.visibility = View.VISIBLE
        infoTv.setTextColor(resources.getColor(R.color.dictate_red, themeProvider()))

        when (type) {
            "update" -> {
                infoTv.setTextColor(resources.getColor(R.color.dictate_blue, themeProvider()))
                infoTv.setText(R.string.dictate_update_installed_msg)
                infoYesButton.visibility = View.VISIBLE
                infoYesButton.setOnClickListener {
                    openSettings()
                    dismiss()
                }
                infoNoButton.setOnClickListener {
                    sp.edit().putInt("net.devemperor.dictate.last_version_code", BuildConfig.VERSION_CODE).apply()
                    dismiss()
                }
            }
            "rate" -> {
                infoTv.setTextColor(resources.getColor(R.color.dictate_blue, themeProvider()))
                infoTv.setText(R.string.dictate_rate_app_msg)
                infoYesButton.visibility = View.VISIBLE
                infoYesButton.setOnClickListener {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=net.devemperor.dictate"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivityAction(intent)
                    sp.edit().putBoolean("net.devemperor.dictate.flag_has_rated_in_playstore", true).apply()
                    dismiss()
                }
                infoNoButton.setOnClickListener {
                    sp.edit().putBoolean("net.devemperor.dictate.flag_has_rated_in_playstore", true).apply()
                    dismiss()
                }
            }
            "donate" -> {
                infoTv.setTextColor(resources.getColor(R.color.dictate_blue, themeProvider()))
                infoTv.setText(R.string.dictate_donate_msg)
                infoYesButton.visibility = View.VISIBLE
                infoYesButton.setOnClickListener {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://paypal.me/DevEmperor"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivityAction(intent)
                    sp.edit().putBoolean("net.devemperor.dictate.flag_has_donated", true)
                        .putBoolean("net.devemperor.dictate.flag_has_rated_in_playstore", true).apply()
                    dismiss()
                }
                infoNoButton.setOnClickListener {
                    sp.edit().putBoolean("net.devemperor.dictate.flag_has_donated", true)
                        .putBoolean("net.devemperor.dictate.flag_has_rated_in_playstore", true).apply()
                    dismiss()
                }
            }
            "timeout" -> {
                infoTv.setText(R.string.dictate_timeout_msg)
                infoYesButton.visibility = View.GONE
                infoNoButton.setOnClickListener { dismiss() }
            }
            "invalid_api_key" -> {
                infoTv.setText(R.string.dictate_invalid_api_key_msg)
                infoYesButton.visibility = View.VISIBLE
                infoYesButton.setOnClickListener {
                    openSettings()
                    dismiss()
                }
                infoNoButton.setOnClickListener { dismiss() }
            }
            "quota_exceeded" -> {
                val provider = providerName?.let { AIProvider.fromPersistKey(it) }
                val displayName = provider?.displayName ?: "API"
                val billingUrl = provider?.billingUrl

                infoTv.text = resources.getString(R.string.dictate_quota_exceeded_msg, displayName)

                if (billingUrl != null) {
                    infoYesButton.visibility = View.VISIBLE
                    infoYesButton.setOnClickListener {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(billingUrl))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivityAction(intent)
                        dismiss()
                    }
                } else {
                    infoYesButton.visibility = View.GONE
                }
                infoNoButton.setOnClickListener { dismiss() }
            }
            "model_not_found" -> {
                infoTv.setText(R.string.dictate_model_not_found_msg)
                infoYesButton.visibility = View.VISIBLE
                infoYesButton.setOnClickListener {
                    openSettings()
                    dismiss()
                }
                infoNoButton.setOnClickListener { dismiss() }
            }
            "bad_request" -> {
                infoTv.setText(R.string.dictate_bad_request_msg)
                infoYesButton.visibility = View.VISIBLE
                infoYesButton.setOnClickListener {
                    openSettings()
                    dismiss()
                }
                infoNoButton.setOnClickListener { dismiss() }
            }
            "internet_error" -> {
                infoTv.setText(R.string.dictate_internet_error_msg)
                infoYesButton.visibility = View.GONE
                infoNoButton.setOnClickListener { dismiss() }
            }
        }
    }
}
