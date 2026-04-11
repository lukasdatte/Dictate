package net.devemperor.dictate.settings

import android.content.Context
import android.util.AttributeSet
import androidx.preference.EditTextPreference

/**
 * [EditTextPreference] that persists its value as an **Int** in SharedPreferences
 * instead of the default String. This lets the rest of the app use [Pref<Int>]
 * while the user still sees a normal text-input dialog.
 *
 * Usage in XML:
 * ```xml
 * <net.devemperor.dictate.settings.IntEditTextPreference
 *     android:key="net.devemperor.dictate.auto_enter_delay"
 *     android:defaultValue="50"
 *     ... />
 * ```
 */
class IntEditTextPreference(
    context: Context,
    attrs: AttributeSet?
) : EditTextPreference(context, attrs) {

    override fun setText(text: String?) {
        val intValue = text?.toIntOrNull() ?: return
        persistInt(intValue)
        notifyChanged()
    }

    override fun getText(): String {
        return getPersistedInt(0).toString()
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        val def = (defaultValue as? String)?.toIntOrNull()
            ?: (defaultValue as? Int)
            ?: 0
        val persisted = getPersistedInt(def)
        if (shouldPersist()) {
            persistInt(persisted)
        }
    }
}
