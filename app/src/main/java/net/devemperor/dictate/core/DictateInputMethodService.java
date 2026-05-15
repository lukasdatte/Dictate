package net.devemperor.dictate.core;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.inputmethodservice.InputMethodService;
import android.icu.text.BreakIterator;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.net.Uri;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.widget.Toast;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.emoji2.emojipicker.EmojiPickerView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.google.android.material.button.MaterialButton;

import net.devemperor.dictate.BuildConfig;
import net.devemperor.dictate.DictateUtils;
import net.devemperor.dictate.ai.AIOrchestrator;
import net.devemperor.dictate.database.DictateDatabase;
import net.devemperor.dictate.database.entity.InsertionMethod;
import net.devemperor.dictate.database.entity.InsertionSource;
import net.devemperor.dictate.database.entity.SessionEntity;
import net.devemperor.dictate.keyboard.KeyAction;
import net.devemperor.dictate.keyboard.QwertzKeyboardController;
import net.devemperor.dictate.keyboard.QwertzKeyboardLayout;
import net.devemperor.dictate.keyboard.QwertzKeyboardView;
import net.devemperor.dictate.preferences.DictatePrefsKt;
import net.devemperor.dictate.preferences.InputLanguagesPlugin;
import net.devemperor.dictate.preferences.LanguageLabelResolver;
import net.devemperor.dictate.preferences.Pref;
import net.devemperor.dictate.preferences.PrefsMigration;
import net.devemperor.dictate.R;
import net.devemperor.dictate.database.dao.PromptDao;
import net.devemperor.dictate.database.dao.UsageDao;
import net.devemperor.dictate.database.entity.PromptEntity;
import net.devemperor.dictate.ai.prompt.PromptService;
import net.devemperor.dictate.rewording.PromptEditActivity;
import net.devemperor.dictate.rewording.PromptsKeyboardAdapter;
import net.devemperor.dictate.rewording.PromptsOverviewActivity;
import net.devemperor.dictate.history.HistoryActivity;
import net.devemperor.dictate.settings.DictateSettingsActivity;
import net.devemperor.dictate.state.layout.KeyboardLayoutManager;
import net.devemperor.dictate.state.layout.LogicalButtonId;
import net.devemperor.dictate.state.render.ImeViewBackend;
import net.devemperor.dictate.state.render.RealMotionSurface;
import net.devemperor.dictate.state.render.RecordingAnimationController;
import net.devemperor.dictate.widget.PulseLayout;

import androidx.constraintlayout.motion.widget.MotionLayout;
import java.util.HashMap;
import java.util.Map;

import androidx.room.InvalidationTracker;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// MAIN CLASS
public class DictateInputMethodService extends InputMethodService
        implements PromptQueueManager.PromptQueueCallback,
                   PipelineOrchestrator.PipelineCallback,
                   MainButtonsController.Callback {

    // define handlers and runnables for background tasks
    private static final int DELETE_LOOKBACK_CHARACTERS = 64;

    private Handler mainHandler;
    private Handler deleteHandler;
    private Runnable deleteRunnable;

    // define variables and objects
    private boolean isDeleting = false;
    private long startDeleteTime = 0;
    private int currentDeleteDelay = 50;
    private boolean livePrompt = false;
    private volatile boolean pendingLivePromptChain = false; // true when transcription result should be chained into live prompt
    private boolean vibrationEnabled = true;
    // Block 3b: the audio-focus flag is no longer cached as a service field.
    // The single persistent source of truth is Pref.AudioFocus; the per-session
    // controller field in RecordingStateController owns runtime state.
    // D-13: language state SoT is preferences.LanguageResolver (permanent)
    // + LanguageState.override (transient). Read the IME-effective value
    // via resolveEffectiveLanguage(); mutate via setLanguageFromPicker(code).
    private boolean autoSwitchKeyboard = false;

    // ── New-path recording-drive state (orchestrator is the sole driver) ──

    /**
     * The IME-faithful {@link PipelineConfigResolver} for the new path
     * (R-1 closure, C3-IMPL-1/-2). Instantiated lazily on the first new-path
     * recording and registered with the bound service via
     * {@code LocalBinder.registerPipelineConfigResolver} in
     * {@link #bindAiInfrastructureFromService}. The orchestrator's pipeline
     * runner adapter consults it (through the
     * {@code DelegatingPipelineConfigResolver}) so the new path builds a
     * {@code JobRequest} field-for-field identical to the IME-runtime
     * construction snapshotted by {@link #captureFreshConfigSnapshot}.
     */
    private ImePipelineConfigResolver imePipelineConfigResolver;

    /**
     * The {@code preAllocatedId} UUID minted for the in-flight new-path
     * recording (the same value passed to
     * {@code RecordingAction.StartRecording.sessionId}). Captured at
     * {@link #startRecording()} so {@link #stopRecording()} can key the
     * R-1 config snapshot under it before dispatching the payload-less
     * {@code StopRecordingAndSend} (FN-4). {@code null} when no new-path
     * recording is in flight.
     */
    private String newPathRecordingSessionId;

    /**
     * Transient bridge that lets {@link #restoreUiState()} after view recreation recover the
     * user's auto-enter toggle from the about-to-be-discarded controller instance. Captured in
     * {@link #cleanupOldControllers()} and consumed (then reset to null) in
     * {@link #restoreUiState()}. Without this bridge an in-pipeline user toggle would silently
     * revert to the default preference on rotation (the controller-owned config is new).
     * See refactor plan O-2.
     */
    private Boolean restoreAutoEnter = null;

    /**
     * W1: Transient bridge for restoring ReprocessStaging across view-recreation.
     * Captured in {@link #cleanupOldControllers()} when the active
     * {@link PipelineUiState} is a {@link PipelineUiState.ReprocessStaging},
     * consumed (and reset to null) in {@link #restoreUiState()} by re-entering
     * the staging mode on the fresh controller. Without this bridge, a rotation
     * or theme change during staging drops the user's edited queue/language
     * silently back to Idle.
     */
    private PipelineUiState.ReprocessStaging restoreReprocessStaging = null;

    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private PipelineOrchestrator pipelineOrchestrator;
    private KeyboardUiController uiController;

    /**
     * D-13 (Epic §4 Block C1): the per-view legacy language-controller
     * field was deleted. The permanent language SoT is the static
     * {@link net.devemperor.dictate.preferences.LanguageResolver}
     * (reads/writes the same SharedPreferences keys, no cache); the
     * ReprocessStaging override is the {@code LanguageState.override}
     * axis, written via {@code LanguageAction.SetOverride}. The IME
     * resolves the effective code via {@link #resolveEffectiveLanguage()}
     * and pushes it to the bound orchestrator via
     * {@link #pushPermanentLanguageToOrchestrator()}
     * (Pre-Dispatch-Resolution, Spec 1 §4.11).
     *
     * <p>Cross-instance refresh: when the Settings activity writes the
     * {@code input_languages} / {@code input_language_pos} keys, the IME
     * listener below re-resolves freshly from prefs and re-pushes — no
     * stale-cache invalidation needed (the old {@code lastEffective}
     * cross-instance bug is gone because there is no cache; R-3).</p>
     *
     * <p>Registered in {@link #onCreateInputView()}; deregistered in
     * {@link #cleanupOldControllers()} (view-recreate) and
     * {@link #onDestroy()} (process tear-down).</p>
     */
    private SharedPreferences.OnSharedPreferenceChangeListener inputLanguagesListener;

    /**
     * Block 2 (Quality-Gate K5): bidirectional sync between the Settings
     * Switch-Preference and the Edit-Bar/Single-Row audio-focus toggle. When
     * the user flips the value in Settings while the keyboard is cached, this
     * listener re-applies it to both the icon and the running RecordingState.
     *
     * <p>Registered alongside {@link #inputLanguagesListener} in
     * {@link #onCreateInputView()}; deregistered in
     * {@link #cleanupOldControllers()} (view-recreate) and {@link #onDestroy()}
     * (process tear-down) — same lifecycle pattern.</p>
     *
     * <p>Invariant: the listener fires on every SP write, including the
     * service-internal write inside {@link #onAudioFocusToggled()}. The
     * resulting refresh is idempotent (same value, same icon, same field) so
     * the doubled fire is benign.</p>
     */
    private SharedPreferences.OnSharedPreferenceChangeListener audioFocusListener;

    /**
     * Service-side pipeline observer. Held as a field so it can be detached
     * via {@link KeyboardUiController#removeCallback(PipelineUiCallback)} on
     * view recreate. Phase 1 cross-phase refactor (Quality-Gate K-2): the
     * Service registers via {@code addCallback}, not the deprecated
     * single-slot {@code setCallback}, so multiple {@code PipelineUiCallback}
     * consumers can coexist without a Composite-Wrapper (D-13: the legacy
     * effective-language controller consumer was removed).
     */
    private PipelineUiCallback servicePipelineCallback;
    private Vibrator vibrator;
    private SharedPreferences sp;
    private AudioManager am;
    private AudioFocusRequest audioFocusRequest;

    // Managers (extracted from God-Class)
    private RecordingManager recordingManager;
    private BluetoothScoManager bluetoothScoManager;
    private PromptQueueManager promptQueueManager;
    private KeyboardStateManager stateManager;
    private InfoBarController infoBarController;
    private MainButtonsController mainButtonsController;

    // B5 F-2 — in-IME overlay-permission onboarding info-bar (Spec 3
    // §5.3). The IME owns this view surface (co-located with
    // infoBarController) rather than ImeViewBackend (documented
    // deviation, ADR-0005 Decision-History 2026-05-15). The observer
    // bridges the pipeline StateFlow's onboardingPending axis to the
    // view's visibility; it is (re)started in onCreateInputView once
    // the views + binder exist and stopped in onDestroyInputView.
    private View overlayPermissionInfobar;
    private OverlayOnboardingObserver overlayOnboardingObserver;

    // C15 — New keyboard-layout render path (Spec 2 §11.8 5c). Constructed in
    // onCreateInputView() once the View tree is inflated; attached to the
    // service-side KeyboardLayoutManager via the LocalBinder. Detached in
    // onDestroyInputView() / onDestroy().
    private ImeViewBackend imeViewBackend;
    private KeyboardLayoutManager keyboardLayoutManager; // copy of the service-side instance, for detach

    // Recording controllers (extracted from God-Class)
    private RecordingStateController recordingStateController;
    private RecordingUiController recordingUiController;

    // Prompt data flow: InvalidationTracker auto-reloads prompts when DB changes
    private DictateDatabase dictateDb;
    private InvalidationTracker.Observer promptsInvalidationObserver;
    private final Runnable reloadPromptsRunnable = () -> reloadPrompts();

    // define views
    private ConstraintLayout dictateKeyboardView;
    // C15 — `main_buttons_cl` is now an `androidx.constraintlayout.motion.widget.MotionLayout`
    // (Spec 2 §7 / §11.1). Typed as `ViewGroup` because callers only need
    // basic visibility / parent semantics; the [ImeViewBackend] resolves
    // the concrete MotionLayout via a dedicated findViewById on the same
    // id when constructing its [RealMotionSurface] wrapper.
    private ViewGroup mainButtonsClGroup;
    private MaterialButton editSettingsButton;
    private ConstraintLayout editButtonsKeyboardLl;
    private MaterialButton recordButton;
    private MaterialButton resendButton;
    private MaterialButton backspaceButton;
    private MaterialButton trashButton;
    private MaterialButton spaceButton;
    private MaterialButton pauseButton;
    private MaterialButton enterButton;
    private ConstraintLayout infoCl;
    private TextView infoTv;
    private Button infoYesButton;
    private Button infoNoButton;
    private ConstraintLayout promptsCl;
    private RecyclerView promptsRv;
    private LinearLayout promptRecordingControlsLl;
    private MaterialButton promptRecIndicatorBtn;
    private MaterialButton promptPauseBtn;
    private MaterialButton promptTrashBtn;
    private MaterialButton editUndoButton;
    private MaterialButton editRedoButton;
    private MaterialButton editCutButton;
    private MaterialButton editCopyButton;
    private MaterialButton editPasteButton;
    private MaterialButton editEmojiButton;
    private ConstraintLayout emojiPickerCl;
    private TextView emojiPickerTitleTv;
    private MaterialButton emojiPickerCloseButton;
    private EmojiPickerView emojiPickerView;
    private MaterialButton editNumbersButton;
    private MaterialButton editKeyboardButton;
    private FrameLayout qwertzContainer;
    private QwertzKeyboardView qwertzKeyboardView;
    private QwertzKeyboardController qwertzController;
    private LinearLayout overlayCharactersLl;

    // PulseLayout for recording ripple animation
    private PulseLayout recordPulseLayout;

    // Pipeline cancel button (delegates to PipelineOrchestrator)
    private MaterialButton pipelineCancelBtn;

    // History button
    private MaterialButton editHistoryButton;

    // Block 2: audio-focus toggle buttons (Edit-Bar + Single-Row variant).
    private MaterialButton editAudioFocusButton;
    private MaterialButton audioFocusButton;

    // Keep screen awake while recording
    private boolean keepScreenAwakeApplied = false;

    PromptDao promptDao;
    PromptsKeyboardAdapter promptsAdapter;
    private boolean disableNonSelectionPrompts = false;

    UsageDao usageDao;
    private AIOrchestrator aiOrchestrator;
    private PromptService promptService;
    private AutoFormattingService autoFormattingService;
    private SessionManager sessionManager;
    private SessionTracker sessionTracker;
    private RecordingRepository recordingRepository;

    // ===== Block 2 — DictatePipelineService bind state =====
    //
    // The pipeline service is bound in onCreateInputView() so it is alive
    // while the user has a keyboard (Spec 1 §11.3.1 — latency argument:
    // 50-200 ms first-bind absorbed by the inflate-blocking window of
    // onCreateInputView; binding from IME.onCreate() would risk starting
    // the FGS too early per ADR-0003 §"Required mechanics" item 4).
    //
    // Block 2 only exercises the bind/unbind lifecycle — no
    // state-collect, no dispatching. Block 1b layers on the StateFlow
    // subscription and routes UI events through pipelineBinder.dispatch.
    private DictatePipelineService.LocalBinder pipelineBinder;
    private final ServiceConnection pipelineConnection = new ServiceConnection() {
        // ── onServiceConnected ──
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            // Same-process cast; LocalBinder is a real object, not a proxy.
            DictatePipelineService.LocalBinder localBinder =
                (DictatePipelineService.LocalBinder) service;
            pipelineBinder = localBinder;
            // C8 IMPL-1 closure: pull the service-owned AI infrastructure
            // into the IME's fields so existing call sites (record-button,
            // pipeline-progress, etc.) work without rewriting.
            bindAiInfrastructureFromService(localBinder);
            // C15 — If the view tree is already inflated (race between
            // bindService callback and onCreateInputView), wire the new
            // render path now. Otherwise the next onCreateInputView pass
            // (which always runs while the user has the keyboard open)
            // will pick it up.
            if (dictateKeyboardView != null && imeViewBackend == null) {
                Context themedContext = new ContextThemeWrapper(
                    DictateInputMethodService.this, R.style.Theme_Dictate);
                attachImeViewBackendIfReady(themedContext);
            }
            // D-13 / R-3 boot-before-bind closure: onCreateInputView's
            // pushPermanentLanguageToOrchestrator() ran BEFORE this binder
            // arrived (bindService is async — the common race), so the
            // RefreshFromPref dispatch was skipped by the
            // `pipelineBinder != null` guard and the orchestrator's
            // `state.language.effective` is still the `"system"` boot
            // sentinel. Re-push now that the binder exists so the F-15
            // RenderBackend label and the transcription-config snapshot
            // see the resolved language. Idempotent — the reducer reduces
            // a no-change refresh to null. The unbound→bound transition
            // is the ONLY place this matters; subsequent pref changes
            // already re-push via inputLanguagesListener.
            pushPermanentLanguageToOrchestrator();
        }

        // ── onServiceDisconnected ──
        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Process-crash. In our same-process setup this should never
            // fire — defensive: null the binder so following dispatches
            // hit the not-ready guard rather than a stale instance.
            if (pipelineBinder != null) {
                unbindAiInfrastructureFromService(pipelineBinder);
            }
            pipelineBinder = null;
        }

        // ── onBindingDied ──
        @Override
        public void onBindingDied(ComponentName name) {
            // Permanent breakage of the binding — re-bind. Should not
            // happen in same-process, but follows Spec 1 §11.3.2.
            if (pipelineBinder != null) {
                unbindAiInfrastructureFromService(pipelineBinder);
            }
            try {
                unbindService(this);
            } catch (IllegalArgumentException ignored) {
                // already unbound; no-op
            }
            pipelineBinder = null;
            Intent intent = new Intent(DictateInputMethodService.this, DictatePipelineService.class);
            bindService(intent, this, Context.BIND_AUTO_CREATE);
        }

        // ── onNullBinding ──
        @Override
        public void onNullBinding(ComponentName name) {
            // DictatePipelineService.onBind always returns the singleton
            // LocalBinder. A null here would mean a regression in the
            // service implementation.
            Log.e("DictateIME", "Unexpected null binding for DictatePipelineService");
        }
    };
    private boolean pipelineServiceBindAttempted = false;

    // ===== PromptQueueManager.PromptQueueCallback =====

    @Override
    public void onQueueChanged(List<Integer> queuedIds) {
        if (promptsAdapter == null || mainHandler == null) return;
        mainHandler.post(() -> promptsAdapter.setQueuedPromptOrder(queuedIds));
    }

    // ===== Lifecycle: onCreate() — long-lived objects (survive view recreation) =====

    @Override
    public void onCreate() {
        super.onCreate();
        initLongLivedObjects();
    }

    private void initLongLivedObjects() {
        // 1. Foundation
        mainHandler = new Handler(Looper.getMainLooper());
        deleteHandler = new Handler(Looper.getMainLooper());
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        sp = getSharedPreferences("net.devemperor.dictate", MODE_PRIVATE);
        dictateDb = DictateDatabase.getInstance(this);
        promptDao = dictateDb.promptDao();
        usageDao = dictateDb.usageDao();
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        // 2. Pref migration (must happen before any AI service is bound to a Pref).
        PrefsMigration.migrateProviderPrefs(sp);

        // 3. AI infrastructure ownership transferred to DictatePipelineService
        //    (Spec 1 §11.2.2 step 7 — IMPL-1 closure in B3 C8).
        //    The Service constructs AIOrchestrator, AutoFormattingService,
        //    PromptQueueManager, SessionManager, SessionTracker, PromptService,
        //    PipelineOrchestrator, RecordingRepository, and calls
        //    JobExecutor.initialize(...). The IME reads them from the
        //    LocalBinder in onServiceConnected (see #bindAiInfrastructureFromService).
        //    Until then, these fields are null; all callers gate on
        //    pipelineBinder != null (record button is disabled, etc.).

        // 4. Audio Focus seam — kept here because the IME-side
        //    RecordingStateController is constructed below and consumes it.
        //    The Service ALSO holds its own production AudioFocusGate
        //    (see DictatePipelineService.buildAudioFocusGate); both gates
        //    bind to the same AudioManager but request focus independently.
        //    During the C8 migration window the IME-side gate is the
        //    primary driver (the orchestrator-side audio module fires only
        //    via emitted actions from the service-side gate's
        //    OnAudioFocusChangeListener — no double-request because the
        //    IME-side gate is the only one currently called from a
        //    recording-start UI click).
        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(focusChange -> {
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                        if (recordingStateController != null
                                && recordingStateController.getState() instanceof RecordingState.Active) {
                            recordingStateController.togglePause();
                        }
                    }
                })
                .build();

        // 5. Recording (setter-injection breaks circular dependency)
        // Block 0g: AudioManager + AudioFocusRequest are wrapped in a RealAudioFocusGate
        // so the controller can be unit-tested against a counter-based fake.
        recordingStateController = new RecordingStateController(
            new RealAudioFocusGate(am, audioFocusRequest), new AmplitudeProcessor(), mainHandler);
        recordingManager = new RecordingManager(recordingStateController);
        bluetoothScoManager = new BluetoothScoManager(this, am, recordingStateController);
        recordingStateController.setManagers(recordingManager, bluetoothScoManager);

        // 6. Pipeline construction + JobExecutor.initialize moved to
        //    DictatePipelineService.onCreate (IMPL-1 closure, C8). The
        //    pipelineOrchestrator field below is populated from the
        //    binder in #bindAiInfrastructureFromService.

        // 7. User ID (one-time)
        if (DictatePrefsKt.get(sp, Pref.UserId.INSTANCE).equals("null")) {
            DictatePrefsKt.put(sp.edit(), Pref.UserId.INSTANCE,
                String.valueOf((int) (Math.random() * 1000000))).apply();
        }
    }

    /**
     * Populate the IME's AI-infrastructure fields from the bound service.
     * Called from {@link #pipelineConnection}.onServiceConnected after the
     * binder is non-null. After this method returns, fields like
     * {@link #aiOrchestrator}, {@link #pipelineOrchestrator}, etc. are
     * ready for use by record-button clicks and other user-triggered
     * paths.
     *
     * IMPL-1 closure (C8): the Service constructs the heavy AI stack;
     * the IME borrows the references. The IME also registers itself as
     * the active PipelineCallback delegate (via PipelineCallbackBridge)
     * and the PromptQueueCallback delegate so its callback methods
     * still fire during recording/pipeline.
     */
    private void bindAiInfrastructureFromService(DictatePipelineService.LocalBinder binder) {
        aiOrchestrator = binder.getAiOrchestrator();
        autoFormattingService = binder.getAutoFormattingService();
        promptQueueManager = binder.getPromptQueueManager();
        sessionManager = binder.getSessionManager();
        sessionTracker = binder.getSessionTracker();
        promptService = binder.getPromptService();
        recordingRepository = binder.getRecordingRepository();
        pipelineOrchestrator = binder.getPipelineOrchestrator();

        // Register the IME-side callbacks. The bridge routes
        // PipelineOrchestrator callbacks back to this IME instance;
        // unregistration happens in onDestroy / onBindingDied.
        binder.registerPipelineCallback(this);
        binder.registerPromptQueueCallback(this);
        binder.registerInputConnectionProvider(this::getCurrentInputConnection);

        // C5 (R-1 closure) — install the IME-faithful PipelineConfigResolver
        // so the new recording-drive path builds a JobRequest
        // field-for-field identical to the IME-runtime construction
        // snapshotted by captureFreshConfigSnapshot(). The orchestrator's
        // pipeline runner adapter consults it (through the
        // DelegatingPipelineConfigResolver) on the new recording path and
        // the imported-audio-file path (C7-IMPL-1).
        if (imePipelineConfigResolver == null) {
            imePipelineConfigResolver = new ImePipelineConfigResolver(
                    this::getFilesDir,
                    new DefaultPipelineConfigResolver(this::getFilesDir));
        }
        binder.registerPipelineConfigResolver(imePipelineConfigResolver);
    }

    /**
     * Inverse of {@link #bindAiInfrastructureFromService}. Called when
     * the binder dies or when the IME is destroyed.
     */
    private void unbindAiInfrastructureFromService(DictatePipelineService.LocalBinder binder) {
        try {
            binder.registerPipelineCallback(null);
            binder.registerPromptQueueCallback(null);
            binder.registerInputConnectionProvider(null);
            binder.registerPipelineConfigResolver(null);
        } catch (Throwable t) {
            Log.w("DictateIME", "unbindAiInfrastructureFromService: callback unregister threw", t);
        }
        // Leave the field references in place — the underlying objects
        // are owned by the Service and survive bind/unbind cycles. The
        // next bind reuses the same singletons.
    }

    // start method that is called when user opens the keyboard (also on view recreation / rotation)
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public View onCreateInputView() {
        Context context = new ContextThemeWrapper(this, R.style.Theme_Dictate);

        // ── 0. Start + bind the pipeline service (Block 2, Spec 1 §11.3.1) ──
        // Idempotent across view-recreate (rotation, theme-switch): the second
        // bindService is a no-op once a connection exists. startForegroundService
        // is also idempotent — Android coalesces start calls. We skip on
        // re-entry to avoid spamming Logcat with redundant onCreate logs.
        if (!pipelineServiceBindAttempted) {
            pipelineServiceBindAttempted = true;
            Intent pipelineIntent = new Intent(this, DictatePipelineService.class);
            ContextCompat.startForegroundService(this, pipelineIntent);
            // bindService returns false when the system cannot resolve the
            // component (missing manifest entry, permission denied,
            // package-manager failure). Without checking the return value
            // pipelineServiceBindAttempted would stay true with no
            // ServiceConnection ever firing, and the matching unbindService
            // in onDestroy would raise IllegalArgumentException. Reset the
            // flag on failure so a subsequent onCreateInputView can retry.
            boolean bound = bindService(pipelineIntent, pipelineConnection, Context.BIND_AUTO_CREATE);
            if (!bound) {
                Log.e("DictateIME", "bindService(DictatePipelineService) returned false");
                pipelineServiceBindAttempted = false;
            }
        }

        // ── 1. Clean up old controllers (on view recreation, not first call) ──
        cleanupOldControllers();

        // ── 2. Preferences that may change between rotations ──
        vibrationEnabled = DictatePrefsKt.get(sp, Pref.Vibration.INSTANCE);
        // D-13: language state lives in the LanguageState orchestrator axis
        // + the static preferences.LanguageResolver (no service field).
        // Pos preference is managed exclusively through the resolver's
        // persistInputLanguagesAndPos pathway.

        // ── 3. View inflation + findViewByIds ──
        dictateKeyboardView = (ConstraintLayout) LayoutInflater.from(context).inflate(R.layout.activity_dictate_keyboard_view, null);
        dictateKeyboardView.setKeepScreenOn(false);
        keepScreenAwakeApplied = false;
        ViewCompat.setOnApplyWindowInsetsListener(dictateKeyboardView, (v, insets) -> {
            v.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom);
            return insets;  // fix for overlapping with navigation bar on Android 15+
        });

        mainButtonsClGroup = dictateKeyboardView.findViewById(R.id.main_buttons_cl);
        editSettingsButton = dictateKeyboardView.findViewById(R.id.edit_settings_btn);
        editButtonsKeyboardLl = dictateKeyboardView.findViewById(R.id.edit_buttons_keyboard_ll);
        recordPulseLayout = dictateKeyboardView.findViewById(R.id.record_pulse_layout);
        recordButton = dictateKeyboardView.findViewById(R.id.record_btn);
        resendButton = dictateKeyboardView.findViewById(R.id.resend_btn);
        backspaceButton = dictateKeyboardView.findViewById(R.id.backspace_btn);
        trashButton = dictateKeyboardView.findViewById(R.id.trash_btn);
        spaceButton = dictateKeyboardView.findViewById(R.id.space_btn);
        pauseButton = dictateKeyboardView.findViewById(R.id.pause_btn);
        enterButton = dictateKeyboardView.findViewById(R.id.enter_btn);

        infoCl = dictateKeyboardView.findViewById(R.id.info_cl);
        infoTv = dictateKeyboardView.findViewById(R.id.info_tv);
        infoYesButton = dictateKeyboardView.findViewById(R.id.info_yes_btn);
        infoNoButton = dictateKeyboardView.findViewById(R.id.info_no_btn);

        promptsCl = dictateKeyboardView.findViewById(R.id.prompts_keyboard_cl);
        promptsRv = dictateKeyboardView.findViewById(R.id.prompts_keyboard_rv);
        promptRecordingControlsLl = dictateKeyboardView.findViewById(R.id.prompt_recording_controls_ll);
        promptRecIndicatorBtn = dictateKeyboardView.findViewById(R.id.prompt_rec_indicator_btn);
        promptPauseBtn = dictateKeyboardView.findViewById(R.id.prompt_pause_btn);
        promptTrashBtn = dictateKeyboardView.findViewById(R.id.prompt_trash_btn);

        editUndoButton = dictateKeyboardView.findViewById(R.id.edit_undo_btn);
        editRedoButton = dictateKeyboardView.findViewById(R.id.edit_redo_btn);
        editCutButton = dictateKeyboardView.findViewById(R.id.edit_cut_btn);
        editCopyButton = dictateKeyboardView.findViewById(R.id.edit_copy_btn);
        editPasteButton = dictateKeyboardView.findViewById(R.id.edit_paste_btn);
        editEmojiButton = dictateKeyboardView.findViewById(R.id.edit_emoji_btn);
        editNumbersButton = dictateKeyboardView.findViewById(R.id.edit_numbers_btn);
        editKeyboardButton = dictateKeyboardView.findViewById(R.id.edit_keyboard_btn);
        emojiPickerCl = dictateKeyboardView.findViewById(R.id.emoji_picker_cl);
        emojiPickerTitleTv = dictateKeyboardView.findViewById(R.id.emoji_picker_title_tv);
        emojiPickerCloseButton = dictateKeyboardView.findViewById(R.id.emoji_picker_close_btn);
        emojiPickerView = dictateKeyboardView.findViewById(R.id.emoji_picker_view);
        qwertzContainer = dictateKeyboardView.findViewById(R.id.qwertz_keyboard_container);
        qwertzKeyboardView = new QwertzKeyboardView(context);
        qwertzContainer.addView(qwertzKeyboardView);
        qwertzController = new QwertzKeyboardController(
            qwertzKeyboardView,
            () -> getCurrentInputConnection(),
            () -> { vibrate(); return kotlin.Unit.INSTANCE; },
            () -> { deleteOneCharacter(); return kotlin.Unit.INSTANCE; },
            () -> { performEnterAction(); return kotlin.Unit.INSTANCE; },
            () -> { hideQwertzKeyboard(); return kotlin.Unit.INSTANCE; },
            () -> { onRecordClicked(); return kotlin.Unit.INSTANCE; },
            () -> {
                // Re-apply recording/pipeline icon after layout rebuild (shift toggle, layout switch)
                if (recordingUiController != null && recordingStateController != null) {
                    if (uiController != null && uiController.getState() instanceof PipelineUiState.Running) {
                        // Pipeline active — layout rebuild: we need a fresh one-shot setup AND
                        // an immediate timer text update so the button isn't stale until the next tick.
                        PipelineUiState.Running s = (PipelineUiState.Running) uiController.getState();
                        recordingUiController.enterPipelineDisplay(s);
                        recordingUiController.updatePipelineTimer(
                            s, uiController.getLatestPipelineElapsedMs());
                    } else {
                        recordingUiController.updateQwertzRecButton(
                            recordingStateController.getState().isRecordingOrPaused()
                        );
                    }
                }
                return kotlin.Unit.INSTANCE;
            }
        );

        overlayCharactersLl = dictateKeyboardView.findViewById(R.id.overlay_characters_ll);

        // Pipeline cancel button
        pipelineCancelBtn = dictateKeyboardView.findViewById(R.id.pipeline_cancel_btn);

        // ── 4. View-dependent controllers ──
        infoBarController = new InfoBarController(
            infoCl, infoTv, infoYesButton, infoNoButton,
            () -> { openSettingsActivity(); return kotlin.Unit.INSTANCE; },
            intent -> { startActivity(intent); return kotlin.Unit.INSTANCE; },
            sp, getResources(), () -> getTheme()
        );

        // B5 F-2 — in-IME overlay-permission onboarding info-bar
        // (Spec 3 §5.3). The IME owns this surface (research §4.3 /
        // ADR-0005 Decision-History 2026-05-15 deviation: NOT in
        // ImeViewBackend, which has a button-map-only contract). Grant
        // launches the System Settings deep-link (the IME is a Context;
        // FLAG_ACTIVITY_NEW_TASK is mandatory from a non-Activity) and
        // dispatches RequestOverlayPermission; the permission result is
        // picked up by F-3's onStartInputView refresh() on return.
        // "Later" permanently dismisses via DismissOverlayOnboarding.
        overlayPermissionInfobar = dictateKeyboardView.findViewById(R.id.overlay_permission_infobar);
        View overlayPermGrantBtn = dictateKeyboardView.findViewById(R.id.overlay_perm_grant_btn);
        View overlayPermDismissBtn = dictateKeyboardView.findViewById(R.id.overlay_perm_dismiss_btn);
        overlayPermGrantBtn.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                Log.w("DictateIME", "Failed to launch overlay-permission settings", e);
            }
            if (pipelineBinder != null) {
                pipelineBinder.dispatch(
                        net.devemperor.dictate.state.Action.OverlayAction.RequestOverlayPermission.INSTANCE);
            }
            overlayPermissionInfobar.setVisibility(View.GONE);
        });
        overlayPermDismissBtn.setOnClickListener(v -> {
            if (pipelineBinder != null) {
                pipelineBinder.dispatch(
                        net.devemperor.dictate.state.Action.OverlayAction.DismissOverlayOnboarding.INSTANCE);
            }
            overlayPermissionInfobar.setVisibility(View.GONE);
        });

        // History button
        editHistoryButton = dictateKeyboardView.findViewById(R.id.edit_history_btn);

        // Block 2: audio-focus toggle buttons (Edit-Bar + Single-Row variant).
        editAudioFocusButton = dictateKeyboardView.findViewById(R.id.edit_audio_focus_btn);
        audioFocusButton = dictateKeyboardView.findViewById(R.id.audio_focus_btn);

        View pipelineProgressLl = dictateKeyboardView.findViewById(R.id.pipeline_progress_ll);

        // KeyboardStateManager (deterministic visibility calculator)
        // Note: recordingStateController and uiController are initialized after stateManager,
        // but lambdas are evaluated lazily, so this is safe.
        // C15: action_row + input_row are gone (MotionLayout owns the layout
        // switch); KeyboardViews no longer carries them.
        KeyboardViews keyboardViews = new KeyboardViews(
            mainButtonsClGroup, editButtonsKeyboardLl, promptsCl, emojiPickerCl,
            qwertzContainer, overlayCharactersLl, pauseButton, trashButton,
            promptRecordingControlsLl, promptTrashBtn,
            promptsRv, pipelineProgressLl,
            recordPulseLayout, spaceButton, backspaceButton,
            enterButton, resendButton, audioFocusButton);
        stateManager = new KeyboardStateManager(
            keyboardViews,
            () -> recordingStateController != null && recordingStateController.getState() instanceof RecordingState.Active,
            () -> recordingStateController != null && recordingStateController.getState() instanceof RecordingState.Paused,
            () -> pipelineOrchestrator != null && pipelineOrchestrator.isRunning(),
            () -> DictatePrefsKt.get(sp, Pref.RewordingEnabled.INSTANCE),
            keepAwake -> { updateKeepScreenAwake(keepAwake); return kotlin.Unit.INSTANCE; },
            infoBarController,
            () -> uiController != null && uiController.getState() instanceof PipelineUiState.Running,
            /* isReprocessStaging */ () -> uiController != null
                    && uiController.getState() instanceof PipelineUiState.ReprocessStaging
        );

        // KeyboardUiController (wraps pipeline progress views, delegates visibility to stateManager).
        // Block-1a Quick-Win (Spec 1 §11.2.2 step 2): the controller now also owns the
        // record-button-appearance resolver for the recording axis. The dictate-button
        // label provider is wired here so the Idle branch in
        // KeyboardUiController.applyRecordButtonForRecording can read it without
        // taking a dependency on the LanguageResolver / SharedPreferences plumbing.
        uiController = new KeyboardUiController(new KeyboardUiController.PipelineViews(
            dictateKeyboardView.findViewById(R.id.pipeline_steps_container),
            dictateKeyboardView.findViewById(R.id.pipeline_scroll_view),
            recordButton,
            infoCl,
            LayoutInflater.from(context),
            mainHandler
        ), stateManager, () -> getDictateButtonText());

        StaggeredGridLayoutManager promptsLayoutManager =
                new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.HORIZONTAL);
        promptsLayoutManager.setGapStrategy(StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS);
        promptsRv.setLayoutManager(promptsLayoutManager);

        // MainButtonsController: handles all button registration, overlay init, and theming
        mainButtonsController = new MainButtonsController(
            new MainButtonViews(
                recordButton, resendButton, backspaceButton, trashButton,
                spaceButton, pauseButton, enterButton, editSettingsButton,
                editUndoButton, editRedoButton, editCutButton, editCopyButton,
                editPasteButton, editEmojiButton, editNumbersButton, editKeyboardButton,
                editHistoryButton, emojiPickerCloseButton, emojiPickerView,
                overlayCharactersLl, pipelineCancelBtn, infoYesButton, infoNoButton,
                recordPulseLayout,
                editAudioFocusButton, audioFocusButton
            ),
            sp, stateManager, this,
            () -> getCurrentInputConnection(),
            qwertzKeyboardView.getKeyPressAnimator()
        );
        mainButtonsController.registerAllListeners();
        mainButtonsController.initializeKeyPressAnimations();
        // Block 2 (Quality-Gate K-Block-2): paint the audio-focus icon from the
        // persisted Pref value once the freshly inflated buttons exist. Without
        // this, both buttons would show the XML default (volume_off ≙ "enabled")
        // even when the user disabled AudioFocus across a previous session.
        mainButtonsController.refreshAudioFocusIcon(DictatePrefsKt.get(sp, Pref.AudioFocus.INSTANCE));

        // C15 — KeyboardLayoutModeController + KSM.setLayoutModeController
        // are gone (Spec 2 §11.8 5d). MotionLayout + LayoutCatalog now own
        // the two-row vs. single-row switch. The handler invoked when the
        // edit_numbers_btn is long-pressed (`onSingleRowModeToggled`) now
        // writes Pref.SingleRowMode and triggers a state re-emit; the
        // attached ImeViewBackend re-renders against the updated state
        // and asks MotionLayout to transition to the new scene-id.

        // Prompt trash control: delegate to same action as main trash
        promptTrashBtn.setOnClickListener(v -> {
            vibrate();
            onTrashClicked();
        });

        // RecordingUiController (needs views + animation)
        float displayDensity = recordButton.getResources().getDisplayMetrics().density;
        net.devemperor.dictate.widget.RecordingAnimation recordingAnimation =
            new net.devemperor.dictate.widget.BorderGlowAnimation(
                DictatePrefsKt.get(sp, Pref.AccentColor.INSTANCE),
                AppCompatResources.getDrawable(context, R.drawable.ic_baseline_send_20),
                new net.devemperor.dictate.widget.AmplitudeVisualizerDrawable.BarCountMode.Fixed(30),
                0.35f,  // max brightness boost
                displayDensity
            );
        // Block-1a Quick-Win: the previously combined "audio file exists AND
        // Pref.ResendButton" lambda is split into two independent axes so the
        // isResendVisible helper receives them separately (mirrors the
        // future Block-5 LayoutCatalog RESEND-slot predicate, Spec 2 §3.2).
        // The recordButton-appearance lambda points at
        // KeyboardUiController.applyRecordButtonForRecording — that resolver
        // combines this with the pipeline axis it already owns.
        // The pipelineStateProvider supplies the live pipeline axis to the
        // RecordingUiController's resend-visibility call sites so a
        // non-stop Idle transition (view-recreate restoreUiState, language-
        // flip, cancel-recording paths) cannot evaluate against a stale
        // PipelineUiState.Idle literal. SoT is KeyboardUiController.state.
        recordingUiController = new RecordingUiController(
            recordButton, pauseButton, resendButton,
            recordingAnimation, stateManager, this,
            () -> getDictateButtonText(),
            () -> DictatePrefsKt.get(sp, Pref.Animations.INSTANCE),
            () -> new File(getCacheDir(), DictatePrefsKt.get(sp, Pref.LastFileName.INSTANCE)).exists(),
            () -> DictatePrefsKt.get(sp, Pref.ResendButton.INSTANCE),
            () -> uiController != null ? uiController.getState() : PipelineUiState.Idle.INSTANCE,
            newRecordingState -> {
                if (uiController != null) {
                    uiController.applyRecordButtonForRecording(newRecordingState);
                }
                return kotlin.Unit.INSTANCE;
            },
            () -> qwertzKeyboardView != null ? qwertzKeyboardView.findButtonForAction(KeyAction.RECORD) : null,
            promptRecIndicatorBtn,
            promptPauseBtn,
            () -> { vibrate(); onPauseClicked(); return kotlin.Unit.INSTANCE; },
            () -> { vibrate(); stopRecording(); return kotlin.Unit.INSTANCE; }
        );

        // ── 4a. Language wiring (D-13 — legacy language-controller gone) ──
        // The permanent SoT is the static LanguageResolver; the
        // orchestrator's `state.language.effective` is fed via the
        // payload-bearing RefreshFromPref dispatch (Pre-Dispatch-
        // Resolution, Spec 1 §4.11). Push once now so the first frame
        // (chip + record-button label + RenderBackend F-15 read) shows
        // the resolved language rather than the "system" boot sentinel.
        pushPermanentLanguageToOrchestrator();

        // Cross-instance refresh: when the Settings activity writes the
        // input-languages keys, re-resolve freshly from prefs and re-push.
        // No stale-cache invalidation is needed — LanguageResolver holds
        // no cache, so a fresh read already reflects the external write
        // (the old per-instance lastEffective cross-instance staleness bug
        // is structurally gone — R-3).
        inputLanguagesListener = (changedPrefs, key) -> {
            if (Pref.InputLanguages.INSTANCE.getKey().equals(key)
                    || Pref.InputLanguagePos.INSTANCE.getKey().equals(key)) {
                pushPermanentLanguageToOrchestrator();
            }
        };
        sp.registerOnSharedPreferenceChangeListener(inputLanguagesListener);

        // Block 2 (Quality-Gate K5): mirror Settings-screen toggles into the
        // Edit-Bar / Single-Row buttons + the running RecordingStateController.
        audioFocusListener = (changedPrefs, key) -> {
            if (Pref.AudioFocus.INSTANCE.getKey().equals(key)) {
                boolean newValue = DictatePrefsKt.get(changedPrefs, Pref.AudioFocus.INSTANCE);
                if (mainButtonsController != null) {
                    mainButtonsController.refreshAudioFocusIcon(newValue);
                }
                if (recordingStateController != null) {
                    recordingStateController.setAudioFocusRuntime(newValue);
                }
            }
        };
        sp.registerOnSharedPreferenceChangeListener(audioFocusListener);

        // Pipeline UI callbacks: QWERTZ button updates from pipeline state.
        // Phase 1 cross-phase refactor: Service uses addCallback() rather than
        // the deprecated setCallback() so multiple PipelineUiCallback consumers
        // can coexist on the same KeyboardUiController without a
        // Composite-Wrapper (D-13: the legacy language-controller consumer
        // is gone; the chip/label refresh on staging-override change now
        // runs inside this servicePipelineCallback's onPipelineUiStateChanged).
        servicePipelineCallback = new PipelineUiCallback() {
            @Override
            public void onPipelineTimerTick(@NonNull PipelineUiState.Running state, long elapsedMs) {
                if (recordingUiController != null) {
                    recordingUiController.updatePipelineTimer(state, elapsedMs);
                }
            }

            @Override
            public void onPipelineUiStateChanged(@NonNull PipelineUiState oldState, @NonNull PipelineUiState newState) {
                // Phase 2: language chip is permanently visible; only the
                // editable-queue order tracks ReprocessStaging now (the
                // chip's *enabled* state follows pipeline-running below).
                syncQueueOrder(newState);

                // Phase 2 Quality-Gate W-6: chip stays clickable except while
                // a transcription is in flight (Running / Preparing).
                if (promptsAdapter != null) {
                    boolean pipelineRunning = newState instanceof PipelineUiState.Running
                                          || newState instanceof PipelineUiState.Preparing;
                    promptsAdapter.setLanguageChipEnabled(!pipelineRunning);
                }

                // D-13: the deleted legacy language-controller used to
                // refresh the chip label on every pipeline-state change
                // (so the chip shows the ReprocessStaging override vs the
                // permanent language). That responsibility moved here —
                // entering / leaving staging, or a staging override write,
                // flips what resolveEffectiveLanguage() returns, so the
                // label must re-resolve. Idempotent (same code → same label).
                refreshLanguageChip();

                if (recordingUiController == null) return;
                if (newState instanceof PipelineUiState.Idle) {
                    recordingUiController.updateQwertzRecButton(false);  // QWERTZ → Mic-Icon
                } else if (newState instanceof PipelineUiState.Running) {
                    // One-shot setup only on the actual Idle/Preparing → Running transition.
                    // Running → Running transitions (step completion, auto-enter toggle) skip
                    // enterPipelineDisplay() to avoid redundant setPadding()/re-layout calls;
                    // a trailing updatePipelineTimer() keeps the text in sync.
                    PipelineUiState.Running runningState = (PipelineUiState.Running) newState;
                    if (!(oldState instanceof PipelineUiState.Running)) {
                        recordingUiController.enterPipelineDisplay(runningState);
                    }
                    recordingUiController.updatePipelineTimer(
                        runningState, uiController.getLatestPipelineElapsedMs());
                } else if (newState instanceof PipelineUiState.Preparing) {
                    // Upload phase: make sure the QWERTZ button shows the idle mic icon
                    // (clears any leftover recording-state rendering).
                    recordingUiController.updateQwertzRecButton(false);
                }
            }
        };
        uiController.addCallback(servicePipelineCallback);

        // ── 5. Rewire callbacks (connect long-lived objects to new UI controllers) ──
        // INVARIANT: Order is controllers (above) → rewireCallbacks() → restoreUiState()
        // restoreUiState() triggers state changes that need the callback set in rewireCallbacks().
        // Without prior re-wiring, state changes go nowhere.
        rewireCallbacks();

        // ── 6. Restore current state onto fresh UI ──
        restoreUiState();

        // ── 7. Prompts adapter + InvalidationTracker ──
        setupPromptsAdapter(context);

        // ── 8. Initial language-chip render (Phase 2 §2.1) ──
        // Chip is always visible; refresh sets the label from the current
        // effective language so the very first frame shows the right value
        // before the user interacts with anything.
        refreshLanguageChip();

        // ── 9. C15 — Attach ImeViewBackend to the service-side
        //       KeyboardLayoutManager (Spec 2 §11.8 5c). The service-side
        //       state-collect coroutine fans every emit into the
        //       attached backend; click → onAction → orchestrator.dispatch.
        //
        //       Skipped silently when the service isn't bound yet (the
        //       record path still works through the legacy MainButtonsController
        //       + KeyboardUiController + RecordingUiController flow). Once
        //       the binder arrives in onServiceConnected the new path
        //       comes online on the NEXT view-recreate (a bound service
        //       is also a precondition for the attach because we need
        //       its ModuleServices reference for the resolvers' audio-file
        //       factory access).
        attachImeViewBackendIfReady(context);

        return dictateKeyboardView;
    }

    /**
     * Construct an {@link ImeViewBackend} for the freshly inflated view
     * tree and attach it to the service-side {@link KeyboardLayoutManager}.
     *
     * Called at the tail of {@link #onCreateInputView()}; the matching
     * detach lives in {@link #cleanupOldControllers()} (view-recreate)
     * and {@link #onDestroy()} (process tear-down).
     *
     * Idempotent — a second call without a preceding detach raises in
     * {@link KeyboardLayoutManager#attachBackend(net.devemperor.dictate.state.layout.RenderBackend)}.
     * The caller is expected to detach first (which is what
     * cleanupOldControllers does on view-recreate).
     */
    private void attachImeViewBackendIfReady(Context context) {
        if (pipelineBinder == null) {
            // Service not bound yet. The onServiceConnected callback
            // will re-attempt the wiring once the binder arrives; in
            // the meantime the legacy controllers still drive the UI.
            return;
        }
        // B4-VAL F-17: Java findViewById<T> throws ClassCastException on
        // type-mismatch rather than returning null — defend via instanceof
        // so the log warning actually fires in the broken-layout case.
        View mainButtonsView = dictateKeyboardView.findViewById(R.id.main_buttons_cl);
        if (!(mainButtonsView instanceof MotionLayout)) {
            Log.w("DictateIME", "main_buttons_cl is not a MotionLayout — ImeViewBackend not attached");
            return;
        }
        MotionLayout motionLayout = (MotionLayout) mainButtonsView;
        // Build the LogicalButtonId → View map. All nine state-driven
        // buttons resolve from the inflated tree; WIDGET_TOGGLE was
        // added in C13 (placeholder icon, B5 supplies the real
        // implementation).
        Map<LogicalButtonId, View> buttonViews = new HashMap<>();
        buttonViews.put(LogicalButtonId.RECORD, recordButton);
        buttonViews.put(LogicalButtonId.RESEND, resendButton);
        buttonViews.put(LogicalButtonId.BACKSPACE, backspaceButton);
        buttonViews.put(LogicalButtonId.AUDIO_FOCUS, audioFocusButton);
        buttonViews.put(LogicalButtonId.TRASH, trashButton);
        buttonViews.put(LogicalButtonId.SPACE, spaceButton);
        buttonViews.put(LogicalButtonId.PAUSE, pauseButton);
        buttonViews.put(LogicalButtonId.ENTER, enterButton);
        View widgetToggleBtn = dictateKeyboardView.findViewById(R.id.widget_toggle_btn);
        if (widgetToggleBtn != null) {
            buttonViews.put(LogicalButtonId.WIDGET_TOGGLE, widgetToggleBtn);
        }

        keyboardLayoutManager = pipelineBinder.getKeyboardLayoutManager();

        // RecordingAnimationController: drive BorderGlow + PulseLayout from
        // state.recording transitions. Spec 2 §11.5 keeps the animation
        // outside the pure-resolver model — the controller is forwarded
        // from ImeViewBackend.render. animationsEnabled is read live
        // from Pref.Animations so a settings flip is reflected on the
        // next state emit.
        float displayDensity = recordButton.getResources().getDisplayMetrics().density;
        net.devemperor.dictate.widget.RecordingAnimation recordingAnimationForBackend =
            new net.devemperor.dictate.widget.BorderGlowAnimation(
                DictatePrefsKt.get(sp, Pref.AccentColor.INSTANCE),
                androidx.appcompat.content.res.AppCompatResources.getDrawable(
                    context, R.drawable.ic_baseline_send_20),
                new net.devemperor.dictate.widget.AmplitudeVisualizerDrawable.BarCountMode.Fixed(30),
                0.35f,
                displayDensity
            );
        recordingAnimationForBackend.prepare(recordButton);
        kotlin.jvm.functions.Function0<Boolean> animationsEnabledLambda =
            () -> DictatePrefsKt.get(sp, Pref.Animations.INSTANCE);
        RecordingAnimationController recordingAnimationCtrlForBackend =
            new RecordingAnimationController(
                recordingAnimationForBackend,
                recordPulseLayout,
                animationsEnabledLambda
            );

        kotlin.jvm.functions.Function0<kotlin.Unit> vibrateLambda = () -> {
            vibrate();
            return kotlin.Unit.INSTANCE;
        };
        imeViewBackend = new ImeViewBackend(
            new RealMotionSurface(motionLayout),
            buttonViews,
            context,
            pipelineBinder.getModuleServices(),
            recordingAnimationCtrlForBackend,
            /* staticHandlerInstaller */ null,
            vibrateLambda
        );

        try {
            keyboardLayoutManager.attachBackend(imeViewBackend);
        } catch (Throwable t) {
            Log.w("DictateIME", "KeyboardLayoutManager.attachBackend failed", t);
            imeViewBackend = null;
        }

        // B5 F-2 — (re)start the onboarding info-bar observer now that
        // both the binder and the inflated info-bar view exist. This is
        // the single consolidation point (called from both
        // onCreateInputView and onServiceConnected), so the observer is
        // wired regardless of the bind↔inflate race. stop() the prior
        // observer first so a view-recreate doesn't leak the old
        // collector scope.
        if (overlayOnboardingObserver != null) {
            overlayOnboardingObserver.stop();
        }
        if (overlayPermissionInfobar != null) {
            overlayOnboardingObserver = new OverlayOnboardingObserver(
                pipelineBinder.getState(),
                pending -> {
                    if (overlayPermissionInfobar != null) {
                        overlayPermissionInfobar.setVisibility(pending ? View.VISIBLE : View.GONE);
                    }
                });
            overlayOnboardingObserver.start();
        }
    }

    // method is called if the user closed the keyboard
    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);

        // Hide QWERTZ keyboard when the input view is finishing (app switch, background, etc.)
        hideQwertzKeyboard();

        // B5 F-1: the three legacy states (recording-pause /
        // pipeline-continue / idle-cleanup) used to early-`return`.
        // They are now restructured into an if/else-if/else with a
        // single tail so the OnImeViewHidden FSM dispatch below fires
        // on ALL paths. This is load-bearing: T3/T4 (KEYBOARD/WIDGET →
        // HOVER) only matter when recording or the pipeline is in
        // flight — i.e. exactly the State (A)/(B) paths that used to
        // `return` early. Missing the dispatch there would make HOVER
        // unreachable in the *primary* use-case (dictation continues
        // after the user switches the keyboard away). See ADR-0005
        // Decision-History 2026-05-15 + research §6.1.
        if (recordingStateController.getState().isRecordingOrPaused()
                || recordingStateController.getState() instanceof RecordingState.Preparing) {
            // State (A): Recording is active or paused -> delegate to controller (pause + timeout)
            recordingStateController.onKeyboardHidden();
            stateManager.setContentArea(ContentArea.MAIN_BUTTONS);
        } else if (pipelineOrchestrator != null && pipelineOrchestrator.isRunning()) {
            // State (B): API request is running -> let it continue, just hide content panels
            stateManager.setContentArea(ContentArea.MAIN_BUTTONS);
        } else {
            // State (C): Idle -> full cleanup
            if (pipelineOrchestrator != null) {
                pipelineOrchestrator.cancel();
            }
            pendingLivePromptChain = false;
            // Note: PipelineConfig is owned by uiController; stopPipeline() nulls it below.

            bluetoothScoManager.unregisterReceiver();

            infoBarController.dismiss();
            stateManager.setContentArea(ContentArea.MAIN_BUTTONS);
            stateManager.refresh();
            uiController.stopPipeline();
            livePrompt = false;
            updatePromptButtonsEnabledState();
        }

        // B5 F-1 (T3/T4): IME view hidden. Dispatched on EVERY path
        // (including the recording-active / pipeline-running branches
        // above — the primary HOVER trigger). The legacy 3-state block
        // is unchanged in behaviour; this only adds the Triangle-FSM
        // boundary dispatch. The ImeViewBackend is intentionally NOT
        // detached here (research §C-5) — HOVER renders via the
        // service-owned OverlayBackend. Guarded on pipelineBinder !=
        // null (the established pre-bind pattern, mirrors line ~828).
        if (pipelineBinder != null) {
            pipelineBinder.dispatch(
                    net.devemperor.dictate.state.Action.ViewModeAction.OnImeViewHidden.INSTANCE);
        }
    }

    @Override
    public void onDestroy() {
        // C15 — Detach the ImeViewBackend so the KeyboardLayoutManager
        // drops its View references. Calling detach via the cached
        // manager (the binder may have already been nulled in
        // onServiceDisconnected) is safe — detach is idempotent.
        if (imeViewBackend != null && keyboardLayoutManager != null) {
            try {
                keyboardLayoutManager.detachBackend(imeViewBackend);
            } catch (Throwable t) {
                Log.w("DictateIME", "ImeViewBackend detach in onDestroy failed", t);
            }
        }
        imeViewBackend = null;
        keyboardLayoutManager = null;

        // B5 F-2 — cancel the onboarding info-bar collector scope so
        // the SupervisorJob does not outlive the service.
        if (overlayOnboardingObserver != null) {
            overlayOnboardingObserver.stop();
            overlayOnboardingObserver = null;
        }

        // Clean up long-lived objects
        if (mainHandler != null) {
            mainHandler.removeCallbacks(reloadPromptsRunnable);
        }
        if (recordingStateController != null) recordingStateController.onDestroy();
        // PipelineOrchestrator.shutdown() is now invoked by
        // DictatePipelineService.onDestroy (C8 IMPL-1 closure — the service
        // owns the orchestrator's lifetime, not the IME). Calling shutdown
        // from here would either (a) act on a null reference if the IME
        // never bound, or (b) prematurely cancel the executor while the
        // FGS is still alive. The IME just clears its local reference.
        pipelineOrchestrator = null;
        if (promptsInvalidationObserver != null && dictateDb != null) {
            dictateDb.getInvalidationTracker().removeObserver(promptsInvalidationObserver);
        }
        if (bluetoothScoManager != null) bluetoothScoManager.unregisterReceiver();
        // D-13: deregister the input-languages prefs listener. The Service
        // may be destroyed without a preceding view-recreate (the IME
        // process can be torn down by the OS while a view is still
        // attached), in which case cleanupOldControllers() is never called
        // and the listener would leak through the SharedPreferences.
        // Idempotent with cleanupOldControllers() (both null the field).
        if (inputLanguagesListener != null && sp != null) {
            sp.unregisterOnSharedPreferenceChangeListener(inputLanguagesListener);
            inputLanguagesListener = null;
        }
        // Block 2: idempotent with cleanupOldControllers — when the IME is
        // torn down without a preceding view-recreate the listener still needs
        // to be detached.
        if (audioFocusListener != null && sp != null) {
            sp.unregisterOnSharedPreferenceChangeListener(audioFocusListener);
            audioFocusListener = null;
        }

        // Block 2 — Spec 1 §11.3.1: unbind the pipeline service so the
        // bind-counter drops. The service itself decides whether to
        // stopSelf via state.isAllTerminal (Block 1b); IME-side only
        // releases its connection. Safe to call even if onServiceConnected
        // never fired — bindService was issued in onCreateInputView and
        // the connection object owns its own state.
        if (pipelineServiceBindAttempted) {
            try {
                unbindService(pipelineConnection);
            } catch (IllegalArgumentException ignored) {
                // connection was not registered (e.g. bind failed); no-op
            }
            pipelineBinder = null;
            pipelineServiceBindAttempted = false;
        }
        super.onDestroy();
    }

    // ===== View-recreation helpers (called from onCreateInputView) =====

    /**
     * Cleans up old view-dependent controllers before creating new ones.
     * Stops orphaned timers, removes InvalidationTracker observer, de-registers BT receiver.
     *
     * IMPORTANT: Does NOT call stopPipeline() — that has side-effects
     * (mode reset, state change callbacks on old controllers).
     */
    private void cleanupOldControllers() {
        // Stop only the elapsed timer — no mode reset, no side-effects
        if (uiController != null) {
            // Capture the current auto-enter value so the upcoming fresh controller
            // (created in onCreateInputView) can re-adopt it in restoreUiState() — otherwise
            // a user's in-pipeline toggle would silently revert to the pref default on rotation.
            KeyboardUiController.AutoEnterConfig cfg = uiController.getAutoEnterConfig();
            restoreAutoEnter = (cfg != null) ? cfg.getAutoEnterActive() : null;

            // W1: Capture the active ReprocessStaging so we can re-enter it on
            // the fresh controller. Only the data matters — the state itself
            // is owned by the old controller and gets discarded.
            PipelineUiState oldState = uiController.getState();
            if (oldState instanceof PipelineUiState.ReprocessStaging) {
                restoreReprocessStaging = (PipelineUiState.ReprocessStaging) oldState;
            } else {
                restoreReprocessStaging = null;
            }

            // Phase 1 cross-phase: detach Service-side pipeline observer so the
            // CopyOnWriteArrayList in the soon-to-be-discarded controller does
            // not retain a reference. The new uiController will get a fresh
            // servicePipelineCallback in onCreateInputView.
            if (servicePipelineCallback != null) {
                uiController.removeCallback(servicePipelineCallback);
                servicePipelineCallback = null;
            }

            uiController.stopActiveTimer();
        }
        // D-13: deregister the input-languages prefs listener bound to the
        // old view. The fresh onCreateInputView re-registers a new one.
        // (No legacy controller to dispose — the resolver is stateless and
        // the orchestrator state survives the view-recreate.)
        if (inputLanguagesListener != null) {
            sp.unregisterOnSharedPreferenceChangeListener(inputLanguagesListener);
            inputLanguagesListener = null;
        }
        // Block 2: drop the audio-focus prefs listener bound to the soon-to-be
        // discarded MainButtonsController; the fresh controller in the upcoming
        // onCreateInputView re-registers a new listener.
        if (audioFocusListener != null) {
            sp.unregisterOnSharedPreferenceChangeListener(audioFocusListener);
            audioFocusListener = null;
        }
        // C15 — detach the previous ImeViewBackend before the upcoming
        // re-inflate. The backend holds direct View references that
        // become invalid the moment LayoutInflater produces a new tree.
        // Detach is idempotent — calling on a stale or already-detached
        // backend is safe.
        if (imeViewBackend != null && keyboardLayoutManager != null) {
            try {
                keyboardLayoutManager.detachBackend(imeViewBackend);
            } catch (Throwable t) {
                Log.w("DictateIME", "ImeViewBackend detach during cleanupOldControllers failed", t);
            }
        }
        imeViewBackend = null;
        // Remove old InvalidationTracker observer (will be re-added in setupPromptsAdapter)
        if (promptsInvalidationObserver != null && dictateDb != null) {
            dictateDb.getInvalidationTracker().removeObserver(promptsInvalidationObserver);
        }
        // De-register BT receiver (will be re-registered in rewireCallbacks)
        if (bluetoothScoManager != null) {
            bluetoothScoManager.unregisterReceiver();
        }
    }

    /**
     * Connects long-lived objects (from onCreate) to the newly created UI controllers.
     * Must be called AFTER view-dependent controllers are created, BEFORE restoreUiState().
     */
    private void rewireCallbacks() {
        // 1. RecordingStateController → new UI controllers
        //    The closures reference Service fields (recordingUiController etc.)
        //    which now point to the NEW controllers.
        recordingStateController.setCallback(new RecordingStateController.Callback() {
            @Override
            public void onStateChanged(RecordingState oldState, RecordingState newState) {
                mainHandler.post(() -> {
                    recordingUiController.onStateChanged(oldState, newState);
                    updatePromptButtonsEnabledState();
                });
            }

            @Override
            public void onAmplitudeUpdate(float level) {
                mainHandler.post(() -> recordingUiController.onAmplitudeUpdate(level));
            }

            @Override
            public void onTimerTick(long elapsedMs) {
                mainHandler.post(() -> recordingUiController.onTimerTick(elapsedMs));
            }

            @Override
            public void onRecordingCompleted(File file) {
                // Dead on the new path: C7 deleted the legacy
                // recordingStateController record-button branches, so the
                // controller is never started and this completion callback
                // never fires (the orchestrator's RecordingModule owns
                // recording end-to-end). Kept compiling + routed through
                // the same orchestrator entry-point for behavioural
                // equivalence should any future legacy caller reach it
                // (the legacy-controller retire is Theme-C/C3 scope —
                // C5-IMPL-2, not this mid-chunk-triage wave).
                mainHandler.post(() ->
                        transcribeImportedAudioFileViaOrchestrator(file));
            }

            @Override
            public void onRecordingError(String errorKey) {
                mainHandler.post(() -> showInfo(errorKey));
            }

            @Override
            public void onKeepScreenAwakeChanged(boolean keepAwake) {
                updateKeepScreenAwake(keepAwake);
            }

            @Override
            public void onAutoStopTimeout() {
                mainHandler.post(() -> {
                    livePrompt = false;
                    updatePromptButtonsEnabledState();
                });
            }
        });

        // 2. Re-register BT receiver (was de-registered in cleanupOldControllers)
        bluetoothScoManager.registerReceiver();

        // 3. RecordingManager + BluetoothScoManager need NO re-wiring:
        //    - RecordingManager.callback = recordingStateController (long-lived, unchanged)
        //    - BluetoothScoManager.callback = recordingStateController (long-lived, unchanged)
        //    - recordingStateController.managers remain set (setManagers was in onCreate)
    }

    /**
     * Synchronizes current state onto the fresh UI after view recreation.
     * Must be called AFTER rewireCallbacks() — otherwise state changes go nowhere.
     */
    private void restoreUiState() {
        // 1. Recording state → UI
        RecordingState currentState = recordingStateController.getState();
        if (!(currentState instanceof RecordingState.Idle)) {
            // Fake a state transition Idle → currentState so RecordingUiController
            // builds the correct UI (button text, animation, visibility)
            recordingUiController.onStateChanged(RecordingState.Idle.INSTANCE, currentState);
            updatePromptButtonsEnabledState();
            updateKeepScreenAwake(currentState.isRecordingOrPaused());
        }

        // 2. Pipeline state → UI
        if (restoreReprocessStaging != null) {
            // W1: The user was editing the reprocess queue when the view was
            // recreated (rotation / theme change). Re-enter staging on the
            // fresh controller so the record-button label, the editable
            // prompt queue, and the selected language all survive.
            PipelineUiState.ReprocessStaging staging = restoreReprocessStaging;
            restoreReprocessStaging = null;
            uiController.enterReprocessStaging(
                staging.getTargetSessionId(),
                staging.getAudioDurationSeconds(),
                staging.getEditableQueue(),
                staging.getSelectedLanguage()
            );
        } else if (pipelineOrchestrator != null && pipelineOrchestrator.isRunning()) {
            int total = pipelineOrchestrator.getTotalSteps();
            int completedSoFar = pipelineOrchestrator.getCompletedSteps();
            String stepName = pipelineOrchestrator.getCurrentStepName();

            // Restore the user's in-pipeline auto-enter toggle if we have one from the
            // about-to-be-discarded old controller; otherwise fall back to the pref default.
            // NOTE: hasFailure is intentionally NOT restored — the orchestrator doesn't
            // track past-failure state, so a rotated pipeline that had already failed loses
            // the red button color until the next failStep. Accepted per refactor plan O-1.
            boolean autoEnter = restoreAutoEnter != null
                ? restoreAutoEnter
                : DictatePrefsKt.get(sp, Pref.AutoEnter.INSTANCE);
            restoreAutoEnter = null;
            uiController.startPipeline(
                total > 0 ? total : 1,
                new KeyboardUiController.AutoEnterConfig(autoEnter),
                completedSoFar);

            // Show the currently running step
            uiController.addRunningStep(stepName != null ? stepName : "\u2026");
        } else {
            // No pipeline active — clear any stale bridge value so it can't leak into a later run.
            restoreAutoEnter = null;
        }

        // 3. Small mode from preferences
        stateManager.setSmallMode(DictatePrefsKt.get(sp, Pref.SmallMode.INSTANCE));
    }

    /**
     * Creates the prompts adapter, sets it on the RecyclerView, and registers
     * the InvalidationTracker observer for auto-reload on DB changes.
     */
    private void setupPromptsAdapter(Context context) {
        promptsAdapter = new PromptsKeyboardAdapter(sp, new ArrayList<>(), new PromptsKeyboardAdapter.AdapterCallback() {
            @Override
            public void onItemClicked(Integer position) {
                vibrate();
                PromptEntity model = promptsAdapter.getItem(position);

                // ReprocessStaging: prompt clicks toggle into/out of the editable queue.
                PipelineUiState currentState = uiController != null ? uiController.getState() : null;
                if (currentState instanceof PipelineUiState.ReprocessStaging) {
                    handleReprocessPromptToggle(model, (PipelineUiState.ReprocessStaging) currentState);
                    return;
                }

                if (model.getId() == -1) {  // instant prompt clicked
                    livePrompt = true;
                    if (ContextCompat.checkSelfPermission(DictateInputMethodService.this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        openSettingsActivity();
                    } else if (isEffectiveRecordingIdle()) {
                        startRecording();
                    } else if (isEffectiveRecordingActiveOrPaused()) {
                        stopRecording();
                    }
                } else if (model.getId() == -3) {  // select all clicked
                    handleSelectAllToggle();
                } else if (model.getId() == -4) {  // clear queue clicked
                    vibrate();
                    promptQueueManager.clear();
                } else if (model.getId() == -2) {  // add prompt clicked
                    Intent intent = new Intent(DictateInputMethodService.this, PromptsOverviewActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } else {
                    if (isEffectiveRecordingInFlight() && !livePrompt) {
                        promptQueueManager.togglePrompt(model.getId());
                        return;
                    }
                    InputConnection currentConnection = getCurrentInputConnection();
                    if (model.getRequiresSelection()) {
                        if (currentConnection == null) {
                            return;
                        }
                        ExtractedText extractedText = currentConnection.getExtractedText(new ExtractedTextRequest(), 0);
                        if (extractedText == null || extractedText.text == null || extractedText.text.length() == 0) {
                            return;
                        }
                        CharSequence selectedText = currentConnection.getSelectedText(0);
                        if (selectedText == null || selectedText.length() == 0) {
                            currentConnection.performContextMenuAction(android.R.id.selectAll);
                            selectedText = currentConnection.getSelectedText(0);
                            if (selectedText == null || selectedText.length() == 0) {
                                return;
                            }
                        }
                    }
                    runStandalonePromptViaOrchestrator(model);
                }
            }

            @Override
            public void onItemLongClicked(Integer position) {
                PromptEntity longClickModel = promptsAdapter.getItem(position);
                if (longClickModel.getId() >= 0) {
                    vibrate();
                    Intent intent = new Intent(DictateInputMethodService.this, PromptEditActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.putExtra("net.devemperor.dictate.prompt_edit_activity_id", longClickModel.getId());
                    startActivity(intent);
                }
            }
        });
        promptsRv.setAdapter(promptsAdapter);
        // Phase 2 §2.4: chip-click listener is wired ONCE here (not per mode).
        // Click opens the grouped PopupMenu — curated languages on top, all
        // others below, "Verwalten…" action at the end.
        promptsAdapter.setLanguageChipListener(this::showLanguagePicker);

        // Register InvalidationTracker to auto-reload prompts when DB changes (debounced 200ms)
        promptsInvalidationObserver = new InvalidationTracker.Observer("prompts") {
            @Override
            public void onInvalidated(@NonNull Set<String> tables) {
                mainHandler.removeCallbacks(reloadPromptsRunnable);
                mainHandler.postDelayed(reloadPromptsRunnable, 200);
            }
        };
        dictateDb.getInvalidationTracker().addObserver(promptsInvalidationObserver);
    }

    /**
     * Phase 2 §2.7b: Syncs the prompts-adapter's queued-prompt order to the
     * pipeline state. The language chip is now permanently visible (its
     * label is refreshed via {@link #refreshLanguageChip()} on every
     * effective-language change), so this method has only a single
     * responsibility — keep the editable queue or the regular queue in
     * the adapter, depending on the active state.
     */
    private void syncQueueOrder(PipelineUiState newState) {
        if (promptsAdapter == null) return;
        if (newState instanceof PipelineUiState.ReprocessStaging) {
            PipelineUiState.ReprocessStaging s = (PipelineUiState.ReprocessStaging) newState;
            promptsAdapter.setQueuedPromptOrder(s.getEditableQueue());
        } else if (promptQueueManager != null) {
            promptsAdapter.setQueuedPromptOrder(promptQueueManager.getQueuedIds());
        }
    }

    /**
     * The IME-side effective language: the transient ReprocessStaging
     * override (when in staging) takes precedence over the permanent
     * pref-resolved language, mirroring the deleted legacy
     * language-controller's effective-resolution semantics (D-13).
     *
     * <p>The ReprocessStaging override is still carried on the legacy
     * {@link PipelineUiState.ReprocessStaging#getSelectedLanguage()}
     * (owned by {@link KeyboardUiController} until C10 retires it); the
     * permanent value comes from the static
     * {@link net.devemperor.dictate.preferences.LanguageResolver}. Both
     * are framework-light reads — safe to call on every render-tick and
     * before the orchestrator binds (R-3 boot-before-bind: the permanent
     * read returns the persisted value, never a stale cache or NPE).</p>
     */
    private String resolveEffectiveLanguage() {
        if (uiController != null
                && uiController.getState() instanceof PipelineUiState.ReprocessStaging) {
            String override =
                    ((PipelineUiState.ReprocessStaging) uiController.getState())
                            .getSelectedLanguage();
            if (override != null && !override.trim().isEmpty()) return override;
        }
        return net.devemperor.dictate.preferences.LanguageResolver.INSTANCE
                .effectiveLanguage(sp);
    }

    /**
     * Resolve the **permanent** effective language from prefs and push it
     * into the bound orchestrator via the payload-bearing
     * {@code LanguageAction.RefreshFromPref} (Pre-Dispatch-Resolution,
     * Spec 1 §4.11), then refresh the chip + record-button label.
     *
     * <p>R-3 boot-before-bind: the dispatch is guarded by
     * {@code pipelineBinder != null} (the parent plan's guard
     * discipline). When unbound, the UI still refreshes from the resolver
     * directly; the next {@code RefreshFromPref} after bind reconciles
     * {@code state.language.effective}. This is dispatched on every
     * permanent-language change (initial render, picker write, external
     * Settings write) so the RenderBackend F-15 read
     * ({@code state.language.effective}) and the transcription-config
     * snapshot stay in lock-step with the prefs.</p>
     */
    private void pushPermanentLanguageToOrchestrator() {
        String code = net.devemperor.dictate.preferences.LanguageResolver.INSTANCE
                .effectiveLanguage(sp);
        if (pipelineBinder != null) {
            try {
                pipelineBinder.dispatch(
                        new net.devemperor.dictate.state.Action.LanguageAction.RefreshFromPref(code));
            } catch (Throwable t) {
                Log.w("DictateIME", "RefreshFromPref dispatch failed", t);
            }
        }
        refreshLanguageChip();
        if (mainButtonsController != null) {
            mainButtonsController.updateRecordButtonText(getDictateButtonText());
        }
    }

    /**
     * Phase 2 §2.1: refreshes the always-visible language chip's label
     * from the current effective language. Called on initial render, on
     * every permanent-language change (via
     * {@link #pushPermanentLanguageToOrchestrator()}), and any place the
     * service wants the chip in lock-step with the resolved language.
     */
    private void refreshLanguageChip() {
        if (promptsAdapter == null) return;
        String code = resolveEffectiveLanguage();
        // The chip uses the compact 2-letter form (e.g. "DE", "EN") so it
        // matches the size and styling of regular prompt pills. The full
        // language name is shown when the user opens the popup picker.
        String label = LanguageLabelResolver.INSTANCE.resolveShortLabel(code);
        promptsAdapter.setLanguageChipVisible(true, label);
    }

    /**
     * Toggles a prompt into/out of the editable queue while in ReprocessStaging.
     * Skips sentinel/control items (id < 0).
     *
     * W6: Single source of truth — we write the new queue onto
     * {@link PipelineUiState.ReprocessStaging} via
     * {@code uiController.updateReprocessQueue}, and the state-change
     * callback routes back through {@link #syncQueueOrder}
     * which updates the adapter. No direct adapter write here.
     */
    private void handleReprocessPromptToggle(PromptEntity model, PipelineUiState.ReprocessStaging staging) {
        if (model.getId() < 0) return;  // sentinel items have no meaning here
        List<Integer> queue = new ArrayList<>(staging.getEditableQueue());
        int promptId = model.getId();
        if (queue.contains(promptId)) {
            queue.removeIf(id -> id == promptId);
        } else {
            queue.add(promptId);
        }
        uiController.updateReprocessQueue(queue);
    }

    /**
     * Phase 2 §2.2: opens a grouped PopupMenu listing all transcription
     * languages. The chip is always-visible, so this method runs in both
     * the idle and the ReprocessStaging modes; {@link #setLanguageFromPicker}
     * decides whether the click results in a permanent write (with auto-
     * curation) or a transient ReprocessStaging override (D-13).
     *
     * <p>Layout:
     * <ol>
     *   <li>Curated languages (top, label-sorted)</li>
     *   <li>Visual divider — native group divider on API 28+, Unicode
     *       label fallback on API 26-27</li>
     *   <li>All other languages (label-sorted)</li>
     *   <li>"⚙ Sprachen verwalten…" action that opens settings</li>
     * </ol>
     *
     * <p>PopupMenu is used instead of a Dialog because IMEs don't own a
     * window token compatible with TYPE_APPLICATION_ATTACHED_DIALOG on
     * all OEM skins (Samsung One UI throws BadTokenException). PopupMenu
     * anchors its window on the passed view and therefore inherits the
     * IME's window context correctly.</p>
     */
    private void showLanguagePicker(View anchor) {
        // Quality-Gate N-6: curatedLanguages() returns the list already
        // label-sorted and free of duplicates / unknown codes (plugin
        // sanitize contract). Just compute "others" for the lower block.
        List<String> curatedOrdered =
                net.devemperor.dictate.preferences.LanguageResolver.INSTANCE
                        .curatedLanguages(sp);
        List<String> othersOrdered = LanguageLabelResolver.INSTANCE.othersThan(curatedOrdered);

        android.widget.PopupMenu popup = new android.widget.PopupMenu(
                new ContextThemeWrapper(this, R.style.Theme_Dictate), anchor);
        Menu menu = popup.getMenu();

        // --- Upper block: curated languages ---
        int order = 0;
        for (String code : curatedOrdered) {
            String label = LanguageLabelResolver.INSTANCE.resolveLabel(code);
            menu.add(GROUP_CURATED, stableIdForCode(code), order++, label);
        }

        // --- Visual divider ---
        // Edge-case (Plan §2.2): when all 62 supported languages are curated
        // there is no "Others" group, so a divider would render an empty
        // section. Guard both branches against the empty-others case.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (!othersOrdered.isEmpty()) {
                // Native horizontal divider between groups (API 28+ / Android 9).
                menu.setGroupDividerEnabled(true);
            }
        } else if (!othersOrdered.isEmpty()) {
            // API 26/27 fallback — disabled label item with Unicode dashes.
            MenuItem sep = menu.add(GROUP_OTHERS, Menu.NONE, order++,
                    getString(R.string.dictate_language_other_separator));
            sep.setEnabled(false);
        }

        // --- Lower block: all other languages ---
        for (String code : othersOrdered) {
            String label = LanguageLabelResolver.INSTANCE.resolveLabel(code);
            menu.add(GROUP_OTHERS, stableIdForCode(code), order++, label);
        }

        // --- Action at the end: open settings with focus on the curation list ---
        menu.add(GROUP_ACTION, MENU_ID_MANAGE, order++,
                getString(R.string.dictate_language_manage));

        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == MENU_ID_MANAGE) {
                openLanguageSettings();
                return true;
            }
            String code = codeForStableId(id);
            if (code != null) {
                setLanguageFromPicker(code);
                return true;
            }
            return false;
        });
        popup.show();
    }

    /**
     * Picker click handler — routes the chosen language to the right
     * target based on the pipeline state, replicating the deleted legacy
     * language-controller's set-language routing (D-13):
     *
     * <ul>
     *   <li><b>ReprocessStaging</b> → a <i>transient</i> override. Written
     *       to the legacy {@link PipelineUiState.ReprocessStaging}
     *       (via {@link KeyboardUiController#updateReprocessLanguage(String)},
     *       still the carrier until C10) <i>and</i> dispatched as
     *       {@code LanguageAction.SetOverride(code)} so the orchestrator's
     *       {@code LanguageState.override} (the new SoT) tracks it. The
     *       state-change callback refreshes the chip; never persisted.</li>
     *   <li><b>any other state</b> → a <i>permanent</i> write with
     *       auto-curation via the static
     *       {@link net.devemperor.dictate.preferences.LanguageResolver},
     *       followed by {@link #pushPermanentLanguageToOrchestrator()}
     *       (refreshes chip + label + dispatches RefreshFromPref).</li>
     * </ul>
     */
    private void setLanguageFromPicker(String code) {
        if (uiController != null
                && uiController.getState() instanceof PipelineUiState.ReprocessStaging) {
            uiController.updateReprocessLanguage(code);
            if (pipelineBinder != null) {
                try {
                    pipelineBinder.dispatch(
                            new net.devemperor.dictate.state.Action.LanguageAction.SetOverride(code));
                } catch (Throwable t) {
                    Log.w("DictateIME", "SetOverride dispatch failed", t);
                }
            }
            // The KeyboardUiController state-change callback path refreshes
            // the chip; nothing persisted for a transient override.
        } else {
            net.devemperor.dictate.preferences.LanguageResolver.INSTANCE
                    .setLanguage(sp, code);
            pushPermanentLanguageToOrchestrator();
        }
    }

    /**
     * Phase 2 §2.2: opens the settings activity with a request to scroll
     * to the input-languages preference so the user can curate the list.
     */
    private void openLanguageSettings() {
        Intent intent = new Intent(this, DictateSettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(DictateSettingsActivity.EXTRA_SCROLL_TO,
                "net.devemperor.dictate.input_languages");
        startActivity(intent);
    }

    // Phase 2 §2.2: stable PopupMenu IDs derived from the resolver's
    // resource-array index of each ISO code. Quality-Gate N-7 — uses
    // a constant offset so a code's index never collides with the
    // sentinel MENU_ID_MANAGE (-1) or Menu.NONE.
    private static final int MENU_ID_MANAGE = -1;
    // Larger than the count of supported language codes (currently 58 in
    // R.array.dictate_input_languages_values) to prevent stable-ID
    // collisions with MENU_ID_MANAGE (-1) or framework constants like
    // Menu.NONE (0). If the language list ever grows beyond ~95 entries,
    // raise this offset accordingly. A static_init compile-time guard
    // would be cleaner, but LanguageLabelResolver is not yet initialised
    // when the Service's static block runs, so we rely on this comment
    // and the Quality-Gate review as the contract.
    private static final int MENU_ID_OFFSET = 100;

    private static int stableIdForCode(String code) {
        int idx = LanguageLabelResolver.INSTANCE.indexOfCode(code);
        return idx >= 0 ? idx + MENU_ID_OFFSET : Menu.NONE;
    }

    private static String codeForStableId(int id) {
        if (id < MENU_ID_OFFSET) return null;
        int idx = id - MENU_ID_OFFSET;
        List<String> all = LanguageLabelResolver.INSTANCE.allCodes();
        return idx < all.size() ? all.get(idx) : null;
    }

    // Phase 2 §2.2: Menu group-IDs for the grouped PopupMenu.
    private static final int GROUP_CURATED = 1;
    private static final int GROUP_OTHERS = 2;
    private static final int GROUP_ACTION = 3;

    // method is called if the keyboard appears again
    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        updateEnterButtonIcon(info);
        bluetoothScoManager.registerReceiver();

        // If recording was paused (by onFinishInputView), cancel timeout and restore UI
        recordingStateController.onKeyboardShown();

        // B5 F-3 + F-1 — IME-activation wiring (the Triangle-FSM
        // production trigger surface, ADR-0005 Decision-History
        // 2026-05-15 / research §3+§5). Grouped here so the activation
        // wiring stays in one place. Guarded on pipelineBinder != null
        // (pre-bind no-op; the observer's cold-start init() covers the
        // initial hasPermission).
        if (pipelineBinder != null) {
            // F-3: pick up an overlay permission the user toggled in
            // System Settings while the IME view was gone (Spec 3
            // §5.0). MUST run BEFORE the OnImeViewShown dispatch so the
            // FSM sees the fresh hasPermission axis (closes the
            // grant-pickup latency + the revoke busy-retry loop).
            pipelineBinder.getOverlayPermissionObserver().refresh();
            // F-1 (T5/T6): IME view shown. Unconditional — the reducer
            // no-ops when computeViewMode == current (idempotent). The
            // `restarting` flag is intentionally IGNORED: suppressing
            // on restart would break T6 (rotation while in HOVER must
            // recompute to WIDGET/KEYBOARD when the view returns). See
            // research §3 view-recreation handling.
            pipelineBinder.dispatch(
                    net.devemperor.dictate.state.Action.ViewModeAction.OnImeViewShown.INSTANCE);
        }

        // Determine if we are truly idle (no recording, no pipeline running).
        // When not idle, skip UI resets that would overwrite state restored by restoreUiState().
        boolean isIdle = recordingStateController.getState() instanceof RecordingState.Idle
                && (pipelineOrchestrator == null || !pipelineOrchestrator.isRunning());

        if (DictatePrefsKt.get(sp, Pref.RewordingEnabled.INSTANCE)) {
            if (isIdle) {
                InputConnection inputConnection = getCurrentInputConnection();
                boolean hasSelection = inputConnection != null && inputConnection.getSelectedText(0) != null;
                promptsAdapter.setDisableNonSelectionPrompts(disableNonSelectionPrompts);
                promptsAdapter.setSelectAllActive(hasSelection);
            }

            // Reload prompts from DB (async — adapter is updated via reloadPrompts())
            reloadPrompts();
        }
        // promptsCl visibility is handled by stateManager.refresh() via applySmallMode below

        if (shouldAutomaticallyShowQwertzNumbers(info)) {
            qwertzController.setLayout(QwertzKeyboardLayout.NUMBERS);
            showQwertzKeyboard();
        } else {
            hideQwertzKeyboard();
        }

        if (isIdle) {
            // Block-1a Quick-Win: resend visibility consolidated into the
            // isResendVisible helper (KeyboardVisibilityPredicates).
            // Recording is guaranteed Idle on this branch (isIdle gate above);
            // pipeline is also Idle on a fresh onStartInputView so the helper
            // returns true iff the cached audio still exists AND
            // Pref.ResendButton is on — same expression that previously lived
            // inline.
            resendButton.setVisibility(KeyboardVisibilityPredicates.resolveResendVisibility(
                    new File(getCacheDir(), DictatePrefsKt.get(sp, Pref.LastFileName.INSTANCE)).exists(),
                    DictatePrefsKt.get(sp, Pref.ResendButton.INSTANCE),
                    RecordingState.Idle.INSTANCE,
                    PipelineUiState.Idle.INSTANCE));

            // get the currently selected input language
            recordButton.setText(getDictateButtonText());
        }

        // Block 3b: audio-focus is read on-demand from the pref by the
        // controller's startRecording() path — no service-side caching.

        // fill all overlay characters
        int accentColor = DictatePrefsKt.get(sp, Pref.AccentColor.INSTANCE);
        String charactersString = DictatePrefsKt.get(sp, Pref.OverlayCharacters.INSTANCE);
        mainButtonsController.updateOverlayCharacters(charactersString, accentColor);

        // update theme
        String theme = DictatePrefsKt.get(sp, Pref.Theme.INSTANCE);
        int keyboardBackgroundColor;
        if ("dark".equals(theme) || ("system".equals(theme) && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)) {
            keyboardBackgroundColor = getResources().getColor(R.color.dictate_keyboard_background_dark, getTheme());
        } else {
            keyboardBackgroundColor = getResources().getColor(R.color.dictate_keyboard_background_light, getTheme());
        }
        dictateKeyboardView.setBackgroundColor(keyboardBackgroundColor);
        emojiPickerCl.setBackgroundColor(keyboardBackgroundColor);
        qwertzContainer.setBackgroundColor(keyboardBackgroundColor);

        TextView[] textColorViews = { infoTv, emojiPickerTitleTv };
        for (TextView tv : textColorViews) tv.setTextColor(accentColor);
        mainButtonsController.applyTheme(accentColor);
        recordingUiController.updateAnimationColor(accentColor);
        qwertzController.applyColors(accentColor, DictateUtils.darkenColor(accentColor, 0.18f), DictateUtils.darkenColor(accentColor, 0.35f));

        // show infos for updates, ratings or donations (DB query on background thread)
        if (DictatePrefsKt.get(sp, Pref.LastVersionCode.INSTANCE) < BuildConfig.VERSION_CODE) {
            showInfo("update");
        } else {
            dbExecutor.execute(() -> {
                Long totalAudioTimeOrNull = usageDao.getTotalAudioTime();
                long totalAudioTime = totalAudioTimeOrNull != null ? totalAudioTimeOrNull : 0;
                mainHandler.post(() -> {
                    if (totalAudioTime > 180 && totalAudioTime <= 600 && !DictatePrefsKt.get(sp, Pref.FlagHasRated.INSTANCE)) {
                        showInfo("rate");
                    } else if (totalAudioTime > 600 && !DictatePrefsKt.get(sp, Pref.FlagHasDonated.INSTANCE)) {
                        showInfo("donate");
                    }
                });
            });
        }

        // Sync animations preference to QWERTZ keyboard
        qwertzKeyboardView.getKeyPressAnimator().setAnimationsEnabled(
                DictatePrefsKt.get(sp, Pref.Animations.INSTANCE));

        // Sync small mode from prefs and apply visibility + animation
        stateManager.setSmallMode(DictatePrefsKt.get(sp, Pref.SmallMode.INSTANCE));
        mainButtonsController.animateSmallModeToggle(false);

        // start audio file transcription if user selected an audio file
        if (!DictatePrefsKt.get(sp, Pref.TranscriptionAudioFile.INSTANCE).isEmpty()) {
            // B3-VAL-W1 F-5: read from `cacheDir/audio/` (the
            // CacheDirAudioFileFactory's scope). DictateSettingsActivity
            // writes the imported file there; the orphan-cleanup pass
            // sees it as a referenced path via SessionEntity.audio_file_path
            // once transcription starts, and the pre-refactor leak
            // (file in cacheDir root falling outside the factory's
            // cleanupOrphans scope) is closed.
            // D-14 (C9-C2): the imported file is a scratch handle local to
            // this import flow — there is no orchestrator recording session
            // for an import, so it is not sourced from RecordingState. It
            // is threaded explicitly into the transcribe entry-point.
            File importedAudio = new File(new File(getCacheDir(), "audio"), DictatePrefsKt.get(sp, Pref.TranscriptionAudioFile.INSTANCE));
            DictatePrefsKt.put(sp.edit(), Pref.LastFileName.INSTANCE, importedAudio.getName()).apply();

            sp.edit().remove(Pref.TranscriptionAudioFile.INSTANCE.getKey()).apply();
            transcribeImportedAudioFileViaOrchestrator(importedAudio);

        } else if (DictatePrefsKt.get(sp, Pref.InstantRecording.INSTANCE)) {
            recordButton.performClick();
        }
    }

    // method is called if user changed text selection
    @Override
    public void onUpdateSelection (int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd, int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);

        // refill all prompts
        if (sp != null && DictatePrefsKt.get(sp, Pref.RewordingEnabled.INSTANCE)) {
            updateSelectAllPromptState();
        }
    }

    private void vibrate() {
        if (vibrationEnabled) if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
        } else {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    private void toggleEmojiPicker() {
        if (stateManager.getContentArea() == ContentArea.EMOJI_PICKER) {
            hideEmojiPicker();
        } else {
            showEmojiPicker();
        }
    }

    private void showEmojiPicker() {
        stateManager.setContentArea(ContentArea.EMOJI_PICKER);
        emojiPickerCl.bringToFront();
    }

    private void hideEmojiPicker() {
        if (stateManager.getContentArea() == ContentArea.EMOJI_PICKER) {
            stateManager.setContentArea(ContentArea.MAIN_BUTTONS);
        }
    }

    private void handleSelectAllToggle() {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection == null) return;

        ExtractedText extractedText = inputConnection.getExtractedText(new ExtractedTextRequest(), 0);
        CharSequence selectedText = inputConnection.getSelectedText(0);

        if ((selectedText == null || selectedText.length() == 0)
                && extractedText != null && extractedText.text != null && extractedText.text.length() > 0) {
            inputConnection.performContextMenuAction(android.R.id.selectAll);
        } else {
            inputConnection.clearMetaKeyStates(0);
            if (extractedText == null || extractedText.text == null) {
                inputConnection.setSelection(0, 0);
            } else {
                int length = extractedText.text.length();
                inputConnection.setSelection(length, length);
            }
        }

        updateSelectAllPromptState();
    }

    private void updateSelectAllPromptState() {
        if (promptsAdapter == null) return;
        InputConnection inputConnection = getCurrentInputConnection();
        boolean hasSelection = inputConnection != null && inputConnection.getSelectedText(0) != null;
        promptsAdapter.setSelectAllActive(hasSelection);
    }

    private void toggleQwertzKeyboard() {
        if (qwertzContainer == null) return;
        if (stateManager.getContentArea() == ContentArea.QWERTZ) {
            hideQwertzKeyboard();
        } else {
            showQwertzKeyboard();
        }
    }

    private void showQwertzKeyboard() {
        if (qwertzContainer == null) return;
        stateManager.setContentArea(ContentArea.QWERTZ);
        qwertzContainer.bringToFront();
        qwertzController.checkAutoShiftAtCursor();
    }

    private void hideQwertzKeyboard() {
        if (qwertzContainer == null) return;
        if (stateManager.getContentArea() == ContentArea.QWERTZ) {
            stateManager.setContentArea(ContentArea.MAIN_BUTTONS);
        }
    }


    private boolean shouldAutomaticallyShowQwertzNumbers(EditorInfo info) {
        if (info == null) return false;
        int inputType = info.inputType;
        int inputClass = inputType & InputType.TYPE_MASK_CLASS;
        if (inputClass == InputType.TYPE_CLASS_NUMBER || inputClass == InputType.TYPE_CLASS_PHONE) {
            return true;
        }
        return inputClass == InputType.TYPE_CLASS_DATETIME;
    }

    private void performEnterAction() {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection == null) return;
        EditorInfo editorInfo = getCurrentInputEditorInfo();

        if (editorInfo == null) {
            inputConnection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            inputConnection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
            return;
        }

        int imeAction = editorInfo.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean noEnterAction = (editorInfo.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;

        if (noEnterAction) {
            inputConnection.commitText("\n", 1);
        } else {
            switch (imeAction) {
                case EditorInfo.IME_ACTION_GO:
                case EditorInfo.IME_ACTION_SEARCH:
                case EditorInfo.IME_ACTION_SEND:
                case EditorInfo.IME_ACTION_NEXT:
                case EditorInfo.IME_ACTION_DONE:
                    inputConnection.performEditorAction(imeAction);
                    break;
                default:
                    inputConnection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                    inputConnection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
                    break;
            }
        }
    }

    /**
     * Schedules {@link #performEnterAction()} with a delay after text commit.
     * The delay ensures terminal emulators (e.g. Termux → Claude Code) treat Enter
     * as a separate keystroke rather than part of the pasted text block.
     * For character-by-character mode, the delay is added after the last character's delay.
     */
    private void scheduleAutoEnter(String output) {
        if (mainHandler == null) {
            // No handler available — fall back to immediate (best effort)
            performEnterAction();
            return;
        }

        long baseDelay = DictatePrefsKt.get(sp, Pref.AutoEnterDelay.INSTANCE);
        if (!DictatePrefsKt.get(sp, Pref.InstantOutput.INSTANCE) && output.length() > 0) {
            // Character-by-character: add delay after the last character finishes
            int speed = DictatePrefsKt.get(sp, Pref.OutputSpeed.INSTANCE);
            long lastCharDelay = (long) ((output.length() - 1) * (20L / (speed / 5f)));
            baseDelay += lastCharDelay;
        }

        mainHandler.postDelayed(this::performEnterAction, baseDelay);
    }

    private void updateEnterButtonIcon(EditorInfo info) {
        if (info == null || enterButton == null) return;

        int imeAction = info.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean noEnterAction = (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;

        if (noEnterAction) {
            enterButton.setForeground(AppCompatResources.getDrawable(this, R.drawable.ic_baseline_subdirectory_arrow_left_24));
        } else {
            switch (imeAction) {
                case EditorInfo.IME_ACTION_GO:
                case EditorInfo.IME_ACTION_SEARCH:
                case EditorInfo.IME_ACTION_SEND:
                case EditorInfo.IME_ACTION_NEXT:
                    enterButton.setForeground(AppCompatResources.getDrawable(this, R.drawable.ic_baseline_send_20));
                    break;
                case EditorInfo.IME_ACTION_DONE:
                    enterButton.setForeground(AppCompatResources.getDrawable(this, R.drawable.ic_baseline_check_24));
                    break;
                default:
                    enterButton.setForeground(AppCompatResources.getDrawable(this, R.drawable.ic_baseline_subdirectory_arrow_left_24));
                    break;
            }
        }
    }

    private void openSettingsActivity() {
        Intent intent = new Intent(this, DictateSettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    /**
     * C5 — the authoritative {@link RecordingState} for the record-button
     * gating logic.
     *
     * <p>On the <b>new</b> path the orchestrator's
     * {@code DictateOrchestrator} owns the recording FSM (the legacy
     * {@code recordingStateController} is never started), so the record
     * button must consult {@code state.recording} to decide
     * start-vs-stop. On the <b>legacy</b> path (or before the binder is
     * up) the legacy controller is the source of truth, exactly as
     * pre-C5.</p>
     *
     * <p>Only the record-button <i>gating</i> call-sites are migrated to
     * this helper. The recording <i>animation</i> / amplitude / timer UI
     * sites still read {@code recordingStateController} directly — those
     * are driven by the legacy controller's callbacks and the
     * orchestrator-side recording UI is the RenderBackend's job
     * (Theme-C/C3), out of C5 scope. On the new path they simply stay
     * Idle (no legacy animation) which is cosmetic; the FGS notification
     * (AC-2) is the authoritative recording-active surface and is driven
     * by RecordingModule.</p>
     *
     * <p><b>Two distinct {@code RecordingState} types.</b> The legacy
     * controller uses {@code net.devemperor.dictate.core.RecordingState}
     * (sealed class: {@code Idle}/{@code Preparing}/{@code Active}/
     * {@code Paused}); the orchestrator uses the structurally-different
     * {@code net.devemperor.dictate.state.RecordingState}. They are not
     * assignment-compatible, so the helper exposes <i>boolean predicates</i>
     * (idle / active-or-paused) rather than a unified object — the
     * record-button gating only needs the predicate, not the payload.</p>
     */
    private boolean isEffectiveRecordingIdle() {
        if (pipelineBinder != null) {
            return pipelineBinder.getState().getValue().getRecording()
                    instanceof net.devemperor.dictate.state.RecordingState.Idle;
        }
        RecordingState s = recordingStateController != null
                ? recordingStateController.getState() : RecordingState.Idle.INSTANCE;
        return s instanceof RecordingState.Idle;
    }

    private boolean isEffectiveRecordingActiveOrPaused() {
        if (pipelineBinder != null) {
            net.devemperor.dictate.state.RecordingState rs =
                    pipelineBinder.getState().getValue().getRecording();
            return net.devemperor.dictate.state.DictateUiStateKt.isActiveOrPaused(rs);
        }
        RecordingState s = recordingStateController != null
                ? recordingStateController.getState() : RecordingState.Idle.INSTANCE;
        return s.isRecordingOrPaused();
    }

    /**
     * C5 — recording is Active, Paused <i>or</i> Preparing (the
     * "a recording session is in flight" predicate the prompt-queue
     * toggle gate uses). Mirrors the legacy
     * {@code isRecordingOrPaused() || instanceof Preparing} check across
     * both state types.
     */
    private boolean isEffectiveRecordingInFlight() {
        if (pipelineBinder != null) {
            net.devemperor.dictate.state.RecordingState rs =
                    pipelineBinder.getState().getValue().getRecording();
            return net.devemperor.dictate.state.DictateUiStateKt.isActiveOrPaused(rs)
                    || rs instanceof net.devemperor.dictate.state.RecordingState.Preparing;
        }
        RecordingState s = recordingStateController != null
                ? recordingStateController.getState() : RecordingState.Idle.INSTANCE;
        return s.isRecordingOrPaused() || s instanceof RecordingState.Preparing;
    }

    /**
     * C5 — cancel an in-flight recording on the authoritative path.
     * New path: dispatch {@code CancelRecording} (RecordingModule rolls
     * Preparing/Active/Paused → Idle, deletes the audio, dismisses the
     * FGS notification) and discard the R-1 config snapshot. Legacy:
     * {@code recordingStateController.cancelRecording()} as pre-C5.
     */
    private void cancelEffectiveRecording() {
        if (pipelineBinder != null) {
            if (newPathRecordingSessionId != null && imePipelineConfigResolver != null) {
                imePipelineConfigResolver.discard(newPathRecordingSessionId);
            }
            newPathRecordingSessionId = null;
            pipelineBinder.dispatch(
                    net.devemperor.dictate.state.Action.RecordingAction.CancelRecording.INSTANCE);
            return;
        }
        if (recordingStateController != null) {
            recordingStateController.cancelRecording();
        }
    }

    /**
     * C5 — toggle pause/resume on the authoritative path. New path:
     * dispatch {@code PauseRecording} when Active, {@code ResumeRecording}
     * when Paused (the orchestrator FSM is the source of truth + the §7.6
     * Recording-Paused notification swap). Legacy:
     * {@code recordingStateController.togglePause()} as pre-C5.
     */
    private void togglePauseEffectiveRecording() {
        if (pipelineBinder != null) {
            net.devemperor.dictate.state.RecordingState rs =
                    pipelineBinder.getState().getValue().getRecording();
            if (rs instanceof net.devemperor.dictate.state.RecordingState.Active) {
                pipelineBinder.dispatch(
                        net.devemperor.dictate.state.Action.RecordingAction.PauseRecording.INSTANCE);
            } else if (rs instanceof net.devemperor.dictate.state.RecordingState.Paused) {
                pipelineBinder.dispatch(
                        net.devemperor.dictate.state.Action.RecordingAction.ResumeRecording.INSTANCE);
            }
            return;
        }
        if (recordingStateController != null) {
            recordingStateController.togglePause();
        }
    }

    private void startRecording() {
        promptQueueManager.prepareAutoApplyQueue();

        // Pre-Dispatch-Allocation (Spec 1 §4.11.4, R.2). The Service-owned
        // AudioFileFactory is the single source for cache-file paths; it
        // produces a UUID-suffixed name in cacheDir/audio/ that survives
        // the multi-job model (R.8) and the boot-time orphan cleanup
        // (KG-AFF-4 freshness cut-off).
        //
        // The legacy fixed `cacheDir/audio.m4a` path is migrated by
        // LegacyAudioFileMigration on the next Service boot.
        //
        // The factory is only available once the Service binder is up
        // (`onServiceConnected`). The early-tap defensive path below
        // toasts and bails — the user retries once the bind lands.
        if (pipelineBinder == null) {
            android.widget.Toast.makeText(
                    this, R.string.dictate_service_not_ready,
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // D-14 (C9-C2): the allocated file is handed to the orchestrator
        // via StartRecording and becomes RecordingState's authoritative
        // payload (Spec 1 §15.2). The IME keeps only this method-local
        // reference (LastFileName mirror + the action arg); the
        // send-tap reads it back from state.recording, not an IME field.
        File audioFile;
        try {
            audioFile = pipelineBinder.getAudioFileFactory().allocate();
        } catch (java.io.IOException e) {
            // Storage full / FS permission. Surface a user-visible
            // toast and bail out — the reducer never sees the failure
            // (R.2 Pure-Reducer invariant: IO lives in the resolver).
            Log.w("DictateIME", "AudioFileFactory.allocate failed", e);
            android.widget.Toast.makeText(
                    this, R.string.dictate_storage_full,
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }

        DictatePrefsKt.put(sp.edit(), Pref.LastFileName.INSTANCE, audioFile.getName()).apply();

        // The orchestrator's RecordingModule drives the real
        // RecordingHardwareAdapter MediaRecorder (AC-2). The pre-allocated
        // UUID is the FSM's single sessionId source (F-10); it is carried
        // into RecordingState.Preparing → Active → Paused and read back on
        // the payload-less StopRecordingAndSend (FN-4). useBluetooth is read
        // off Pref into AudioState by the orchestrator (the StartRecording
        // reducer reads ctx.global.audio.useBluetoothMic — already
        // pref-mirrored), so it is NOT threaded on the action.
        String preAllocatedId = java.util.UUID.randomUUID().toString();
        newPathRecordingSessionId = preAllocatedId;
        pipelineBinder.dispatch(new net.devemperor.dictate.state.Action.RecordingAction.StartRecording(
                net.devemperor.dictate.state.InsertionTarget.INPUT_CONNECTION,
                audioFile,
                preAllocatedId));
    }

    private void stopRecording() {
        // The send-tap is the IME-runtime config snapshot instant (the same
        // place + same field values the legacy path read) so the
        // orchestrator's async PipelineModule.SubmitPipeline →
        // PipelineRunnerSubsystemAdapter → ImePipelineConfigResolver
        // rebuild a JobRequest field-for-field identical to the legacy
        // construction (R-1: a dropped field is silent data loss). Then
        // dispatch the payload-less StopRecordingAndSend (FN-4) — the
        // RecordingModule reads the sessionId off the live FSM and fires
        // EmitPipelineTrigger → TriggerPipeline → SubmitPipeline.
        String sessionId = newPathRecordingSessionId;
        if (sessionId == null || pipelineBinder == null
                || imePipelineConfigResolver == null) {
            // Defensive: a stop with no in-flight new-path recording
            // (e.g. binder dropped mid-recording). Nothing to send; clear
            // any stale snapshot key. The orchestrator FSM is the source
            // of truth — a StopRecordingAndSend with no Active state is a
            // reducer no-op (Rejected), so dispatching is harmless, but
            // without a snapshot the resolver would throw; bail cleanly.
            Log.w("DictateIME",
                    "stopRecording (new path): no in-flight session — skipping send");
            return;
        }

        // B2-VAL-W1 F-3 — sendable-state guard BEFORE the destructive
        // pre-dispatch. `StopRecordingAndSend` from a non-bearing
        // recording state (still `Preparing` — BT-SCO wait unresolved,
        // or a slow `MediaRecorder.prepare()`) is a reducer no-op
        // (RecordingModule has no `Preparing + StopRecordingAndSend`
        // arm → Rejected). But the trio below is irreversible *before*
        // any FSM check: `captureFreshConfigSnapshot` consumes/resets
        // the one-shot flags (livePrompt / autoSwitchKeyboard /
        // pendingLivePromptChain), `primePipelineUiForNewPath` shows the
        // "Sending…" keyboard, and `newPathRecordingSessionId=null`
        // orphans the recording. The F-1/F-2 Preparing-SCO redesign
        // *widens* the Preparing window (a BT-mic recording can stay
        // `Preparing(awaitingSco)` for up to 2500 ms), so a
        // Send-while-Preparing race is materially more likely. Bail
        // cleanly here — nothing destructive has run yet — exactly like
        // the existing defensive null-guard above.
        if (!isEffectiveRecordingActiveOrPaused()) {
            Log.w("DictateIME",
                    "stopRecording (new path): recording not Active/Paused "
                            + "(still Preparing?) — skipping send, recording preserved");
            return;
        }

        // D-14 (C9-C2): the recording's audio file is sourced from the
        // orchestrator's authoritative state.recording payload (Spec 1
        // §15.2), not a removed IME field. The Active/Paused guard above
        // guarantees a non-null handle; bail defensively if state raced
        // away (binder dropped) — nothing destructive has run yet.
        File recordingAudioFile = net.devemperor.dictate.state.DictateUiStateKt
                .getAudioFileOrNull(pipelineBinder.getState().getValue().getRecording());
        if (recordingAudioFile == null) {
            Log.w("DictateIME",
                    "stopRecording (new path): no audioFile in state.recording "
                            + "— skipping send, recording preserved");
            return;
        }

        captureFreshConfigSnapshot(sessionId, recordingAudioFile);
        // Drive the legacy keyboard pipeline UI (KeyboardUiController is
        // still the render path until Theme-C/C3 retires it) so the
        // keyboard shows "Sending…"/progress exactly as the legacy
        // trigger did. The orchestrator owns state.pipeline; this is the
        // thin IME-side UI bookkeeping the legacy path also performed.
        primePipelineUiForNewPath();

        newPathRecordingSessionId = null;
        pipelineBinder.dispatch(
                net.devemperor.dictate.state.Action.RecordingAction.StopRecordingAndSend.INSTANCE);
    }

    /**
     * C5 (R-1) — compute the 8 IME-runtime-only fresh-recording
     * {@code JobRequest} fields exactly as the legacy pre-C7 inline
     * {@code JobRequest.TranscriptionPipeline} construction did
     * ({@code DictateInputMethodService.java:2214-2230} pre-C5; deleted
     * by C7 — this helper is now the single field-faithful source) and
     * stash them in {@link #imePipelineConfigResolver} keyed by
     * {@code sessionId}. The orchestrator consumes the snapshot
     * asynchronously when its pipeline runner submits.
     *
     * <p>Shared by both the fresh-recording send-tap
     * ({@link #stopRecording()}) and the imported-audio-file path
     * ({@link #transcribeImportedAudioFileViaOrchestrator()},
     * C7-IMPL-1).</p>
     *
     * <p>Called at the send-tap (the legacy trigger instant) so every
     * field — including the {@code livePrompt}/{@code autoSwitchKeyboard}
     * instance flags that are reset right after — is captured with the
     * same timing the legacy path used.</p>
     *
     * <p>D-14 (C9-C2): {@code audioFile} is passed in rather than read
     * from a removed IME field. The fresh-recording caller
     * ({@link #stopRecording()}) sources it from the orchestrator's
     * {@code state.recording} (the post-cutover authoritative payload,
     * Spec 1 §15.2); the imported-audio caller
     * ({@link #transcribeImportedAudioFileViaOrchestrator(File)}) passes
     * the imported scratch file (no recording session exists for an
     * import).</p>
     */
    private void captureFreshConfigSnapshot(String sessionId, File audioFile) {
        int totalSteps = 1; // transcription always
        if (autoFormattingService.isEnabled()) totalSteps++;
        totalSteps += promptQueueManager.getQueuedIds().size();

        // D-13: language source is resolveEffectiveLanguage() — the
        // ReprocessStaging override (when staging) over the permanent
        // pref-resolved language (preferences.LanguageResolver). Exactly
        // the deleted legacy language-controller's effective-resolution
        // semantics, so R-1 transcription-config fidelity is preserved.
        // "detect" remains
        // the explicit "let Whisper detect" sentinel — null on the wire.
        String effectiveLanguage = resolveEffectiveLanguage();
        String language = !"detect".equals(effectiveLanguage) ? effectiveLanguage : null;
        String stylePrompt = promptService.resolveWhisperStylePrompt(effectiveLanguage);

        EditorInfo info = getCurrentInputEditorInfo();
        String targetAppPackage = info != null && info.packageName != null
                ? info.packageName.toString() : null;
        boolean showResend = new File(getCacheDir(), DictatePrefsKt.get(sp, Pref.LastFileName.INSTANCE)).exists()
                && DictatePrefsKt.get(sp, Pref.ResendButton.INSTANCE);

        imePipelineConfigResolver.snapshotFresh(
                sessionId,
                new ImePipelineConfigResolver.FreshConfig(
                        totalSteps,
                        audioFile.getAbsolutePath(),
                        language,
                        promptQueueManager.getQueuedIds(),
                        targetAppPackage,
                        stylePrompt,
                        livePrompt,
                        autoSwitchKeyboard,
                        showResend));

        // Mirror the legacy post-build flag handling
        // (DictateInputMethodService.java:2232-2234 pre-C5): the
        // live-prompt chain is armed from the captured value, then the
        // one-shot instance flags are cleared so the next recording
        // starts clean.
        pendingLivePromptChain = livePrompt;
        livePrompt = false;
        autoSwitchKeyboard = false;
    }

    /**
     * C5 — drive the legacy {@code KeyboardUiController} pipeline UI for
     * the new path so the keyboard still shows the "Sending…"/progress
     * affordance the legacy pre-C7 fresh-recording trigger set up. Shared
     * by {@link #stopRecording()} and
     * {@link #transcribeImportedAudioFileViaOrchestrator()} (C7-IMPL-1).
     * The orchestrator owns the authoritative
     * {@code state.pipeline}; this is the same thin UI bookkeeping the
     * legacy trigger performed (the RenderBackend cutover that makes this
     * unnecessary is Theme-C/C3, out of C5 scope).
     */
    private void primePipelineUiForNewPath() {
        try {
            uiController.preparePipeline();
            resendButton.setVisibility(KeyboardVisibilityPredicates.resolveResendVisibility(
                    new File(getCacheDir(), DictatePrefsKt.get(sp, Pref.LastFileName.INSTANCE)).exists(),
                    DictatePrefsKt.get(sp, Pref.ResendButton.INSTANCE),
                    recordingStateController != null
                            ? recordingStateController.getState()
                            : RecordingState.Idle.INSTANCE,
                    uiController.getState()));
            infoBarController.dismiss();
            updatePromptButtonsEnabledState();
            stateManager.refresh();

            int totalSteps = 1;
            if (autoFormattingService.isEnabled()) totalSteps++;
            totalSteps += promptQueueManager.getQueuedIds().size();
            boolean autoEnter = DictatePrefsKt.get(sp, Pref.AutoEnter.INSTANCE);
            uiController.startPipeline(totalSteps, new KeyboardUiController.AutoEnterConfig(autoEnter));
        } catch (RuntimeException e) {
            // UI bookkeeping is best-effort — a view-recreation race must
            // not abort the (already-dispatched) recording stop. The
            // orchestrator state is authoritative regardless.
            Log.w("DictateIME", "primePipelineUiForNewPath failed (non-fatal)", e);
        }
    }

    private void updateKeepScreenAwake(boolean keepAwake) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (mainHandler != null) {
                mainHandler.post(() -> updateKeepScreenAwake(keepAwake));
            }
            return;
        }

        if (dictateKeyboardView != null) {
            dictateKeyboardView.setKeepScreenOn(keepAwake);
        }

        if (keepScreenAwakeApplied == keepAwake) return;

        Dialog windowDialog = getWindow();
        if (windowDialog == null) {
            if (!keepAwake) keepScreenAwakeApplied = false;
            return;
        }

        Window window = windowDialog.getWindow();
        if (window == null) {
            if (!keepAwake) keepScreenAwakeApplied = false;
            return;
        }

        if (keepAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        keepScreenAwakeApplied = keepAwake;
    }

    /**
     * Transcribe a user-imported, pre-existing audio file via the new
     * orchestrator (C7-IMPL-1 closure, mid-chunk-triage wave
     * B2-C7-MID-W1).
     *
     * <p><b>Why this is orchestrator-routed (AC-10).</b> The Settings
     * "transcribe an imported audio file" feature
     * ({@code Pref.TranscriptionAudioFile}, picked up by
     * {@link #onStartInputView}) submits a brand-new {@code RECORDING}-kind
     * transcription of a file that already exists — there is no recording
     * FSM to drive. C7 deleted the legacy fresh-recording + reprocess
     * {@code JobExecutor.start} branches; this site was the last
     * non-RESUME legacy {@code JobExecutor.start} survivor. It is now
     * routed through the orchestrator exactly like a fresh recording's
     * post-record submit: snapshot the IME-runtime config (field-faithful
     * via the shared {@link #captureFreshConfigSnapshot}) then dispatch
     * {@link net.devemperor.dictate.state.Action.PipelineAction.TriggerPipeline}
     * — the documented Spec 1 §3 pipeline entry-point that the recording
     * FSM itself emits ({@code RecordingModule.Effect.EmitPipelineTrigger}).
     * {@code PipelineModule} reduces it from {@code Idle} to
     * {@code Preparing} and emits {@code Effect.SubmitPipeline} →
     * {@code PipelineRunnerSubsystemAdapter.submit} →
     * {@code ImePipelineConfigResolver.resolveFresh} (consuming the
     * snapshot) → {@code JobExecutor.start} <i>inside the C3 adapter</i>
     * (the sole legacy {@code JobExecutor.start} site; AC-10 satisfied —
     * only RESUME remains in the IME).</p>
     *
     * <p><b>R-1 fidelity.</b> {@link #captureFreshConfigSnapshot} computes
     * the 8 IME-runtime fields identically to the pre-C7 inline
     * construction (it was extracted from this very method in C5), so the
     * resulting {@code JobRequest} is provably field-for-field identical
     * to the deleted legacy construction (no silent config drift).</p>
     *
     * <p>The single-submit guard is the {@code PipelineUiState.Idle}
     * reducer edge (a second trigger while {@code Preparing}/{@code Running}
     * is a reducer no-op) — structurally equivalent to the legacy
     * {@code JobExecutor.start} busy-{@code false}. The
     * {@link ActiveJobRegistry#isAnyActive()} pre-check preserves the
     * legacy busy-toast user feedback (mirrors the new-path reprocess
     * route in {@link #handleReprocessSend}).</p>
     */
    private void transcribeImportedAudioFileViaOrchestrator(File audioFile) {
        // Service not yet bound: the orchestrator route is unavailable.
        // Surface a not-ready toast and bail (mirror handleReprocessSend).
        if (pipelineBinder == null || imePipelineConfigResolver == null) {
            android.widget.Toast.makeText(
                    this, R.string.dictate_service_not_ready,
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // Single-submit busy pre-check (faithful to the legacy
        // started == false branch + the established new-path reprocess
        // pattern in handleReprocessSend). The FSM Idle-guard is the
        // structural protection; this only preserves the user-visible
        // busy feedback the legacy path showed.
        if (ActiveJobRegistry.INSTANCE.isAnyActive()) {
            showJobBusyToast();
            return;
        }

        // Preparing state: keyboard shows "Sending..."/progress exactly as
        // the legacy trigger did (the orchestrator owns the authoritative
        // state.pipeline; this is the same thin IME-side UI bookkeeping).
        infoBarController.dismiss();
        updatePromptButtonsEnabledState();
        primePipelineUiForNewPath();

        // Mint the sessionId (was preAllocatedId pre-C7) and snapshot the
        // IME-runtime config field-for-field via the shared C5 helper —
        // it takes the imported {@code audioFile} (passed in by the
        // caller; D-14/C9-C2 removed the IME field), computes all 8
        // IME-runtime fields exactly as the deleted legacy construction
        // did, snapshots them into imePipelineConfigResolver, and performs
        // the pendingLivePromptChain / one-shot-flag reset (legacy parity).
        String sessionId = java.util.UUID.randomUUID().toString();
        captureFreshConfigSnapshot(sessionId, audioFile);

        // Dispatch the documented pipeline entry-point. No recording FSM:
        // TriggerPipeline goes straight Idle → Preparing → SubmitPipeline →
        // the C3 runner adapter → the IME-faithful resolver (single
        // dispatch, ADR-0001; Mode-1 same-axis effect, ADR-0002).
        pipelineBinder.dispatch(
                new net.devemperor.dictate.state.Action.PipelineAction.TriggerPipeline(
                        sessionId, audioFile));
    }

    /**
     * Prepares UI and launches a standalone prompt via PipelineOrchestrator.
     * Replaces the old startGPTApiRequest(model) method.
     */
    private void runStandalonePromptViaOrchestrator(PromptEntity model) {
        // Determine selected text (must be read on main thread)
        CharSequence selectedText = null;
        if (model.getRequiresSelection()) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                selectedText = ic.getSelectedText(0);
            }
        }
        String selStr = selectedText != null ? selectedText.toString() : null;

        // Set UI mode BEFORE calling orchestrator.
        // Guard removed intentionally (plan §4e): in the live-prompt-chain case the previous
        // pipeline's state is still Running with completedSteps == totalSteps. Calling
        // startPipeline(1, ..., 0) unconditionally resets the counter to "0/1" — without this
        // the stale counter of the prior transcription would remain on screen.
        //
        // Auto-enter handling: the previous pipeline's controller-owned AutoEnterConfig is the
        // authoritative source (it reflects any in-pipeline user toggle). Direct-prompt-button
        // callers have no previous config — we seed from prefs in that case.
        String displayName = model.getId() == -1 ? getString(R.string.dictate_live_prompt) : model.getName();
        KeyboardUiController.AutoEnterConfig prevCfg = uiController.getAutoEnterConfig();
        boolean autoEnter = (prevCfg != null)
            ? prevCfg.getAutoEnterActive()
            : DictatePrefsKt.get(sp, Pref.AutoEnter.INSTANCE);
        uiController.startPipeline(1, new KeyboardUiController.AutoEnterConfig(autoEnter), 0);

        EditorInfo editorInfo = getCurrentInputEditorInfo();
        PipelineOrchestrator.StandaloneConfig config = new PipelineOrchestrator.StandaloneConfig(
            model, selStr, null,
            editorInfo != null ? editorInfo.packageName : null);

        if (pipelineOrchestrator == null) {
            Log.w("DictateIME", "runStandalonePrompt: pipelineOrchestrator not yet bound — dropping click");
            return;
        }
        pipelineOrchestrator.runStandalonePrompt(config);
    }

    // ===== PipelineOrchestrator.PipelineCallback =====

    @Override
    public void onStepStarted(@androidx.annotation.NonNull String stepName) {
        mainHandler.post(() -> {
            if (uiController == null) return;  // View recreation not yet complete
            if (uiController.getState() instanceof PipelineUiState.Running) {
                uiController.addRunningStep(stepName);
            }
        });
    }

    @Override
    public void onStepCompleted(@androidx.annotation.NonNull String stepName, long durationMs) {
        mainHandler.post(() -> {
            if (uiController == null) return;  // View recreation not yet complete
            if (uiController.getState() instanceof PipelineUiState.Running) {
                uiController.completeStep(stepName, durationMs);
            }
        });
    }

    @Override
    public void onStepFailed(@androidx.annotation.NonNull String stepName) {
        mainHandler.post(() -> {
            if (uiController == null) return;  // View recreation not yet complete
            if (uiController.getState() instanceof PipelineUiState.Running) {
                uiController.failStep(stepName);
            }
        });
    }

    @Override
    public void onPipelineCompleted(@androidx.annotation.NonNull String text, @androidx.annotation.NonNull InsertionSource source) {
        mainHandler.post(() -> {
            if (pendingLivePromptChain) {
                // Live prompt: transcription result becomes the prompt for a completion call
                pendingLivePromptChain = false;
                if (uiController == null) return;  // View recreation not yet complete
                PromptEntity liveEntity = new PromptEntity(-1, Integer.MIN_VALUE, "", text, true, false);
                runStandalonePromptViaOrchestrator(liveEntity);
            } else {
                commitTextToInputConnection(text, source);
            }
        });
    }

    @Override
    public void onPipelineError(@androidx.annotation.NonNull String errorInfoKey, boolean vibrate, @androidx.annotation.Nullable String providerName) {
        mainHandler.post(() -> {
            if (infoBarController == null) return;  // View recreation not yet complete
            showInfo(errorInfoKey, providerName);
        });
        if (vibrate && vibrationEnabled) {
            vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    @Override
    public void onShowResend() {
        mainHandler.post(() -> {
            if (resendButton == null) return;  // View recreation not yet complete
            // Block-1a Quick-Win exception (Spec 1 §9.4):
            // This callback fires from PipelineOrchestrator BEFORE the
            // pipeline-state transitions back to Idle (`onPipelineFinished()` is
            // posted separately and calls `uiController.stopPipeline()` only
            // after this returns). Running `isResendVisible` here would
            // therefore evaluate to `false` and the resend button would never
            // appear — the very thing the callback exists to do. Block 5
            // (LayoutCatalog) folds the predicate into a state-driven
            // subscriber and re-orders the pipeline-completion sequence so
            // this explicit setter disappears entirely. Until then, the
            // gating happens upstream: `onShowResend` is only fired when
            // `PipelineConfig.showResendButton == true`, which is itself
            // derived from `Pref.ResendButton` AND the cached audio file
            // existing (see `captureFreshConfigSnapshot`).
            resendButton.setVisibility(View.VISIBLE);
        });
    }

    @Override
    public void onAutoSwitch() {
        mainHandler.post(this::switchToPreviousKeyboard);
    }

    @Override
    public void onAudioPersisted(@androidx.annotation.NonNull File audioFile, @androidx.annotation.NonNull String sessionId) {
        // MediaMetadataRetriever is Android-API -> stays in the Service
        dbExecutor.execute(() -> {
            try {
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                retriever.setDataSource(audioFile.getAbsolutePath());
                String durationStr = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
                retriever.release();
                if (durationStr != null) {
                    long durationSeconds = Long.parseLong(durationStr) / 1000;
                    sessionManager.updateAudioDuration(sessionId, durationSeconds);
                }
            } catch (Exception e) {
                Log.w("DictateIME", "Failed to extract audio duration", e);
            }
        });
    }

    @Override
    public void onPipelineFinished() {
        // If a live prompt chain is pending, skip the UI/session reset —
        // runStandalonePromptViaOrchestrator will start a new pipeline that calls onPipelineFinished when done.
        if (pendingLivePromptChain) return;

        // Clear the transient current-session tracking (DB is source of truth
        // for "last keyboard session" — see SessionTracker.getLastKeyboardSession).
        sessionTracker.clearCurrent();
        mainHandler.post(() -> {
            if (uiController == null) return;  // View recreation not yet complete
            uiController.stopPipeline();  // → updatePipelineState(Idle) → Callback → QWERTZ reset
            uiController.restoreRecordButtonIdle(
                getDictateButtonText(),
                R.drawable.ic_baseline_mic_20,
                R.drawable.ic_baseline_folder_open_20);
            // QWERTZ-Reset happens automatically via onPipelineUiStateChanged callback
        });
    }

    private boolean isAutoEnterActive() {
        KeyboardUiController.AutoEnterConfig cfg = uiController != null ? uiController.getAutoEnterConfig() : null;
        if (cfg != null) return cfg.getAutoEnterActive();
        return DictatePrefsKt.get(sp, Pref.AutoEnter.INSTANCE);
    }

    private void toggleAutoEnterOverride() {
        // Only meaningful during Running — during Preparing the auto-enter chip is not visible,
        // and during Idle there is no pipeline to toggle against.
        if (uiController == null || !uiController.isPipelineRunning()) return;
        // Controller owns the PipelineConfig; it atomically flips config + Running.autoEnterActive.
        uiController.toggleAutoEnter();
    }

    /**
     * Backward-compat wrapper — captures the live IC + EditorInfo and
     * delegates to the parametrised overload with auto-enter enabled. All
     * existing pipeline call sites continue to work unchanged.
     */
    private void commitTextToInputConnection(String text, InsertionSource source) {
        commitTextToInputConnection(
                getCurrentInputConnection(),
                getCurrentInputEditorInfo(),
                text,
                source,
                /* sessionIdOverride = */ null,
                /* enableAutoEnter   = */ true);
    }

    /**
     * Commit text via an explicit {@link InputConnection}.
     *
     * Used by the Phase-5 resend-button short-press path so the IC captured
     * at click time can be reused if the editor focus has drifted by the
     * time the DB lookup completes. Side-effects (replaced-text capture,
     * slow-output animation, auto-enter, DB log) are funnelled through this
     * single path so all stages of the resend strategy behave consistently.
     *
     * @param ic                  the {@link InputConnection} to commit on.
     *                            {@code null} → returns {@code false} (no-op).
     * @param editor              the {@link EditorInfo} that pairs with
     *                            {@code ic}; used only for the audit log
     *                            (package name).
     * @param text                the text to insert. {@code null} treated as
     *                            empty string.
     * @param source              audit/telemetry classifier; {@code null}
     *                            disables the DB write.
     * @param sessionIdOverride   when non-{@code null} the audit log binds
     *                            to this session id instead of the
     *                            {@link SessionTracker#getCurrentSessionId() current}
     *                            one. Resend-clicks pass the
     *                            {@code lastSession.getId()} here because the
     *                            tracker has already been cleared by the
     *                            time the click runs.
     * @param enableAutoEnter     when {@code true} the auto-enter side-effect
     *                            ({@link #scheduleAutoEnter}) runs after a
     *                            successful commit; when {@code false} it is
     *                            suppressed. The pipeline transcription/
     *                            standalone-prompt paths pass {@code true} —
     *                            the resend-button paths (Stage 1 + Stage 2
     *                            of {@link ResendInsertStrategy}) pass
     *                            {@code false} because (a) Stage 2 commits
     *                            on a captured IC while
     *                            {@link #scheduleAutoEnter}/{@link #performEnterAction}
     *                            would later fire on the live IC — Enter
     *                            would land in the wrong field — and (b) a
     *                            Resend click is a recovery insert, not a
     *                            new transcription, so silently appending
     *                            Enter is unwanted UX in either stage.
     * @return {@code true} if the commit succeeded, {@code false} if the IC
     *         was {@code null} or {@code commitText()} reported failure.
     */
    private boolean commitTextToInputConnection(
            InputConnection ic,
            EditorInfo editor,
            String text,
            InsertionSource source,
            String sessionIdOverride,
            boolean enableAutoEnter) {
        if (ic == null) return false;

        // 1. Capture replaced (selected) text before commit for undo-buffer / audit
        String replacedText = null;
        if (source != null) {
            replacedText = safeReadSelectedText(ic);
        }

        String output = text == null ? "" : text;

        // 2. InstantOutput vs slow-output branch — same path for live and
        //    captured IC so UX (char-by-char animation) stays consistent.
        boolean success;
        if (DictatePrefsKt.get(sp, Pref.InstantOutput.INSTANCE)) {
            success = ic.commitText(output, 1);
        } else if (mainHandler != null) {
            success = commitSlowOutput(ic, output);
        } else {
            success = ic.commitText(output, 1);
        }
        if (!success) return false;

        // 3. Auto-enter: send as separate control character with delay so
        //    terminal emulators (e.g. Termux/Claude Code) treat it as a
        //    distinct keystroke, not part of the paste block. Suppressed
        //    for resend paths — see KDoc on the {@code enableAutoEnter}
        //    parameter for the rationale.
        if (enableAutoEnter && isAutoEnterActive()) {
            scheduleAutoEnter(output);
        }

        // 4. Persist text insertion and update session's final output
        if (source != null && output.length() > 0) {
            final String fReplacedText = replacedText;
            final String fSessionId = sessionIdOverride != null
                    ? sessionIdOverride : sessionTracker.getCurrentSessionId();
            final String fStepId = sessionTracker.getCurrentStepId();
            final String fTranscriptionId = sessionTracker.getCurrentTranscriptionId();
            final String pkg = editor != null ? editor.packageName : null;

            dbExecutor.execute(() -> {
                sessionManager.logTextInsertion(fSessionId, output, fReplacedText, pkg,
                    null, fStepId, fTranscriptionId, InsertionMethod.COMMIT);
                if (fSessionId != null) {
                    sessionManager.updateFinalOutputText(fSessionId, output);
                }
            });
        }
        return true;
    }

    /**
     * Slow-output animation: char-by-char commit on the captured (or live)
     * IC, scheduled on {@code mainHandler}. Returns whether the first
     * character was committed successfully — a {@code false} here signals an
     * IC that rejects writes (stale / closed) and the caller can fall
     * through to the next stage.
     *
     * <p><b>IC-capture semantics (Phase 5 refactor):</b> the {@link
     * InputConnection} is captured <i>once</i> at the start of the animation
     * and reused for every scheduled character. This is a deliberate
     * behaviour change from the pre-refactor loop, which re-fetched
     * {@code getCurrentInputConnection()} per character. As a result, if the
     * user changes editor focus mid-animation, the remaining scheduled
     * characters will fail silently — {@code commitText()} returns
     * {@code false} on the now-stale IC and we drop them. For the
     * captured-IC resend use case (Stage 2 of {@link ResendInsertStrategy})
     * this is the desired behaviour: the resend explicitly targets the
     * editor that was focused at click time, not whatever the user has
     * navigated to since.</p>
     */
    private boolean commitSlowOutput(InputConnection ic, String output) {
        if (output.isEmpty()) return ic.commitText(output, 1);

        int speed = DictatePrefsKt.get(sp, Pref.OutputSpeed.INSTANCE);
        // Commit first character synchronously so we can detect a dead IC
        // immediately and report the failure to the caller.
        boolean firstOk = ic.commitText(String.valueOf(output.charAt(0)), 1);
        if (!firstOk) return false;
        for (int i = 1; i < output.length(); i++) {
            String characterString = String.valueOf(output.charAt(i));
            long delay = (long) (i * (20L / (speed / 5f)));
            mainHandler.postDelayed(() -> ic.commitText(characterString, 1), delay);
        }
        return true;
    }

    /**
     * Quality-Gate W-3 — central try-catch for {@link InputConnection#getSelectedText(int)}.
     * Stale IC implementations are documented to throw on read attempts; we
     * swallow the exception and treat the result as "no selected text".
     */
    private static String safeReadSelectedText(InputConnection ic) {
        try {
            CharSequence sel = ic.getSelectedText(0);
            return (sel != null && sel.length() > 0) ? sel.toString() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Builds the keyboard prompt list with sentinel entries for instant prompt, select-all, clear-queue, and add button.
     */
    private List<PromptEntity> buildPromptsWithControlButtons(List<PromptEntity> dbPrompts) {
        List<PromptEntity> result = new ArrayList<>(dbPrompts.size() + 4);
        result.add(new PromptEntity(-1, Integer.MIN_VALUE, null, null, false, false));      // instant prompt
        result.add(new PromptEntity(-3, Integer.MIN_VALUE + 1, null, null, false, false));  // select all
        result.add(new PromptEntity(-4, Integer.MIN_VALUE + 2, null, null, false, false));  // clear queue
        result.addAll(dbPrompts);
        result.add(new PromptEntity(-2, Integer.MAX_VALUE, null, null, false, false));       // add button
        return result;
    }

    /**
     * Reloads prompts from the database on a background thread and updates the adapter on the main thread.
     * Debounced via InvalidationTracker — safe to call multiple times in quick succession.
     */
    private void reloadPrompts() {
        if (promptDao == null || mainHandler == null) return;
        dbExecutor.execute(() -> {
            List<PromptEntity> dbPrompts = promptDao.getAll();
            List<PromptEntity> fullList = buildPromptsWithControlButtons(dbPrompts);

            mainHandler.post(() -> {
                if (promptsAdapter == null || promptQueueManager == null) return;
                promptsAdapter.updateData(fullList);

                // Sync queue state with current prompt IDs
                Set<Integer> validIds = new HashSet<>();
                for (PromptEntity p : fullList) {
                    if (p.getId() >= 0) validIds.add(p.getId());
                }
                promptQueueManager.restoreQueue(validIds);
                onQueueChanged(promptQueueManager.getQueuedIds());
                updateSelectAllPromptState();
            });
        });
    }

    private void updatePromptButtonsEnabledState() {
        RecordingState state = recordingStateController != null ? recordingStateController.getState() : RecordingState.Idle.INSTANCE;
        disableNonSelectionPrompts = state.isRecordingOrPaused() || state instanceof RecordingState.Preparing;
        if (promptsAdapter == null) return;
        if (mainHandler != null) {
            mainHandler.post(() -> {
                promptsAdapter.setDisableNonSelectionPrompts(disableNonSelectionPrompts);
                updateSelectAllPromptState();
            });
        } else {
            promptsAdapter.setDisableNonSelectionPrompts(disableNonSelectionPrompts);
            updateSelectAllPromptState();
        }
    }

    private void switchToPreviousKeyboard() {
        boolean success = false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                success = switchToNextInputMethod(false);
            } else {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                success = imm.switchToLastInputMethod(getWindow().getWindow().getAttributes().token);
            }
        } catch (Exception ignored) {}

        if (!success) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showInputMethodPicker();
        }
    }

    private void showInfo(String type) {
        infoBarController.showInfo(type);
    }

    private void showInfo(String type, String providerName) {
        infoBarController.showInfo(type, providerName);
    }

    /**
     * Phase 2 §2.5b: record-button label for the current effective
     * language. The self-heal + StringSet sanitisation lives in
     * {@link InputLanguagesPlugin#sanitize} + the
     * {@link net.devemperor.dictate.preferences.LanguageResolver} pos
     * resync (D-13); this method is now a pure lookup.
     */
    private String getDictateButtonText() {
        if (sp == null) {
            // Defensive: only reachable in the brief window before sp is
            // assigned in onCreate(). Fall back to the first entry of
            // InputLanguagesPlugin.defaultValue so there is a single
            // source of truth for the default code (avoids a hard-coded
            // "detect" drifting out of sync with the plugin).
            String defaultCode = InputLanguagesPlugin.INSTANCE.getDefaultValue().get(0);
            return LanguageLabelResolver.INSTANCE.recordLabelFor(defaultCode);
        }
        String code = resolveEffectiveLanguage();
        return LanguageLabelResolver.INSTANCE.recordLabelFor(code);
    }

    private void deleteOneCharacter() {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection == null) return;

        CharSequence selectedText = inputConnection.getSelectedText(0);
        if (selectedText != null && selectedText.length() > 0) {
            inputConnection.commitText("", 1);
            return;
        }

        CharSequence textBeforeCursor = inputConnection.getTextBeforeCursor(DELETE_LOOKBACK_CHARACTERS, 0);
        if (textBeforeCursor == null || textBeforeCursor.length() == 0) {
            inputConnection.deleteSurroundingText(1, 0);
            return;
        }

        String before = textBeforeCursor.toString();
        BreakIterator breakIterator = BreakIterator.getCharacterInstance(Locale.getDefault());
        breakIterator.setText(before);

        int end = before.length();
        int start = breakIterator.preceding(end);
        if (start == BreakIterator.DONE) {
            try {
                start = before.offsetByCodePoints(end, -1);
            } catch (IndexOutOfBoundsException ignored) {
                start = Math.max(0, end - 1);
            }
        }

        int charsToDelete = Math.max(1, end - start);
        inputConnection.deleteSurroundingText(charsToDelete, 0);
    }

    // ===== MainButtonsController.Callback =====

    @Override
    public void onVibrate() {
        vibrate();
    }

    @Override
    public void onRecordClicked() {
        infoBarController.dismiss();

        // ReprocessStaging: the big record button becomes a Send trigger for the
        // currently staged queue (Phase 9.3).
        PipelineUiState state = uiController != null ? uiController.getState() : null;
        if (state instanceof PipelineUiState.ReprocessStaging) {
            handleReprocessSend((PipelineUiState.ReprocessStaging) state);
            return;
        }

        if (uiController.isPipelineActive()) {
            // Pipeline running or preparing → toggle auto-enter (no-op during Preparing).
            // Using isPipelineActive() closes the Preparing-window race on the QWERTZ record
            // button, which otherwise fell through to startRecording() during audio upload.
            toggleAutoEnterOverride();
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            openSettingsActivity();
        } else if (isEffectiveRecordingIdle()) {
            startRecording();
        } else if (isEffectiveRecordingActiveOrPaused()) {
            stopRecording();
        }
    }

    @Override
    public void onRecordLongClicked() {
        if (isEffectiveRecordingIdle()) {
            Intent intent = new Intent(this, DictateSettingsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra("net.devemperor.dictate.open_file_picker", true);
            startActivity(intent);
        } else if (isEffectiveRecordingActiveOrPaused() && !livePrompt && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            autoSwitchKeyboard = true;
            stopRecording();
        }
    }

    @Override
    public void onResendClicked() {
        // Phase 5 — status-based dispatch with InputConnection capture.
        //
        // The IC + EditorInfo are captured *here* on the main thread, before
        // the DB lookup begins. If the user changes focus while the lookup
        // runs, the live IC obtained later may belong to a different field;
        // insertOrFallback() then falls back to the captured channel.
        final InputConnection capturedIc = getCurrentInputConnection();
        final EditorInfo capturedEditor = getCurrentInputEditorInfo();

        // Quality-Gate N-2 — double-click race: disable the button on the
        // main thread immediately so a second tap within the cooldown window
        // can't kick off a parallel DB lookup + insertion. Re-enabled in the
        // worker's finally block via mainHandler.postDelayed.
        if (mainButtonsController != null) {
            mainButtonsController.setResendEnabled(false);
        }

        dbExecutor.execute(() -> {
            try {
                SessionEntity lastSession = sessionTracker.getLastKeyboardSession();
                if (lastSession == null) return;

                ResendAction action = ResendStatusDispatcher.INSTANCE.decide(
                        lastSession.getStatusEnum(),
                        lastSession.getFinalOutputText(),
                        lastSession.getId());

                if (action instanceof ResendAction.Insert) {
                    ResendAction.Insert insert = (ResendAction.Insert) action;
                    mainHandler.post(() -> insertOrFallback(
                            capturedIc, capturedEditor,
                            insert.getOutput(), insert.getSessionId()));
                } else if (action instanceof ResendAction.Resume) {
                    ResendAction.Resume resume = (ResendAction.Resume) action;
                    mainHandler.post(() -> startResumeJob(resume.getSessionId()));
                }
                // ResendAction.NoOp falls through silently — FAILED status
                // and defensive empty-output COMPLETED both land here.
            } finally {
                // Quality-Gate N-2 — re-enable after a 500 ms cooldown so the
                // capture + dispatch phase has time to settle without a
                // second click slipping through.
                mainHandler.postDelayed(() -> {
                    if (mainButtonsController != null) {
                        mainButtonsController.setResendEnabled(true);
                    }
                }, 500);
            }
        });
    }

    /**
     * Three-stage insertion strategy for the short-press resend path.
     *
     * <p>Called on the main thread <i>after</i> the worker-thread DB lookup
     * has resolved the last keyboard session. The {@code capturedIc} +
     * {@code capturedEditor} were obtained when the button was clicked, so
     * they are still valid even if the user has since focused a different
     * field.</p>
     *
     * <p>Stages, tried in order:</p>
     * <ol>
     *   <li><b>Live IC + same editor</b> — preferred, identical to the normal
     *       transcription-commit path.</li>
     *   <li><b>Captured IC</b> — used when the live IC is {@code null} or
     *       points to a different editor. Android does not invalidate IC
     *       objects synchronously on focus change, so the captured handle
     *       often still works for the original field.</li>
     *   <li><b>Toast + resume job</b> — last resort if both IC channels are
     *       dead (capture's {@code commitText()} reported failure or both
     *       were {@code null}). Shows the user a clear focus-lost message
     *       and starts a pipeline resume so they don't lose the output.</li>
     * </ol>
     */
    private void insertOrFallback(
            InputConnection capturedIc,
            EditorInfo capturedEditor,
            String output,
            String sessionId) {
        // Delegates the strategy to the pure-logic helper so the 3-stage
        // decision tree stays unit-testable (see ResendInsertStrategy +
        // InsertOrFallbackTest). Side-effect adapters (commit, toast,
        // resume) are bound here.
        ResendInsertStrategy.INSTANCE.execute(
                getCurrentInputConnection(),
                getCurrentInputEditorInfo(),
                capturedIc,
                capturedEditor,
                output,
                sessionId,
                // enableAutoEnter = false in BOTH resend stages:
                // - Stage 2 (captured IC): scheduleAutoEnter would later fire
                //   performEnterAction, which reads the *live* IC — Enter would
                //   land in whatever field has focus now, not the captured one.
                // - Stage 1 (live IC, same editor): a Resend click is a recovery
                //   insert, not a new transcription, so appending Enter is
                //   undesirable UX. Both stages route through this adapter.
                (ic, editor, text, sid) -> commitTextToInputConnection(
                        ic, editor, text, InsertionSource.TRANSCRIPTION, sid,
                        /* enableAutoEnter = */ false),
                () -> Toast.makeText(
                        this, R.string.dictate_resend_focus_lost, Toast.LENGTH_SHORT).show(),
                this::startResumeJob);
    }

    /**
     * Short-press resend helper: launches a JobKind.RESUME for the given session.
     * Returns early if another job is already active, showing an informational toast.
     */
    private void startResumeJob(String sessionId) {
        if (ActiveJobRegistry.INSTANCE.isAnyActive()) {
            showJobBusyToast();
            return;
        }
        int remainingSteps = computeRemainingSteps(sessionId);
        JobRequest.Resume request = new JobRequest.Resume(sessionId, remainingSteps);
        // RESUME carve-out (C6-IMPL-2 / C5-IMPL-3) — stays legacy
        // JobExecutor.start, unconditionally.
        //
        // RESUME is the short-press-resend *recovery* path (continue a
        // failed pipeline from its checkpoint) — structurally distinct
        // from a fresh recording. The PipelineRunnerSubsystem interface
        // (Spec 1 §4.9) exposes submit / submitReprocess / cancel but
        // **no resume**; the orchestrator has no resume equivalent, so a
        // regression here loses the user's already-transcribed output.
        // It is single-dispatch and orthogonal to the recording-drive
        // cutover — no user action triggers both this and an orchestrator
        // dispatch(StartRecording/StopRecordingAndSend) (AC-10 holds).
        // Retiring it is an architecture change beyond C7's pure-deletion
        // scope, explicitly out of scope per the C7 carve-out note —
        // owned by a post-cutover block.
        boolean started = JobExecutor.INSTANCE.start(this, request);
        if (!started) {
            showJobBusyToast();
        }
    }

    private void showJobBusyToast() {
        Toast.makeText(this, R.string.dictate_job_already_active, Toast.LENGTH_SHORT).show();
    }

    /**
     * Rough estimate of steps still to run for a Resume job: queued prompts
     * minus completed steps. Used only for progress UI; off-by-one is harmless.
     */
    private int computeRemainingSteps(String sessionId) {
        List<Integer> queuedIds = sessionManager.getHistoricalQueuedPromptIds(sessionId);
        int totalQueued = queuedIds.size();
        int completed = dictateDb.processingStepDao().getCurrentChain(sessionId).size();
        return Math.max(1, totalQueued - completed);
    }

    @Override
    public void onResendLongClicked() {
        // Phase 9.2 — enter ReprocessStaging with the last keyboard session.
        if (uiController.isBusy()) return;

        dbExecutor.execute(() -> {
            SessionEntity lastSession = sessionTracker.getLastKeyboardSession();
            if (lastSession == null) return;

            RecordingRepository.LoadResult result = recordingRepository.loadBySessionId(lastSession.getId());
            if (!(result instanceof RecordingRepository.LoadResult.Available)) {
                mainHandler.post(() -> Toast.makeText(
                        this, R.string.dictate_audio_file_missing, Toast.LENGTH_SHORT).show());
                return;
            }

            List<Integer> historicalQueue = sessionManager.getHistoricalQueuedPromptIds(lastSession.getId());
            mainHandler.post(() -> {
                if (uiController == null) return;
                uiController.enterReprocessStaging(
                        lastSession.getId(),
                        lastSession.getAudioDurationSeconds(),
                        historicalQueue,
                        lastSession.getLanguage());
                updatePromptButtonsEnabledState();
            });
        });
    }

    @Override
    public void onBackspaceClicked() {
        deleteOneCharacter();
    }

    @Override
    public void onBackspaceLongClicked() {
        isDeleting = true;
        startDeleteTime = System.currentTimeMillis();
        currentDeleteDelay = 50;
        deleteRunnable = new Runnable() {
            @Override
            public void run() {
                if (isDeleting) {
                    deleteOneCharacter();
                    long diff = System.currentTimeMillis() - startDeleteTime;
                    if (diff > 1500 && currentDeleteDelay == 50) {
                        vibrate();
                        currentDeleteDelay = 25;
                    } else if (diff > 3000 && currentDeleteDelay == 25) {
                        vibrate();
                        currentDeleteDelay = 10;
                    } else if (diff > 5000 && currentDeleteDelay == 10) {
                        vibrate();
                        currentDeleteDelay = 5;
                    }
                    deleteHandler.postDelayed(this, currentDeleteDelay);
                }
            }
        };
        deleteHandler.post(deleteRunnable);
    }

    @Override
    public void onBackspaceDeleteCancelled() {
        isDeleting = false;
        if (deleteRunnable != null) deleteHandler.removeCallbacks(deleteRunnable);
    }

    @Override
    public void onTrashClicked() {
        // ReprocessStaging: the trash button cancels back to Idle.
        if (uiController != null && uiController.getState() instanceof PipelineUiState.ReprocessStaging) {
            uiController.cancelReprocessStaging();
            updatePromptButtonsEnabledState();
            return;
        }

        cancelEffectiveRecording();
        livePrompt = false;
        updatePromptButtonsEnabledState();
    }

    /**
     * Triggered by the record button press while in ReprocessStaging. Starts a
     * JobKind.REPROCESS_STAGING job with the edited queue (Phase 9.3).
     *
     * W5: The session read is dispatched onto dbExecutor (Room forbids main-
     * thread queries). W4: audio file existence is checked before starting
     * the job, matching startHistoryReprocess so the user gets a specific
     * error instead of a generic pipeline failure.
     */
    private void handleReprocessSend(PipelineUiState.ReprocessStaging staging) {
        // Snapshot UI-owned data on the main thread so the worker sees a
        // stable view — staging is mutable via the UI controller.
        final String targetSessionId = staging.getTargetSessionId();
        final String selectedLanguage = staging.getSelectedLanguage();
        final String selectedModel = staging.getSelectedModel();
        final List<Integer> editableQueue = staging.getEditableQueue();
        final EditorInfo info = getCurrentInputEditorInfo();
        final String targetAppPackage = info != null && info.packageName != null
                ? info.packageName.toString() : null;

        dbExecutor.execute(() -> {
            SessionEntity session = sessionManager.getSessionById(targetSessionId);
            String audioPath = session != null ? session.getAudioFilePath() : null;
            // W4: file-existence check mirrors startHistoryReprocess so the
            // user gets a specific "audio missing" toast instead of a late
            // pipeline failure at the transcription stage.
            boolean missing = audioPath == null || !new File(audioPath).exists();

            mainHandler.post(() -> {
                if (missing) {
                    Toast.makeText(this, R.string.dictate_audio_file_missing, Toast.LENGTH_SHORT).show();
                    return;
                }

                int totalSteps = 1; // transcription always
                if (autoFormattingService.isEnabled()) totalSteps++;
                totalSteps += editableQueue.size();

                // REPROCESS_STAGING routes through the orchestrator's C3
                // PipelineRunnerSubsystemAdapter.submitReprocess (which
                // calls JobExecutor.start internally — that is the adapter,
                // not an IME call-site). The reprocess modelOverride /
                // targetAppPackage / AutoFormatting-+1 are threaded via the
                // ImePipelineConfigResolver reprocess snapshot so the
                // adapter's resolver rebuilds the JobRequest faithfully
                // (C3-IMPL-2). Single-dispatch — no double-run.
                // B2-VAL-W1 F-9 — the not-bound condition is "service not
                // yet ready", NOT "a job is already active". Surface the
                // correct message, consistent with the sibling
                // transcribeImportedAudioFileViaOrchestrator() not-bound
                // bail. showJobBusyToast() stays for the genuine
                // ActiveJobRegistry busy branch below.
                if (pipelineBinder == null || imePipelineConfigResolver == null) {
                    android.widget.Toast.makeText(
                            this, R.string.dictate_service_not_ready,
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                if (ActiveJobRegistry.INSTANCE.isAnyActive()) {
                    showJobBusyToast();
                    return;
                }
                imePipelineConfigResolver.snapshotReprocess(
                        targetSessionId,
                        new ImePipelineConfigResolver.ReprocessConfig(
                                totalSteps, selectedModel, targetAppPackage));
                pipelineBinder.getModuleServices().getPipelineRunner().submitReprocess(
                        targetSessionId,
                        new File(audioPath),
                        editableQueue,
                        selectedLanguage);

                // Transition staging → Preparing → Running via the same path used by
                // the fresh-recording flow (SEC-7-6).
                uiController.preparePipeline();
                boolean autoEnter = DictatePrefsKt.get(sp, Pref.AutoEnter.INSTANCE);
                uiController.startPipeline(totalSteps, new KeyboardUiController.AutoEnterConfig(autoEnter));
            });
        });
    }

    @Override
    public void onPauseClicked() {
        togglePauseEffectiveRecording();
    }

    @Override
    public void onEnterClicked() {
        performEnterAction();
    }

    @Override
    public void onKeyboardToggleClicked() {
        toggleQwertzKeyboard();
    }

    @Override
    public void onKeyboardLongClicked() {
        switchToPreviousKeyboard();
    }

    @Override
    public void onEmojiToggleClicked() {
        toggleEmojiPicker();
    }

    @Override
    public void onEmojiCloseClicked() {
        hideEmojiPicker();
    }

    @Override
    public void onSettingsClicked() {
        if (isEffectiveRecordingInFlight()) {
            cancelEffectiveRecording();
            livePrompt = false;
            updatePromptButtonsEnabledState();
        }
        infoBarController.dismiss();
        openSettingsActivity();
    }

    @Override
    public void onHistoryClicked() {
        Intent intent = new Intent(this, HistoryActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    @Override
    public void onPipelineCancelClicked() {
        // Prefer the JobExecutor-based cancellation (cooperative token + last-resort interrupt).
        // Fall back to the orchestrator's executor.shutdownNow() for legacy code paths that
        // may still be running outside the registry (e.g. standalone prompt from a prompt button).
        String activeSessionId = sessionTracker.getCurrentSessionId();
        PipelineOrchestrator.CancelInfo cancelInfo;
        if (activeSessionId != null && ActiveJobRegistry.INSTANCE.isActive(activeSessionId)) {
            JobExecutor.INSTANCE.cancel(activeSessionId);
            // JobExecutor.finally handles the registry; orchestrator writes CANCELLED itself.
            cancelInfo = new PipelineOrchestrator.CancelInfo(
                    sessionTracker.getCurrentStepId(),
                    sessionTracker.getCurrentTranscriptionId());
        } else if (pipelineOrchestrator != null) {
            // Legacy standalone-prompt path (no Registry entry).
            cancelInfo = pipelineOrchestrator.cancel();
        } else {
            cancelInfo = new PipelineOrchestrator.CancelInfo(null, null);
        }

        pendingLivePromptChain = false;

        uiController.stopPipeline();
        uiController.restoreRecordButtonIdle(
            getDictateButtonText(),
            R.drawable.ic_baseline_mic_20,
            R.drawable.ic_baseline_folder_open_20);

        dbExecutor.execute(() -> {
            String lastOutput = null;
            if (cancelInfo.getLastStepId() != null) {
                lastOutput = sessionManager.getStepOutput(cancelInfo.getLastStepId());
            } else if (cancelInfo.getLastTranscriptionId() != null) {
                lastOutput = sessionManager.getTranscriptionText(cancelInfo.getLastTranscriptionId());
            }

            sessionTracker.clearCurrent();

            if (lastOutput != null) {
                String finalOutput = lastOutput;
                mainHandler.post(() -> commitTextToInputConnection(finalOutput, InsertionSource.TRANSCRIPTION));
            }
        });
    }

    @Override
    public void onSmallModeToggled() {
        boolean newSmallMode = !stateManager.isSmallMode();
        DictatePrefsKt.put(sp.edit(), Pref.SmallMode.INSTANCE, newSmallMode).apply();
        stateManager.setSmallMode(newSmallMode);
        mainButtonsController.animateSmallModeToggle(true);
    }

    @Override
    public void onSingleRowModeToggled() {
        // Block 1 / Chunk 3 (Plan-Z. 235-241). Order:
        //   1. flip + persist the pref
        //   2. let the controller swap ConstraintSets + re-parent (animated
        //      iff Pref.Animations is true)
        //   3. play the editNumbersButton bounce as visual feedback
        //   4. KSM.refresh() — Block-1a Quick-Win (Spec 1 §11.2.2 step 3):
        //      the layout-mode controller's setSingleRowMode is structural
        //      (ConstraintSet swap + re-parent) but does NOT recompute the
        //      visibility axes owned by KeyboardStateManager. Without an
        //      explicit refresh the action-row / input-row visibility could
        //      lag a frame behind the pref-flip on the next state change.
        //
        // SmallMode-Vorrang (Plan-Z. 222-229): when SmallMode is on the
        // entire main_buttons_cl is GONE, so step 2 is invisible; the pref
        // still persists and takes effect as soon as SmallMode is toggled
        // off. Same for QWERTZ ContentArea — applyVisibility() will
        // re-call refresh() on the controller when MAIN_BUTTONS becomes
        // active again.
        boolean current = DictatePrefsKt.get(sp, Pref.SingleRowMode.INSTANCE);
        boolean next = !current;
        DictatePrefsKt.put(sp.edit(), Pref.SingleRowMode.INSTANCE, next).apply();
        // C15 — KeyboardLayoutModeController is gone. The Pref-write above
        // is mirrored into the orchestrator state via PipelinePrefMirror;
        // the attached ImeViewBackend re-renders and asks MotionLayout to
        // transition to the new scene-id. No direct controller call needed.
        if (mainButtonsController != null) {
            mainButtonsController.animateEditNumbersBounce();
        }
        if (stateManager != null) {
            stateManager.refresh();
        }
    }

    @Override
    public void onAudioFocusToggled() {
        // Block 2 (Quality-Gate W "Race Window"): the order
        //   1. SP-write   2. live-hook   3. icon refresh   4. KSM.refresh
        // matters. Other components reading Pref.AudioFocus on a trigger (the
        // SP listener registered above, the next startRecording() pass) must
        // see the new value already; the icon only follows after the runtime
        // state has been adjusted so a torn frame cannot show stale state.
        boolean newValue = !DictatePrefsKt.get(sp, Pref.AudioFocus.INSTANCE);

        // 1. Persist FIRST.
        DictatePrefsKt.put(sp.edit(), Pref.AudioFocus.INSTANCE, newValue).apply();

        // 2. Live-Hook on a running recording (no-op in Idle, only mutates
        //    AudioManager when state is Active — see RecordingStateController
        //    .setAudioFocusRuntime KDoc). Also covers Block 3c.
        if (recordingStateController != null) {
            recordingStateController.setAudioFocusRuntime(newValue);
        }

        // 3. UI refresh — both buttons synced via refreshAudioFocusIcon.
        if (mainButtonsController != null) {
            mainButtonsController.refreshAudioFocusIcon(newValue);
        }

        // 4. Block-1a Quick-Win (Spec 1 §11.2.2 step 3): the audio-focus
        //    toggle does not directly change any KSM-owned axis today, but
        //    downstream visibility resolvers may consult the pref via the
        //    `isRecording`/`isPaused` lambdas in a future iteration. Adding
        //    the refresh now keeps the toggle on the same "user-action ⇒
        //    refresh" rhythm as setSmallMode / onSingleRowModeToggled and
        //    eliminates the off-by-one frame the plan §11.2.2 step 3 calls
        //    out (the icon update would otherwise reach the screen one
        //    layout-pass before any KSM-driven downstream visibility).
        if (stateManager != null) {
            stateManager.refresh();
        }
    }

    @Override
    public void onEditAction(int actionId) {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection != null) {
            inputConnection.performContextMenuAction(actionId);
        }
    }

}
