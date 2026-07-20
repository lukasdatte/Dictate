package net.devemperor.dictate.settings;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreference;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import net.devemperor.dictate.BuildConfig;
import net.devemperor.dictate.DictateUtils;
import net.devemperor.dictate.R;
import net.devemperor.dictate.core.DictatePipelineService;
import net.devemperor.dictate.preferences.DictatePrefsKt;
import net.devemperor.dictate.preferences.LanguageResolver;
import net.devemperor.dictate.preferences.Pref;
import net.devemperor.dictate.preferences.WindowsTarget;
import net.devemperor.dictate.history.HistoryActivity;
import net.devemperor.dictate.rewording.PromptsOverviewActivity;
import net.devemperor.dictate.database.DictateDatabase;
import net.devemperor.dictate.database.dao.UsageDao;
import net.devemperor.dictate.state.RecordingState;
import net.devemperor.dictate.usage.UsageActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class PreferencesFragment extends PreferenceFragmentCompat {

    private static final String TAG = "DictatePrefsFragment";

    SharedPreferences sp;
    UsageDao usageDao;

    /**
     * Held connection to {@link DictatePipelineService} so the
     * "Clear cache" preference can snapshot the recording state
     * before unlinking files (B3-VAL-W1 F-4 / KG-AFF-3 race-protect).
     * Bound in {@link #onStart()} and released in {@link #onStop()}; a
     * null binder reads as "service not connected → cannot be
     * actively recording", which is the safe default.
     */
    @androidx.annotation.Nullable
    private DictatePipelineService.LocalBinder pipelineBinder;

    private final ServiceConnection pipelineConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            if (service instanceof DictatePipelineService.LocalBinder) {
                pipelineBinder = (DictatePipelineService.LocalBinder) service;
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            pipelineBinder = null;
        }
    };

    private boolean pipelineBound = false;

    @androidx.annotation.Nullable
    private Preference windowsPairingPreference;
    @androidx.annotation.Nullable
    private SwitchPreference windowsAutoSendPreference;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        getPreferenceManager().setSharedPreferencesName("net.devemperor.dictate");
        setPreferencesFromResource(R.xml.fragment_preferences, null);
        sp = getPreferenceManager().getSharedPreferences();
        usageDao = DictateDatabase.getInstance(requireContext()).usageDao();

        Preference editPromptsPreference = findPreference("net.devemperor.dictate.edit_custom_rewording_prompts");
        if (editPromptsPreference != null) {
            editPromptsPreference.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(requireContext(), PromptsOverviewActivity.class));
                return true;
            });
        }

        MultiSelectListPreference inputLanguagesPreference = findPreference("net.devemperor.dictate.input_languages");
        if (inputLanguagesPreference != null) {
            // Phase 3 §3.1 (Quality-Gate K-5/K-6): the preference is marked
            // app:persistent="false" in XML so MultiSelectListPreference.setValues
            // does not call persistStringSet() and overwrite the JSON envelope.
            // D-13 (Epic §4 Block C1): the on-disk source-of-truth is read /
            // written via LanguageResolver (the unbound-path SoT — the
            // Settings UI has no bound DictateOrchestrator). It delegates to
            // VersionedPrefs internally, and the pos-resync algorithm lives
            // in exactly one place (persistInputLanguagesAndPos). No stale
            // in-memory cache: every read re-reads SharedPreferences, so an
            // IME-side write between fragment-create and listener-fire is
            // immediately visible (R-3 cross-instance staleness is gone).
            List<String> curated = LanguageResolver.INSTANCE.curatedLanguages(sp);

            // Order matters: setSummaryProvider must run BEFORE setValues, because
            // setValues() fires notifyChanged() and the framework rebuilds the summary
            // immediately. With the provider still null the very first render shows an
            // empty summary until something else triggers another notifyChanged().
            inputLanguagesPreference.setSummaryProvider((Preference.SummaryProvider<MultiSelectListPreference>) preference -> {
                String[] selectedLanguagesValues = preference.getValues().toArray(new String[0]);
                return Arrays.stream(selectedLanguagesValues).map(DictateUtils::translateLanguageToEmoji).collect(Collectors.joining(" "));
            });

            inputLanguagesPreference.setValues(new HashSet<>(curated));

            inputLanguagesPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                @SuppressWarnings("unchecked")
                Set<String> selectedLanguages = (Set<String>) newValue;
                if (selectedLanguages.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.dictate_input_languages_empty, Toast.LENGTH_SHORT).show();
                    return false;
                }

                // Determine the currently-active code (pos-anchor). Reading
                // the persisted list freshly mirrors the IME path and avoids
                // a stale in-memory copy if another component (the IME) wrote
                // between fragment creation and this listener firing.
                List<String> oldList = LanguageResolver.INSTANCE.curatedLanguages(sp);
                int oldPos = DictatePrefsKt.get(sp, Pref.InputLanguagePos.INSTANCE);
                String oldActive = (oldPos >= 0 && oldPos < oldList.size())
                        ? oldList.get(oldPos)
                        : null;

                // Persist via the resolver — sanitize (dedupe + allowlist
                // filter + label sort) and pos-resync are centralized there.
                LanguageResolver.INSTANCE.setCuratedLanguages(
                        sp, new ArrayList<>(selectedLanguages), oldActive);

                // app:persistent="false" already prevents the framework from
                // writing a StringSet to the same key. Returning true here
                // would not cause a write, but returning true is also semantically
                // correct: the preference UI should reflect the new selection.
                return true;
            });
        }

        ListPreference appLanguagePreference = findPreference("net.devemperor.dictate.app_language");
        if (appLanguagePreference != null) {
            appLanguagePreference.setOnPreferenceChangeListener((preference, newValue) -> {
                DictateUtils.applyApplicationLocale((String) newValue);
                requireActivity().recreate();
                return true;
            });
        }

        EditTextPreference overlayCharactersPreference = findPreference("net.devemperor.dictate.overlay_characters");
        if (overlayCharactersPreference != null) {
            overlayCharactersPreference.setSummaryProvider((Preference.SummaryProvider<EditTextPreference>) preference -> {
                String text = preference.getText();
                if (TextUtils.isEmpty(text)) {
                    return getString(R.string.dictate_default_overlay_characters);
                }
                return text.chars().mapToObj(c -> String.valueOf((char) c)).collect(Collectors.joining(" "));
            });

            overlayCharactersPreference.setOnBindEditTextListener(editText -> {
                editText.setInputType(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                editText.setSingleLine(true);
                editText.setHint(R.string.dictate_default_overlay_characters);
                editText.setFilters(new InputFilter[] {new InputFilter.LengthFilter(8)});
                editText.setSelection(editText.getText().length());
            });

            overlayCharactersPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                String text = (String) newValue;
                if (text.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.dictate_overlay_characters_empty, Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            });
        }

        IntEditTextPreference autoEnterDelayPref = findPreference("net.devemperor.dictate.auto_enter_delay");
        if (autoEnterDelayPref != null) {
            autoEnterDelayPref.setSummaryProvider(pref ->
                    ((EditTextPreference) pref).getText() + " ms");

            autoEnterDelayPref.setOnBindEditTextListener(editText -> {
                editText.setInputType(InputType.TYPE_CLASS_NUMBER);
                editText.setSingleLine(true);
                editText.setFilters(new InputFilter[] { new InputFilter.LengthFilter(3) });
                editText.setSelection(editText.getText().length());
            });

            autoEnterDelayPref.setOnPreferenceChangeListener((pref, newValue) -> {
                try {
                    int delay = Integer.parseInt((String) newValue);
                    return delay >= 0 && delay <= 999;
                } catch (NumberFormatException e) {
                    return false;
                }
            });
        }

        SwitchPreference instantOutputPreference = findPreference("net.devemperor.dictate.instant_output");
        SeekBarPreference outputSpeedPreference = findPreference("net.devemperor.dictate.output_speed");
        if (instantOutputPreference != null && outputSpeedPreference != null) {
            instantOutputPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                outputSpeedPreference.setEnabled(!(Boolean) newValue);
                return true;
            });
            outputSpeedPreference.setEnabled(!instantOutputPreference.isChecked());
        }

        Preference usagePreference = findPreference("net.devemperor.dictate.usage");
        if (usagePreference != null) {
            Long totalTimeOrNull = usageDao.getTotalAudioTime();
            long totalTime = totalTimeOrNull != null ? totalTimeOrNull : 0;
            usagePreference.setSummary(getString(R.string.dictate_usage_total_audio_time, totalTime / 60, totalTime % 60));

            usagePreference.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(requireContext(), UsageActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            });
        }

        Preference historyPreference = findPreference("net.devemperor.dictate.history");
        if (historyPreference != null) {
            historyPreference.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(requireContext(), HistoryActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            });
        }

        Preference apiSettingsPreference = findPreference("net.devemperor.dictate.api_settings");
        if (apiSettingsPreference != null) {
            apiSettingsPreference.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(requireContext(), APISettingsActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            });
        }

        // Read-only Peer Explorer (Block E3, peer-katalog.md §8.3).
        Preference peerExplorerPreference = findPreference("net.devemperor.dictate.peer_explorer");
        if (peerExplorerPreference != null) {
            peerExplorerPreference.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(requireContext(), net.devemperor.dictate.peers.ui.PeerExplorerActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            });
        }

        Preference promptPreference = findPreference("net.devemperor.dictate.prompts");
        if (promptPreference != null) {
            promptPreference.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(requireContext(), SystemPromptsActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            });
        }

        EditTextPreference proxyHostPreference = findPreference("net.devemperor.dictate.proxy_host");
        if (proxyHostPreference != null) {
            proxyHostPreference.setSummaryProvider((Preference.SummaryProvider<EditTextPreference>) preference -> {
                String host = preference.getText();
                if (TextUtils.isEmpty(host)) return getString(R.string.dictate_settings_proxy_hint);
                return host;
            });

            proxyHostPreference.setOnBindEditTextListener(editText -> {
                editText.setInputType(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_URI);
                editText.setSingleLine(true);
                editText.setHint(R.string.dictate_settings_proxy_hint);
            });

            proxyHostPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                String host = (String) newValue;
                if (DictateUtils.isValidProxy(host)) return true;
                else {
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.dictate_proxy_invalid_title)
                            .setMessage(R.string.dictate_proxy_invalid_message)
                            .setPositiveButton(R.string.dictate_okay, null)
                            .show();
                    return false;
                }
            });
        }

        // ── Windows Dispatch (ADR-0019) ──
        windowsPairingPreference = findPreference("net.devemperor.dictate.windows_pairing");
        windowsAutoSendPreference = findPreference("net.devemperor.dictate.windows_auto_send_enabled");
        if (windowsPairingPreference != null) {
            windowsPairingPreference.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(requireContext(), WindowsPairingActivity.class));
                return true;
            });
        }
        refreshWindowsDispatchState();

        Preference howToPreference = findPreference("net.devemperor.dictate.how_to");
        if (howToPreference != null) {
            howToPreference.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(requireContext(), HowToActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            });
        }

        Preference cachePreference = findPreference("net.devemperor.dictate.cache");
        File cacheDir = requireContext().getCacheDir();
        if (cachePreference != null) {
            // KG-AFF-3 (Spec 1 §4.11.6.3): the new AudioFileFactory writes
            // to `cacheDir/audio/` (a sub-directory). The pre-refactor
            // `cacheDir.listFiles()` + `File.delete()` loop was a no-op
            // on non-empty sub-directories, so the new audio sub-tree
            // would survive a user-initiated "clear cache". Switch to a
            // recursive size + count + delete trio so both the legacy
            // top-level layout AND the new sub-directory layout behave
            // identically from the user's perspective.
            long cacheSize = computeCacheSizeRecursive(cacheDir);
            int cacheFileCount = countCacheFilesRecursive(cacheDir);
            cachePreference.setTitle(getString(R.string.dictate_settings_cache, cacheFileCount, cacheSize / 1024f / 1024f));

            cachePreference.setOnPreferenceClickListener(preference -> {
                // B3-VAL-W1 F-4 / Spec 1 §4.11.6.3 KG-AFF-3 — block
                // the clear if a recording is currently active. The
                // race we're protecting against: user taps the
                // preference mid-recording, we unlink() the open
                // MediaRecorder FD, the recorder keeps writing to the
                // unlinked inode, persistFromCache then fails →
                // Ghost-Session FAILED with no clear user-facing
                // cause. Snapshot the orchestrator state via the
                // service binder; null binder means the service
                // isn't running, so no recording can be in flight.
                if (isRecordingActive()) {
                    Toast.makeText(
                            requireContext(),
                            R.string.dictate_cache_clear_blocked_recording,
                            Toast.LENGTH_LONG).show();
                    return true;
                }
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.dictate_cache_clear_title)
                        .setMessage(R.string.dictate_cache_clear_message)
                        .setPositiveButton(R.string.dictate_yes, (dialog, which) -> {
                            // Re-check at confirmation time — the user may have
                            // tapped the record button between the click and the
                            // confirm dialog.
                            if (isRecordingActive()) {
                                Toast.makeText(
                                        requireContext(),
                                        R.string.dictate_cache_clear_blocked_recording,
                                        Toast.LENGTH_LONG).show();
                                return;
                            }
                            clearCacheRecursively(cacheDir);
                            cachePreference.setTitle(getString(R.string.dictate_settings_cache, 0, 0f));
                            Toast.makeText(requireContext(), R.string.dictate_cache_cleared, Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton(R.string.dictate_no, null)
                        .show();
                return true;
            });
        }

        Preference feedbackPreference = findPreference("net.devemperor.dictate.feedback");
        if (feedbackPreference != null) {
            feedbackPreference.setOnPreferenceClickListener(preference -> {
                Intent emailIntent = new Intent(Intent.ACTION_SENDTO);
                emailIntent.setData(Uri.parse("mailto:contact@devemperor.net"));
                emailIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.dictate_feedback_subject));
                emailIntent.putExtra(Intent.EXTRA_TEXT, getString(R.string.dictate_feedback_body)
                        + "\n\nDictate User-ID: " + DictatePrefsKt.get(sp, Pref.UserId.INSTANCE));
                startActivity(Intent.createChooser(emailIntent, getString(R.string.dictate_feedback_title)));
                return true;
            });
        }

        Preference githubPreference = findPreference("net.devemperor.dictate.github");
        if (githubPreference != null) {
            githubPreference.setOnPreferenceClickListener(preference -> {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/DevEmperor/Dictate"));
                startActivity(browserIntent);
                return true;
            });
        }

        Preference aboutPreference = findPreference("net.devemperor.dictate.about");
        if (aboutPreference != null) {
            aboutPreference.setTitle(getString(R.string.dictate_about, BuildConfig.VERSION_NAME));
            aboutPreference.setOnPreferenceClickListener(preference -> {
                Toast.makeText(requireContext(), "User-ID: " + DictatePrefsKt.get(sp, Pref.UserId.INSTANCE), Toast.LENGTH_LONG).show();
                return true;
            });
        }

        // Phase 2 §2.2a (Quality-Gate K-7): if launched with a "scroll_to"
        // bundle arg, scroll the matching preference into view. Used by the
        // keyboard's "⚙ Sprachen verwalten…" PopupMenu action so the user
        // lands directly on the language curation list without having to
        // hunt for it in the long settings screen.
        Bundle args = getArguments();
        if (args != null) {
            String scrollTo = args.getString("scroll_to");
            if (scrollTo != null) {
                scrollToPreference(scrollTo);
            }
        }
    }

    /**
     * Recursive byte-size sum of {@code root} (files + sub-directories).
     * Used by the cache preference summary so the displayed size reflects
     * the audio sub-directory the new {@link
     * net.devemperor.dictate.core.CacheDirAudioFileFactory} writes into
     * (Spec 1 §4.11.6.3, KG-AFF-3). A pre-Block-4 top-level scan would
     * report 0 for the audio sub-tree even when it holds several megabytes.
     *
     * @param root the cache root (typically {@code getCacheDir()}). May be
     *   {@code null} when called against a stripped {@code Context}; the
     *   helper returns 0.
     * @return total byte count of all regular files under {@code root}.
     */
    private static long computeCacheSizeRecursive(File root) {
        if (root == null || !root.exists()) return 0L;
        long total = 0L;
        File[] children = root.listFiles();
        if (children == null) return 0L;
        for (File child : children) {
            if (child.isDirectory()) {
                total += computeCacheSizeRecursive(child);
            } else if (child.isFile()) {
                total += child.length();
            }
        }
        return total;
    }

    /**
     * Recursive file count under {@code root}. Mirrors {@link
     * #computeCacheSizeRecursive(File)} so the displayed "N files / MB"
     * pair stays consistent (Spec 1 §4.11.6.3, KG-AFF-3).
     *
     * @param root the cache root. {@code null} returns 0.
     * @return number of regular files (directories not counted).
     */
    private static int countCacheFilesRecursive(File root) {
        if (root == null || !root.exists()) return 0;
        int total = 0;
        File[] children = root.listFiles();
        if (children == null) return 0;
        for (File child : children) {
            if (child.isDirectory()) {
                total += countCacheFilesRecursive(child);
            } else if (child.isFile()) {
                total += 1;
            }
        }
        return total;
    }

    /**
     * Recursively delete every entry under {@code root} but keep {@code
     * root} itself (the cache directory is owned by the OS — deleting it
     * is incorrect, but emptying it is). The pre-Block-4 code path
     * called {@link File#delete()} on each top-level entry — Java's
     * contract for that method is "fail when non-empty directory", so
     * the audio sub-tree survived a user-triggered cache wipe.
     *
     * @param root the cache root. {@code null} is a no-op.
     */
    private static void clearCacheRecursively(File root) {
        if (root == null || !root.exists()) return;
        File[] children = root.listFiles();
        if (children == null) return;
        for (File child : children) {
            deleteRecursively(child);
        }
    }

    /** Tail recursion helper for {@link #clearCacheRecursively(File)}. */
    private static void deleteRecursively(File entry) {
        if (entry.isDirectory()) {
            File[] nested = entry.listFiles();
            if (nested != null) {
                for (File n : nested) {
                    deleteRecursively(n);
                }
            }
        }
        // File.delete() returns false on non-empty dirs; harmless here
        // because we just drained the directory above.
        // noinspection ResultOfMethodCallIgnored
        entry.delete();
    }

    @Override
    public void onResume() {
        super.onResume();
        // The pairing summary and the toggle's enabled-state must reflect a pairing
        // that just happened in WindowsPairingActivity (or an unpair) — refresh on return.
        refreshWindowsDispatchState();
    }

    /**
     * Mirrors the current pairing state into the Windows-Dispatch category: the pairing row's
     * summary names the paired PC (or "not paired"), and the auto-send toggle is only enabled
     * while a PC is coupled — the device secret is the single "paired?" gate (ADR-0017/0019).
     */
    private void refreshWindowsDispatchState() {
        boolean paired = WindowsTarget.isPaired(sp);
        if (windowsPairingPreference != null) {
            windowsPairingPreference.setSummary(paired
                    ? DictatePrefsKt.get(sp, Pref.WindowsServerName.INSTANCE)
                    : getString(R.string.dictate_settings_windows_not_paired));
        }
        if (windowsAutoSendPreference != null) {
            windowsAutoSendPreference.setEnabled(paired);
            windowsAutoSendPreference.setSummary(paired
                    ? getString(R.string.dictate_settings_windows_auto_send_summary)
                    : getString(R.string.dictate_settings_windows_auto_send_summary_not_paired));
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        // B3-VAL-W1 F-4 — bind to the running pipeline service so the
        // cache-clear click handler can snapshot the recording state.
        // BIND_AUTO_CREATE would start the service; we only need to
        // observe the existing one (the IME ensures it's running while
        // the user keyboard is active). If the service isn't up,
        // pipelineBinder stays null → isRecordingActive() returns false
        // (safe default: nothing to race against).
        Context appCtx = requireContext().getApplicationContext();
        Intent intent = new Intent(appCtx, DictatePipelineService.class);
        try {
            pipelineBound = appCtx.bindService(intent, pipelineConnection, 0);
        } catch (SecurityException e) {
            Log.w(TAG, "bindService(DictatePipelineService) denied", e);
            pipelineBound = false;
        }
    }

    @Override
    public void onStop() {
        if (pipelineBound) {
            try {
                requireContext().getApplicationContext().unbindService(pipelineConnection);
            } catch (IllegalArgumentException e) {
                // Already unbound — safe to ignore.
                Log.w(TAG, "unbindService(DictatePipelineService) failed", e);
            }
            pipelineBound = false;
        }
        pipelineBinder = null;
        super.onStop();
    }

    /**
     * Returns {@code true} when the orchestrator reports a non-Idle
     * recording state (Preparing / Active / Paused). Used by the
     * Cache-Clear preference handler (B3-VAL-W1 F-4) so it can refuse
     * to unlink MediaRecorder's open file descriptor mid-recording.
     *
     * A null binder (service not bound yet, or not running) reads as
     * <em>not recording</em>: the safe default — if the user can't
     * reach the service, they can't be recording through it.
     */
    private boolean isRecordingActive() {
        DictatePipelineService.LocalBinder binder = pipelineBinder;
        if (binder == null) return false;
        try {
            return !(binder.getState().getValue().getRecording() instanceof RecordingState.Idle);
        } catch (Throwable t) {
            // Defensive — a torn-down binder may throw on state read.
            // Falling back to "not recording" matches the safe-default
            // intent (false-positive blocks would be more annoying
            // than a false-negative race window).
            Log.w(TAG, "recording-state snapshot failed", t);
            return false;
        }
    }
}
