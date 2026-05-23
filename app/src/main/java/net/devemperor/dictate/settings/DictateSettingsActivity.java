package net.devemperor.dictate.settings;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import net.devemperor.dictate.BuildConfig;
import net.devemperor.dictate.onboarding.OnboardingActivity;
import net.devemperor.dictate.R;
import net.devemperor.dictate.preferences.DictatePrefsKt;
import net.devemperor.dictate.preferences.Pref;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class DictateSettingsActivity extends AppCompatActivity {

    /**
     * Phase 2 §2.2a (Quality-Gate K-7): when set on the launching Intent,
     * the activity asks {@link PreferencesFragment} to scroll the named
     * preference into view after the fragment is created. Used by the
     * keyboard's "⚙ Sprachen verwalten…" PopupMenu action so the user
     * lands directly on the language curation list.
     */
    public static final String EXTRA_SCROLL_TO = "net.devemperor.dictate.scroll_to";

    /** Bundle key forwarded to {@link PreferencesFragment#getArguments()}. */
    private static final String FRAGMENT_ARG_SCROLL_TO = "scroll_to";

    ActivityResultLauncher<Intent> filePickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_dictate_settings);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.activity_dictate_settings), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Phase 2 §2.2a: forward the optional scroll-to extra to the
        // PreferencesFragment via Bundle args so the fragment can scroll
        // to a specific preference key after inflation.
        PreferencesFragment fragment = new PreferencesFragment();
        String scrollTo = getIntent().getStringExtra(EXTRA_SCROLL_TO);
        if (scrollTo != null) {
            Bundle args = new Bundle();
            args.putString(FRAGMENT_ARG_SCROLL_TO, scrollTo);
            fragment.setArguments(args);
        }
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.activity_dictate_settings, fragment)
                .commit();

        SharedPreferences sp = getSharedPreferences("net.devemperor.dictate", MODE_PRIVATE);

        // start onboarding if this is the first time for the user to open Dictate
        if (!DictatePrefsKt.get(sp, Pref.OnboardingComplete.INSTANCE)) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();

        // open file picker if user wants to transcribe a file
        } else if (getIntent().getBooleanExtra("net.devemperor.dictate.open_file_picker", false)) {
            filePickerLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        if (result.getData() != null) {
                            Uri uri = result.getData().getData();
                            if (uri == null) return;

                            Cursor cursor = getContentResolver().query(uri, null, null, null, null);
                            if (cursor == null) return;
                            int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                            int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);

                            String fileName = "";
                            long fileSize = 0;
                            if (cursor.moveToFirst()) {
                                fileName = cursor.getString(nameIndex);
                                fileSize = cursor.getLong(sizeIndex);
                            }
                            cursor.close();

                            // check if fileSize is larger than 25MB
                            if (fileSize > 25 * 1024 * 1024) {
                                new MaterialAlertDialogBuilder(this)
                                        .setTitle(R.string.dictate_file_too_large_title)
                                        .setMessage(R.string.dictate_file_too_large_message)
                                        .setPositiveButton(R.string.dictate_okay, null)
                                        .show();
                                return;
                            }

                            // copy the inputFileUri file to app cache directory.
                            //
                            // B3-VAL-W1 F-5: write into `cacheDir/audio/`
                            // (the same sub-directory CacheDirAudioFileFactory
                            // owns) so the imported file participates in
                            // the M4 orphan-cleanup pass (Spec 1 §4.11.5.1).
                            // The pre-fix path `cacheDir/<name>` fell outside
                            // the factory's `audioCacheDir` scope and leaked
                            // indefinitely unless the user manually cleared
                            // the cache.
                            Toast.makeText(this, getString(R.string.dictate_file_copying_to_cache), Toast.LENGTH_SHORT).show();
                            File audioCacheDir = new File(getCacheDir(), "audio");
                            //noinspection ResultOfMethodCallIgnored
                            audioCacheDir.mkdirs();
                            try {
                                InputStream inputStream = getContentResolver().openInputStream(uri);
                                FileOutputStream outputStream = new FileOutputStream(new File(audioCacheDir, fileName));
                                byte[] buffer = new byte[4096];
                                int bytesRead;
                                if (inputStream != null) {
                                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                                        outputStream.write(buffer, 0, bytesRead);
                                    }
                                    outputStream.close();
                                    inputStream.close();
                                }
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }

                            DictatePrefsKt.put(sp.edit(), Pref.TranscriptionAudioFile.INSTANCE, fileName).apply();
                        }
                    }
                    finish();  // close the activity after the file has been picked
                }
            );

            // let the user choose an audio file used for transcription
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/mpeg", "audio/mp4", "audio/wav", "audio/x-m4a", "audio/webm", "audio/ogg", "audio/amr", "audio/flac", "video/mp4", "video/mpeg", "video/webm"});
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            filePickerLauncher.launch(Intent.createChooser(intent, getString(R.string.dictate_choose_audio_file)));

        } else if (DictatePrefsKt.get(sp, Pref.LastVersionCode.INSTANCE) < BuildConfig.VERSION_CODE) {

            // show changelog if user has a new version
            StringBuilder whatsNewMessage = new StringBuilder(getString(R.string.dictate_changelog_donate));
            int lastVersionCode = DictatePrefsKt.get(sp, Pref.LastVersionCode.INSTANCE);
            for (int version = BuildConfig.VERSION_CODE; version >= 5; version--) {
                if (lastVersionCode < version) {
                    int resId = getResources().getIdentifier("dictate_changelog_" + version, "string", getPackageName());
                    whatsNewMessage.append(getString(resId));
                }
            }
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dictate_whats_new)
                    .setMessage(whatsNewMessage.toString())
                    .setPositiveButton(R.string.dictate_okay, (di, i) -> DictatePrefsKt.put(sp.edit(), Pref.LastVersionCode.INSTANCE, BuildConfig.VERSION_CODE).apply())
                    .show();

            if (lastVersionCode <= 26) DictatePrefsKt.put(sp.edit(), Pref.UseBluetoothMic.INSTANCE, false).apply();  // reset bluetooth mic setting to false due to issues in 2.10.0

        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{ Manifest.permission.RECORD_AUDIO }, 1337);

        } else {
            // check if keyboard is still enabled
            List<InputMethodInfo> inputMethodsList = ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).getEnabledInputMethodList();
            boolean keyboardEnabled = false;
            for (InputMethodInfo inputMethod : inputMethodsList) {
                if (inputMethod.getPackageName().equals(getPackageName())) {
                    keyboardEnabled = true;
                    break;
                }
            }
            if (!keyboardEnabled) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.dictate_enable_keyboard_title)
                        .setMessage(R.string.dictate_enable_keyboard_message)
                        .setPositiveButton(R.string.dictate_yes, (dialog, which) -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)))
                        .setNegativeButton(R.string.dictate_no, null)
                        .show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1337) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.dictate_microphone_permission_granted, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.dictate_microphone_permission_denied, Toast.LENGTH_SHORT).show();
            }
            finish();
        }
    }
}