package net.devemperor.dictate.settings;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import net.devemperor.dictate.R;
import net.devemperor.dictate.ai.secrets.SecretStore;
import net.devemperor.dictate.preferences.DictatePrefsKt;
import net.devemperor.dictate.preferences.Pref;
import net.devemperor.dictate.preferences.WindowsTarget;
import net.devemperor.dictate.secrets.AndroidKeystoreSecretStore;
import net.devemperor.dictate.secrets.PairingSecrets;
import net.devemperor.dictate.shared.auth.PairingInfo;
import net.devemperor.dictate.shared.auth.PairingUri;
import net.devemperor.dictate.shared.client.DispatchClient;
import net.devemperor.dictate.shared.client.DispatchError;
import net.devemperor.dictate.shared.client.DispatchResult;
import net.devemperor.dictate.shared.protocol.HealthResponse;
import net.devemperor.dictate.shared.protocol.PairResponse;
import net.devemperor.dictate.shared.transport.OkHttpDispatchTransport;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Pairs the phone with a desktop companion (ADR-0017/0019).
 *
 * Two equivalent paths (ADR-0017 F-4): scan the QR the companion shows, or type its URL and the
 * 8-char code. CAMERA is an <em>optional</em>, runtime-requested permission — declining it is not
 * an error, it just focuses the manual entry. The pairing call and the "test connection" both go
 * through the real {@code shared} {@link DispatchClient}, so this screen and the dispatch path can
 * never disagree about the wire format.
 */
public class WindowsPairingActivity extends AppCompatActivity {

    private static final String TAG = "WindowsPairing";

    private SharedPreferences sp;

    private TextInputEditText urlEt;
    private TextInputEditText codeEt;
    private MaterialButton pairBtn;
    private MaterialButton scanBtn;
    private MaterialButton testBtn;
    private MaterialButton unpairBtn;
    private ProgressBar progress;
    private TextView statusTv;

    /** One in-flight network call at a time — a second tap while pairing is a no-op. */
    private volatile boolean busy = false;

    private final ActivityResultLauncher<ScanOptions> scanLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                if (result.getContents() != null) onQrScanned(result.getContents());
            });

    private final ActivityResultLauncher<String> cameraPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    launchScanner();
                } else {
                    // Declining the camera is fine — the manual path is fully equivalent.
                    Toast.makeText(this, R.string.dictate_pairing_camera_denied, Toast.LENGTH_LONG).show();
                    if (urlEt != null) urlEt.requestFocus();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_windows_pairing);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.activity_windows_pairing), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.dictate_settings_windows_pairing_title);
        }

        sp = getSharedPreferences("net.devemperor.dictate", MODE_PRIVATE);

        urlEt = findViewById(R.id.windows_pairing_url_et);
        codeEt = findViewById(R.id.windows_pairing_code_et);
        pairBtn = findViewById(R.id.windows_pairing_pair_btn);
        scanBtn = findViewById(R.id.windows_pairing_scan_btn);
        testBtn = findViewById(R.id.windows_pairing_test_btn);
        unpairBtn = findViewById(R.id.windows_pairing_unpair_btn);
        progress = findViewById(R.id.windows_pairing_progress);
        statusTv = findViewById(R.id.windows_pairing_status_tv);

        scanBtn.setOnClickListener(v -> onScanClicked());
        pairBtn.setOnClickListener(v -> onPairClicked());
        testBtn.setOnClickListener(v -> onTestClicked());
        unpairBtn.setOnClickListener(v -> onUnpairClicked());

        refreshPairedState();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    // ── QR scan ─────────────────────────────────────────────────────────────────────────

    private void onScanClicked() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            launchScanner();
        } else {
            cameraPermLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void launchScanner() {
        ScanOptions options = new ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .setPrompt(getString(R.string.dictate_pairing_scan_prompt));
        scanLauncher.launch(options);
    }

    private void onQrScanned(String raw) {
        PairingInfo info = PairingUri.INSTANCE.parse(raw);
        if (info == null) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dictate_pairing_invalid_qr_title)
                    .setMessage(R.string.dictate_pairing_invalid_qr_message)
                    .setPositiveButton(R.string.dictate_okay, null)
                    .show();
            return;
        }
        // Reflect the scan into the manual fields so the user sees what was read, then pair.
        urlEt.setText(info.getBaseUrl());
        codeEt.setText(info.getToken());
        attemptPair(info.getBaseUrl(), info.getToken());
    }

    // ── Pairing ─────────────────────────────────────────────────────────────────────────

    private void onPairClicked() {
        String baseUrl = urlEt.getText() == null ? "" : urlEt.getText().toString().trim();
        String code = codeEt.getText() == null ? "" : codeEt.getText().toString().trim().toUpperCase();
        if (TextUtils.isEmpty(baseUrl) || TextUtils.isEmpty(code)) {
            Toast.makeText(this, R.string.dictate_pairing_fill_fields, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            baseUrl = "http://" + baseUrl;
        }
        attemptPair(baseUrl, code);
    }

    private void attemptPair(String baseUrl, String token) {
        if (busy) return;
        setBusy(true);

        // A stable per-install id, generated once and reused across re-pairings (ADR-0017).
        String deviceId = DictatePrefsKt.get(sp, Pref.WindowsDeviceId.INSTANCE);
        if (TextUtils.isEmpty(deviceId)) deviceId = UUID.randomUUID().toString();
        final String finalDeviceId = deviceId;
        final String finalBaseUrl = baseUrl;
        final String deviceName = Build.MODEL == null ? "Android" : Build.MODEL;

        new Thread(() -> {
            DispatchClient client = new DispatchClient(
                    new OkHttpDispatchTransport(finalBaseUrl, OkHttpDispatchTransport.Companion.defaultClient()),
                    () -> null);
            DispatchResult<PairResponse> result = client.pair(token, finalDeviceId, deviceName);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                setBusy(false);
                if (result instanceof DispatchResult.Success) {
                    PairResponse response = ((DispatchResult.Success<PairResponse>) result).getValue();
                    if (persistPairing(finalBaseUrl, response)) {
                        Toast.makeText(this, R.string.dictate_pairing_success, Toast.LENGTH_SHORT).show();
                        refreshPairedState();
                    } else {
                        // The handshake succeeded but the secret could not be stored securely — do
                        // not leave the user "paired" with no usable secret (see persistPairing).
                        new MaterialAlertDialogBuilder(this)
                                .setTitle(R.string.dictate_pairing_failed_title)
                                .setMessage(R.string.dictate_pairing_error_generic)
                                .setPositiveButton(R.string.dictate_okay, null)
                                .show();
                    }
                } else {
                    DispatchError error = ((DispatchResult.Failure) result).getError();
                    showPairingError(error);
                }
            });
        }).start();
    }

    /**
     * Store the pairing device secret in the {@link SecretStore}, then the non-secret prefs.
     *
     * <p>The secret goes into the encrypted store (spec secretstore.md §7.2) — never back into a
     * plaintext pref. Order is <em>secret-before-url</em>, mirroring the migration's put-before-mark
     * invariant: {@link WindowsTarget#isPaired} (which keys on the url being present) must never
     * become true without a stored secret. If the store is unavailable or the put fails, nothing is
     * written and {@code false} is returned, so the caller can surface an error instead of showing
     * the user "paired" with no usable secret (keeps {@code url-present ⟺ secret-present}).
     *
     * @return true iff the secret was stored and the non-secret prefs written.
     */
    private boolean persistPairing(String baseUrl, PairResponse response) {
        SecretStore store = AndroidKeystoreSecretStore.create(this);
        if (!store.getAvailable()) {
            Log.w(TAG, "secret store unavailable — cannot persist pairing secret");
            return false;
        }
        try {
            store.put(PairingSecrets.DEVICE_SECRET_REF,
                    response.getDeviceSecret().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // SecretStore.put throws SecretStoreException (StorageIo/Unavailable). It is a Kotlin
            // exception with no @Throws, so Java cannot catch the specific checked type — catch
            // broadly and treat any store failure as "could not pair" rather than crash pairing.
            Log.w(TAG, "pairing secret could not be stored", e);
            return false;
        }
        SharedPreferences.Editor editor = sp.edit();
        DictatePrefsKt.put(editor, Pref.WindowsTargetUrl.INSTANCE, baseUrl);
        DictatePrefsKt.put(editor, Pref.WindowsDeviceId.INSTANCE, response.getDeviceId());
        DictatePrefsKt.put(editor, Pref.WindowsServerName.INSTANCE, response.getServerName());
        editor.apply();
        return true;
    }

    private void showPairingError(DispatchError error) {
        int message;
        if (error instanceof DispatchError.TokenExpired) {
            message = R.string.dictate_pairing_error_token_expired;
        } else if (error instanceof DispatchError.TokenInvalid) {
            message = R.string.dictate_pairing_error_token_invalid;
        } else if (error instanceof DispatchError.TokenConsumed) {
            message = R.string.dictate_pairing_error_token_consumed;
        } else if (error instanceof DispatchError.Unreachable) {
            message = R.string.dictate_windows_unreachable_msg;
        } else if (error instanceof DispatchError.ProtocolMismatch) {
            message = R.string.dictate_pairing_error_protocol;
        } else {
            message = R.string.dictate_pairing_error_generic;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dictate_pairing_failed_title)
                .setMessage(message)
                .setPositiveButton(R.string.dictate_okay, null)
                .show();
    }

    // ── Test connection ─────────────────────────────────────────────────────────────────

    private void onTestClicked() {
        if (busy) return;
        WindowsTarget target = WindowsTarget.resolve(sp, AndroidKeystoreSecretStore.create(this));
        if (target == null) return;
        setBusy(true);
        new Thread(() -> {
            DispatchClient client = new DispatchClient(
                    new OkHttpDispatchTransport(target.getBaseUrl(), OkHttpDispatchTransport.Companion.defaultClient()),
                    target::credentials);
            DispatchResult<HealthResponse> result = client.health();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                setBusy(false);
                if (result instanceof DispatchResult.Success) {
                    HealthResponse health = ((DispatchResult.Success<HealthResponse>) result).getValue();
                    if (health.getCanInsert()) {
                        Toast.makeText(this, getString(R.string.dictate_pairing_test_ok, health.getServerName()),
                                Toast.LENGTH_LONG).show();
                    } else {
                        // The companion cannot type (Linux/macOS / no inserter) — warn now, not on
                        // the first dictation, that text arrives but is not typed (ADR-0018).
                        new MaterialAlertDialogBuilder(this)
                                .setTitle(R.string.dictate_pairing_test_ok_title)
                                .setMessage(getString(R.string.dictate_pairing_test_no_insert, health.getServerName()))
                                .setPositiveButton(R.string.dictate_okay, null)
                                .show();
                    }
                } else {
                    Toast.makeText(this, R.string.dictate_windows_unreachable_msg, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    // ── Unpair ──────────────────────────────────────────────────────────────────────────

    private void onUnpairClicked() {
        // Delete the secret from the store (it is no longer a plaintext pref — spec §7.2). delete()
        // is a no-op if nothing is stored, so it never throws.
        AndroidKeystoreSecretStore.create(this).delete(PairingSecrets.DEVICE_SECRET_REF);
        SharedPreferences.Editor editor = sp.edit();
        // Clearing the url already flips WindowsTarget.isPaired to false (the paired-predicate).
        DictatePrefsKt.put(editor, Pref.WindowsTargetUrl.INSTANCE, "");
        DictatePrefsKt.put(editor, Pref.WindowsServerName.INSTANCE, "");
        // Disarming auto-send on unpair is mandatory: an "active" auto-send mode without a target
        // would tip every dictation into the pending-part path (plan §1.2).
        DictatePrefsKt.put(editor, Pref.WindowsAutoSendEnabled.INSTANCE, false);
        // The device id is deliberately kept so a re-pair reuses the same stable identity.
        editor.apply();
        refreshPairedState();
    }

    // ── UI state ────────────────────────────────────────────────────────────────────────

    private void refreshPairedState() {
        // Non-secret predicate: no target object (which would need the SecretStore) is needed just
        // to render the paired UI; the display name is the non-secret server-name pref.
        boolean paired = WindowsTarget.isPaired(sp);
        testBtn.setVisibility(paired ? View.VISIBLE : View.GONE);
        unpairBtn.setVisibility(paired ? View.VISIBLE : View.GONE);
        statusTv.setText(paired
                ? getString(R.string.dictate_pairing_status_paired,
                        DictatePrefsKt.get(sp, Pref.WindowsServerName.INSTANCE))
                : getString(R.string.dictate_settings_windows_not_paired));
    }

    private void setBusy(boolean value) {
        busy = value;
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        pairBtn.setEnabled(!value);
        scanBtn.setEnabled(!value);
        testBtn.setEnabled(!value);
    }
}
