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
import android.inputmethodservice.InputMethodService;
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
import net.devemperor.dictate.state.render.ContentAreaController;
import net.devemperor.dictate.state.render.ContentAreaViews;
import net.devemperor.dictate.state.render.EditBarController;
import net.devemperor.dictate.state.render.EditBarViews;
import net.devemperor.dictate.state.render.EffectiveNightModeKt;
import net.devemperor.dictate.state.render.EmojiController;
import net.devemperor.dictate.state.render.EmojiViews;
import net.devemperor.dictate.state.render.ImeViewBackend;
import net.devemperor.dictate.state.render.OverlayCharactersController;
import net.devemperor.dictate.state.render.OverlayCharactersViews;
import net.devemperor.dictate.state.render.OverlayResetHandler;
import net.devemperor.dictate.state.render.OverlayResetViews;
import net.devemperor.dictate.state.render.PipelineStepRowRenderer;
import net.devemperor.dictate.state.render.QwertzRecordingController;
import net.devemperor.dictate.state.render.PromptVisibilityController;
import net.devemperor.dictate.state.render.PromptVisibilityViews;
import net.devemperor.dictate.state.render.RealMotionSurface;
import net.devemperor.dictate.state.render.RecordingAnimationController;
import net.devemperor.dictate.state.render.RenderGate;
import net.devemperor.dictate.state.render.SpecialTouchHandlerInstaller;
import net.devemperor.dictate.state.insertion.AutoEnterScheduler;
import net.devemperor.dictate.state.insertion.ClipboardGateway;
import net.devemperor.dictate.state.insertion.ControlOp;
import net.devemperor.dictate.state.insertion.EditAction;
import net.devemperor.dictate.state.insertion.HostSelection;
import net.devemperor.dictate.state.insertion.HostTarget;
import net.devemperor.dictate.state.insertion.HostTextReader;
import net.devemperor.dictate.state.insertion.InsertionAuditLog;
import net.devemperor.dictate.state.insertion.InsertionPolicy;
import net.devemperor.dictate.state.insertion.InsertionRequest;
import net.devemperor.dictate.state.insertion.InsertionResult;
import net.devemperor.dictate.state.insertion.InsertionService;
import net.devemperor.dictate.state.insertion.RecoveryHandler;
import net.devemperor.dictate.state.insertion.SlowOutputAnimator;
import net.devemperor.dictate.state.layout.EnterButtonRole;

import androidx.constraintlayout.motion.widget.MotionLayout;
import java.util.HashMap;
import java.util.Map;

import androidx.room.InvalidationTracker;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// MAIN CLASS
public class DictateInputMethodService extends InputMethodService
        implements PromptQueueManager.PromptQueueCallback,
                   PipelineOrchestrator.PipelineCallback,
                   // CR-DEL — MainButtonsController.Callback removed
                   // (MainButtonsController deleted). The button-action
                   // method bodies stay as plain IME methods: the
                   // EditBarController/EmojiController callbacks (strict
                   // ISP subsets) keep their @Override, and the
                   // RECORD/RESEND/BACKSPACE/TRASH/PAUSE/ENTER methods are
                   // invoked via the ImeViewBackend imeSideAffordance hook
                   // + SpecialTouchHandlerInstaller (no interface needed).
                   EditBarController.Callback,
                   EmojiController.Callback {


    // Engagement-hint confirm targets (2026-07-02, ADR-0006 completion —
    // carried over from the deleted legacy info-bar controller handlers).
    private static final String PLAY_STORE_URL =
            "https://play.google.com/store/apps/details?id=net.devemperor.dictate";
    private static final String DONATE_URL = "https://paypal.me/DevEmperor";

    private Handler mainHandler;
    private Handler deleteHandler;
    private Runnable deleteRunnable;

    // define variables and objects
    private boolean isDeleting = false;
    private long startDeleteTime = 0;
    // Initial value comes from the pure-Kotlin speed-curve helper so
    // there is exactly one source of truth for the 50→25→10→5 ms cascade
    // (BackspaceDeleteSpeedCurve). The cascade thresholds + step sizes
    // are covered by BackspaceLongPressIntegrationTest.
    private int currentDeleteDelay = BackspaceDeleteSpeedCurve.INITIAL_DELAY_MS;
    private boolean livePrompt = false;
    private volatile boolean pendingLivePromptChain = false; // true when transcription result should be chained into live prompt
    // ADR-0013: non-null while a review-refinement recording (S2) is in flight;
    // holds the S1 conversation session the resulting transcript continues.
    private volatile String reviewRefinementTargetSessionId = null;
    private net.devemperor.dictate.state.render.ReviewPanelRenderer reviewPanelRenderer;

    // ADR-0014 — in-keyboard history panel (renderer + IME-owned Paging lifecycle).
    private net.devemperor.dictate.state.render.HistoryPanelRenderer historyPanelRenderer;
    private net.devemperor.dictate.history.KeyboardHistoryController historyController;
    private net.devemperor.dictate.history.KeyboardHistoryAdapter historyAdapter;
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
     * Captured in {@link #cleanupOldControllers()} when the active pipeline
     * phase is in staging, consumed (and reset to null) in
     * {@link #restoreUiState()} by re-entering the staging mode on the
     * fresh controller. Without this bridge, a rotation or theme change
     * during staging drops the user's edited queue/language silently back
     * to Idle.
     *
     * <p>Post-Phase-5.B (Vol 2): the orchestrator's
     * {@code state.PipelineUiState.ReprocessStaging} only carries
     * {@code (sessionId, transcript)} — the auxiliary fields
     * (audio duration, editable queue, selected language, selected model)
     * are mirrored into dedicated IME fields below
     * ({@link #reprocessAudioDurationSeconds},
     * {@link #reprocessEditableQueue}, {@link #reprocessSelectedLanguage},
     * {@link #reprocessSelectedModel}). The restoreReprocessStaging-flag
     * field now signals "we were staging" while the field-snapshots carry
     * the data; the flag is captured/consumed identically to the
     * pre-Phase-5.B shape.</p>
     */
    private boolean restoreReprocessStaging = false;

    /**
     * Phase 5.B of `2026-05-21 - dictate-render-cutover-completion-vol2`:
     * IME-Java mirror of the legacy
     * {@code core.PipelineUiState.ReprocessStaging.audioDurationSeconds}
     * payload, which the orchestrator's `state.PipelineUiState.ReprocessStaging`
     * does not carry. Written by {@link #enterReprocessStagingFromSession}
     * (and the restore-from-view-recreate path), read by the View-side
     * staging UI helpers.
     */
    private long reprocessAudioDurationSeconds = 0L;

    /**
     * Phase 5.B IME-Java mirror of the legacy
     * {@code core.PipelineUiState.ReprocessStaging.editableQueue} payload.
     * Holds the user-editable prompt-id queue while in ReprocessStaging.
     */
    private List<Integer> reprocessEditableQueue = new ArrayList<>();

    /**
     * Phase 5.B IME-Java mirror of the legacy
     * {@code core.PipelineUiState.ReprocessStaging.selectedLanguage} payload.
     * Holds the staging-scoped language override (separate from the
     * orchestrator's permanent {@code LanguageState.override}).
     */
    private String reprocessSelectedLanguage = null;

    /**
     * Phase 5.B IME-Java mirror of the legacy
     * {@code core.PipelineUiState.ReprocessStaging.selectedModel} payload
     * (forward-compat for the future model-selector chip).
     */
    private String reprocessSelectedModel = null;

    /**
     * Phase 5.B IME-Java mirror of the legacy
     * {@code core.PipelineUiState.ReprocessStaging.targetSessionId} payload —
     * the session-id of the recording being reprocessed.
     */
    private String reprocessTargetSessionId = null;

    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private PipelineOrchestrator pipelineOrchestrator;
    // CR-DEL (Theme C-R / C10-C3) — KeyboardUiController DELETED; the
    // pipeline-progress step-row UI + PipelineUiState machinery (Spec 1
    // §9.2 "stepRows bleibt View-side" BLEIBT) is relocated into
    // PipelineStepRowRenderer (A3 option-a, the binding CR3 disposition).
    private PipelineStepRowRenderer pipelineStepRowRenderer;

    /**
     * D-13 (Epic §4 Block C1): the per-view legacy language-controller
     * field was deleted. The permanent language SoT is the static
     * {@link net.devemperor.dictate.preferences.LanguageResolver}
     * (reads/writes the same SharedPreferences keys, no cache); the
     * ReprocessStaging override is the {@code LanguageState.override}
     * axis, written via {@code LanguageAction.SetOverride}. The IME
     * resolves the effective code via {@link #resolveEffectiveLanguage()}
     * and pushes it to the bound orchestrator via
     * {@link #pushPermanentLanguageToOrchestrator()} on the
     * boot-cold-start path (Pre-Dispatch-Resolution, Spec 1 §4.11).
     *
     * <p>Cross-instance refresh (post Chunk 4.5b): the custom
     * {@code inputLanguagesListener} SP-listener is REMOVED. External
     * Settings-Activity writes to {@code input_languages} /
     * {@code input_language_pos} are picked up by the
     * {@code PipelinePrefMirror} computed-mirror (Chunk 4.5a) which
     * resolves them to {@code state.language.effective}; the
     * {@link LanguageEffectiveObserver} below re-runs
     * {@link #refreshLanguageChip()} on the state-emit. No private
     * listener field, no per-view register/unregister — gone.
     */
    private LanguageEffectiveObserver languageEffectiveObserver;

    // 2026-05-21 indirection-cleanup Chunk 3.5 (C-3) — the custom
    // `audioFocusListener` SP-listener is REMOVED. PipelinePrefMirror
    // now owns the full SP → state.audio.audioFocusEnabledPref mirror;
    // the EditBarAudioFocusObserver (Chunk 3.3) feeds the edit-bar twin;
    // the AudioModule.onCrossModuleStateChange cascade emits
    // `ApplyAudioFocusRuntimeFromPref` for mid-recording external SP
    // writes (Chunk 3.5). No private listener field, no listener
    // registration, no unregistration — the indirection is gone.

    /**
     * Phase 5.B of `2026-05-21 - dictate-render-cutover-completion-vol2`:
     * Service-side pipeline-state observer. Subscribes to
     * {@code state.pipeline} (distinctUntilChanged) and drives the
     * IME-side side-effects that the legacy
     * {@code PipelineStepRowRenderer.addCallback(PipelineUiCallback)}
     * mechanic previously delivered (queue-order sync, language-chip
     * enable, language-chip refresh, QWERTZ rec-button updates). The
     * reactive {@link net.devemperor.dictate.state.render.PipelineStepRowRenderer}
     * owns step-row rendering directly via {@code onState}; this observer
     * carries only the non-renderer responsibilities.
     */
    private PipelineUiStateObserver pipelineUiStateObserver;
    private Vibrator vibrator;
    private SharedPreferences sp;
    private AudioManager am;
    private AudioFocusRequest audioFocusRequest;

    // Managers (extracted from God-Class)
    private RecordingManager recordingManager;
    private BluetoothScoManager bluetoothScoManager;
    private PromptQueueManager promptQueueManager;
    // CR-DEL — KeyboardStateManager + MainButtonsController DELETED (their
    // axes owned by the armed CR3 controllers / catalog resolvers /
    // ImeViewBackend / EditBar+Emoji+OverlayChars owners — RR-3 trace
    // PASS). No unbound fallback (point-of-no-return).
    //
    // 2026-07-02 (ADR-0006 completion) — the legacy imperative info-bar controller
    // is DELETED too: pipeline errors + Update/Rate/Donate hints now
    // surface as state (state.infoHints, InfoHintModule) and render
    // through the single state-driven InfoBarRenderer below.

    // The single info-bar container (`infobar_cl` + message + two
    // buttons). The IME owns this view surface rather than
    // ImeViewBackend (documented deviation, ADR-0005 Decision-History
    // 2026-05-15).
    private View infoBarContainer;
    // 2026-05-21 ADR-0006 — state-driven info-bar renderer. Observes
    // `InfoBarSelector.select(state)` and renders the top item into the
    // `infobar_*` views (container + text + two buttons). Since
    // 2026-07-02 this is the ONLY info-bar surface — the legacy
    // info-bar controller + its second container are gone.
    private net.devemperor.dictate.state.infobar.InfoBarRenderer infoBarRenderer;

    // 2026-05-21 indirection-cleanup Chunk 3.3 — reactive bridge for the
    // edit-bar audio-focus-twin (`editAudioFocusButton`). Listens to
    // `state.audio.audioFocusEnabledPref` and forwards to
    // `EditBarController.refreshAudioFocusIcon(...)`. Replaces the
    // imperative refresh paths in `audioFocusListener` + the
    // post-toggle call inside `onAudioFocusToggled` (both removed in
    // Chunks 3.4 / 3.5).
    private EditBarAudioFocusObserver editBarAudioFocusObserver;
    /**
     * 2026-05-22 — reactive observer for state.layout.smallMode so the
     * edit_numbers_btn rotation tracks the SoT even when the state
     * changes outside onSmallModeToggled (e.g. SetContentArea auto-exit).
     */
    private EditNumbersSmallModeObserver editNumbersSmallModeObserver;

    // dictate-pipeline-render-and-state-unification §5.7 — reactive
    // bridge for the prompt-chips disable-bit. Listens to a derived
    // boolean over `state.recording` + `state.pipeline` and fires
    // `updatePromptButtonsEnabledState()` when the bit flips
    // (distinctUntilChanged). Replaces the legacy read of
    // `recordingStateController.getState()` which post-cutover stayed
    // permanently Idle (B-E regression). The observer is the IME-side
    // half of the AC-P-1 single-source-of-truth invariant: the disable
    // bit follows the orchestrator state, not the legacy controller.
    private PromptChipsBusyObserver promptChipsBusyObserver;

    // 2026-07-11 — the per-second TickPipelineTimer ticker and the
    // recording-animation ticker are SERVICE-OWNED (external-start
    // incident fix; see DictatePipelineService onCreate Step 8b). The
    // IME registers per-tick sinks via
    // LocalBinder.registerRecordingTickSinks instead of constructing
    // observers here.

    // Post-cutover hotfix #AMP — normalizes the raw 0..32767 MediaRecorder
    // amplitude into the 0..1 contract that AmplitudeVisualizerDrawable /
    // BorderGlowAnimation expect (log-normalize + asymmetric EMA). Without
    // this step the waveform pegs at maximum on every tick because the
    // raw int gets float-casted and immediately clamped to 1.0 by the
    // renderer. The cutover dropped the legacy
    // RecordingStateController.onAmplitudeUpdate normalization hop; this
    // reintroduces it on the new ticker-driven path. Separate instance
    // from recordingStateController's processor — that one is on the
    // dormant legacy path and would mix EMA state between two recorders.
    private final AmplitudeProcessor recordingTickerAmplitudeProcessor = new AmplitudeProcessor();

    // C15 — New keyboard-layout render path (Spec 2 §11.8 5c). Constructed in
    // onCreateInputView() once the View tree is inflated; attached to the
    // service-side KeyboardLayoutManager via the LocalBinder. Detached in
    // onDestroyInputView() / onDestroy().
    private ImeViewBackend imeViewBackend;
    // CR2 (Theme C-R) — builds the three Spec 2 §11.7 special-touch
    // handlers (SPACE/BACKSPACE/ENTER) but keeps them DORMANT (built, not
    // attached) until CR4. RR-1: the legacy MainButtonsController is the
    // sole LIVE touch owner of these Views until CR4 calls
    // attachToViews(...) in the same chunk it removes the legacy wiring.
    private SpecialTouchHandlerInstaller specialTouchHandlerInstaller;
    private KeyboardLayoutManager keyboardLayoutManager; // copy of the service-side instance, for detach

    // The three R.10 visibility controllers, attached via
    // KeyboardLayoutManager.attachBackend and armed as the SOLE LIVE
    // writers of these axes post-CR-DEL. Historical (render-path-cutover.md
    // §6 RR-2): first attached gated-dormant in CR3 while the legacy
    // `KeyboardStateManager` still drove these axes, armed in CR4 as
    // that drive was removed, then KSM deleted in CR-DEL — no fallback
    // writer remains. The gate fields are retained (arm()ed; detach
    // stays symmetric). backendType=null multi-backends (ambiguity A4 —
    // follow parent-B4 design).
    private ContentAreaController contentAreaController;
    private PromptVisibilityController promptVisibilityController;
    private OverlayResetHandler overlayResetHandler;
    private RenderGate contentAreaGate;
    private RenderGate promptVisibilityGate;
    private RenderGate overlayResetGate;

    // CR-EXTRACT (Theme C-R) — the three §13.2-prescribed-but-never-
    // created owners (CR4-IMPL-1 resolution). Built BUILD-BUT-DORMANT:
    // they exist + are attached at the consolidation point but the
    // legacy MainButtonsController.registerEditBarListeners /
    // registerEmojiListeners / initializeOverlayCharacters stay the
    // SOLE LIVE owner until CR4 flips per-axis atomically
    // (attachToViews()/arm() in the same chunk it removes
    // registerAllListeners(), never both wired at once — RR-1/RR-2).
    // The overlay-chars owner uses a RenderGate (write axis, CR3
    // pattern) constructed dormant; CR4 arm()s it.
    private EditBarController editBarController;

    /**
     * Single owner of every host {@link InputConnection} write (insertion
     * unification). Built lazily on first use via {@link #insertionService()}
     * because its collaborators close over service state set up in onCreate.
     */
    private InsertionService insertionService;
    private EmojiController emojiController;
    private OverlayCharactersController overlayCharactersController;
    private RenderGate overlayCharactersGate;

    // CR4 (Theme C-R / G15, render-path-cutover.md §3 / §7 A2) — the
    // edit-numbers animation is now owned by an IME-held
    // EditNumbersAnimator (CR1 extracted the helper; MainButtonsController
    // was a thin delegate). CR4 re-points the IME call-sites
    // (onSmallModeToggled / onSingleRowModeToggled / onStartInputView) to
    // this field and removes the mainButtonsController delegation drive.
    // Rebuilt against the fresh tree alongside the other CR-EXTRACT
    // owners (it holds a direct View reference).
    private net.devemperor.dictate.core.EditNumbersAnimator editNumbersAnimator;

    // Recording controllers (extracted from God-Class)
    private RecordingStateController recordingStateController;
    // CR-DEL — RecordingUiController DELETED; its recording-axis
    // Main-button side-effects are dead on the bound path (legacy
    // controller never started, C5) and collapsed onto
    // RecordingAnimationController + catalog resolvers + predResendVisible.
    // The still-live QWERTZ rec-button + prompts-visualizer (G9 BLEIBT,
    // Spec 2 §9.4) is extracted into QwertzRecordingController.
    private QwertzRecordingController qwertzRecordingController;

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
    // ADR-0009 secondary-recording mic button (rendered in SEND_MODE only).
    private MaterialButton secondaryRecordButton;
    private MaterialButton backspaceButton;
    private MaterialButton trashButton;
    private MaterialButton spaceButton;
    private MaterialButton pauseButton;
    private MaterialButton enterButton;
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
    // 2026-05-22 — edit-bar widget-toggle (relocated from the main action
    // row's widget_toggle_btn slot; see EditBarController.Callback
    // .onWidgetToggleClicked).
    private MaterialButton editWidgetToggleButton;
    private FrameLayout qwertzContainer;
    private QwertzKeyboardView qwertzKeyboardView;
    private QwertzKeyboardController qwertzController;
    private LinearLayout overlayCharactersLl;

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
            // are picked up by the PipelinePrefMirror computed-mirror
            // (Chunk 4.5a) and reflected via the
            // LanguageEffectiveObserver (Chunk 4.5b).
            pushPermanentLanguageToOrchestrator();
            // Post-cutover hotfix #R3 — close the bind→reload race.
            // setupPromptsAdapter → reloadPrompts may have fired during
            // onCreateInputView while promptQueueManager was still null;
            // the gate in reloadPrompts() early-returns in that case so
            // the prompts data array stays empty and only the
            // Language-Chip shows. Kick the reload now that the binder
            // (and therefore promptQueueManager via
            // bindAiInfrastructureFromService) is in place. Idempotent
            // on re-binds.
            if (promptsAdapter != null) {
                reloadPrompts();
            }
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
                    // 2026-05-21 indirection-cleanup Chunk 3.6 (C-1) —
                    // route AudioManager focus events through the
                    // orchestrator instead of bypassing it with a direct
                    // `recordingStateController.togglePause()`.
                    //
                    // NOTE (F-036/F-007 consolidation, 2026-07-02): this
                    // IME-side listener is effectively dead — its
                    // AudioFocusRequest is only requested by the
                    // never-started legacy RecordingStateController.
                    // The live interruption authority is the FGS-side
                    // AudioFocusChangeClassifier → InterruptionModule
                    // (pause cascade); AudioModule keeps focus grant as
                    // bookkeeping only. Even if this listener fired, it
                    // would only update that bookkeeping. Removal belongs
                    // to the RecordingStateController retirement.
                    //
                    // **Legacy parity contract:** the legacy code only
                    // paused on full AUDIOFOCUS_LOSS — transient losses
                    // (LOSS_TRANSIENT / LOSS_TRANSIENT_CAN_DUCK) were
                    // ignored, and GAIN/etc were no-ops. We preserve
                    // that exactly: dispatch `granted=false` only on
                    // hard LOSS; dispatch `granted=true` on any GAIN
                    // variant. Transient LOSS variants are deliberately
                    // not dispatched (the AudioModule state already
                    // reflects the prior grant state — re-dispatching
                    // `true` would be redundant; the reducer rejects
                    // the same value as null per its idempotency
                    // contract).
                    if (pipelineBinder == null) return;  // pre-bind no-op
                    Boolean grantedOrNull;
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                        grantedOrNull = Boolean.FALSE;
                    } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN
                            || focusChange == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                            || focusChange == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                            || focusChange == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE) {
                        grantedOrNull = Boolean.TRUE;
                    } else {
                        // LOSS_TRANSIENT / LOSS_TRANSIENT_CAN_DUCK — legacy
                        // no-op, do not dispatch.
                        grantedOrNull = null;
                    }
                    if (grantedOrNull != null) {
                        pipelineBinder.dispatch(
                                new net.devemperor.dictate.state.Action.AudioAction.OnAudioFocusGrantChanged(
                                        grantedOrNull));
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
        // P4 cross-service delegate: register the IME's single
        // InsertionService so KeyboardInputModule effects (Space/Backspace/
        // Enter/PhysicalEnter) route their host writes through the same
        // owner the IME-side controllers use. Cleared on unbind below.
        binder.registerInsertionServiceProvider(() -> insertionService());

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
            binder.registerInsertionServiceProvider(null);
            binder.registerPipelineConfigResolver(null);
            // dictate-widget-integration §8.3 Chunk 3.2 — clear the
            // overlay affordance lambda so a click that races the unbind
            // becomes a no-op (the OverlayBackend reads the lambda via
            // the binder field at click time).
            binder.registerImeSideAffordance(null);
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
        Log.i("DictateTrace", "IME.onCreateInputView() bindAttempted=" + pipelineServiceBindAttempted
                + " binder=" + (pipelineBinder != null));
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
        recordButton = dictateKeyboardView.findViewById(R.id.record_btn);
        resendButton = dictateKeyboardView.findViewById(R.id.resend_btn);
        secondaryRecordButton = dictateKeyboardView.findViewById(R.id.secondary_record_btn);
        backspaceButton = dictateKeyboardView.findViewById(R.id.backspace_btn);
        trashButton = dictateKeyboardView.findViewById(R.id.trash_btn);
        spaceButton = dictateKeyboardView.findViewById(R.id.space_btn);
        pauseButton = dictateKeyboardView.findViewById(R.id.pause_btn);
        enterButton = dictateKeyboardView.findViewById(R.id.enter_btn);

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
        editWidgetToggleButton = dictateKeyboardView.findViewById(R.id.edit_widget_toggle_btn);
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
            // P4: the single InsertionService owner for all QWERTZ host-IC
            // writes (char / space / cursor-move). Lazy getter, null-safe.
            () -> insertionService(),
            () -> { vibrate(); return kotlin.Unit.INSTANCE; },
            () -> { deleteOneCharacter(); return kotlin.Unit.INSTANCE; },
            () -> {
                // Plan: dictate-enter-button-host-action Chunk 4 —
                // QWERTZ-Enter goes through the same orchestrator
                // dispatch the Catalog ENTER slot uses, so both paths
                // share one source of truth (HostEditorState → Role →
                // PerformEnter Effect). Pre-bind window (rare but real
                // — service-bind is async): fall back to a physical
                // KEYCODE_ENTER so the keystroke is not silently lost.
                if (pipelineBinder != null) {
                    pipelineBinder.dispatch(
                            net.devemperor.dictate.state.Action.KeyboardInputAction.EnterKey.INSTANCE);
                } else {
                    sendPhysicalEnterFallback();
                }
                return kotlin.Unit.INSTANCE;
            },
            () -> { hideQwertzKeyboard(); return kotlin.Unit.INSTANCE; },
            () -> { onRecordClicked(); return kotlin.Unit.INSTANCE; },
            () -> {
                // Re-apply recording/pipeline icon after layout rebuild (shift toggle, layout switch).
                // Phase 5.B: read the pipeline phase off the orchestrator state (single SoT).
                if (qwertzRecordingController != null && recordingStateController != null) {
                    net.devemperor.dictate.state.PipelineUiState phase = getPipelinePhase();
                    if (phase instanceof net.devemperor.dictate.state.PipelineUiState.Running) {
                        // Pipeline active — layout rebuild: we need a fresh one-shot setup AND
                        // an immediate timer text update so the button isn't stale until the next tick.
                        net.devemperor.dictate.state.PipelineUiState.Running s =
                                (net.devemperor.dictate.state.PipelineUiState.Running) phase;
                        qwertzRecordingController.enterPipelineDisplay(s);
                        qwertzRecordingController.updatePipelineTimer(s, s.getElapsedMs());
                    } else {
                        qwertzRecordingController.updateQwertzRecButton(
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
        // 2026-05-21 ADR-0006 / 2026-07-02 completion — the info-bar
        // surface is fully state-driven. InfoBarRenderer subscribes to
        // `InfoBarSelector.select(state)` and renders the top item into
        // the single `infobar_*` container (text + two buttons). Button
        // click listeners are owned by InfoBarRenderer; the legacy
        // imperative info-bar controller (update/rate/donate + error
        // cases) is deleted — its triggers live on state.infoHints.
        //
        // Activity-launch side-channel: some dispatched actions also
        // need an Activity launch (overlay-permission settings, app
        // settings, billing / Play-Store / PayPal pages). This is
        // unavoidable imperative because reducers cannot startActivity
        // (the launcher seam has to live somewhere with a real Context
        // — the IME service IS that context). ADR-0005 Decision-History
        // 2026-05-15 acknowledges this seam; see
        // launchInfoBarSideChannel(...).
        infoBarContainer = dictateKeyboardView.findViewById(R.id.infobar_cl);
        infoBarRenderer = null;  // built below if all views inflated

        // History button
        editHistoryButton = dictateKeyboardView.findViewById(R.id.edit_history_btn);

        // Block 2: audio-focus toggle buttons (Edit-Bar + Single-Row variant).
        editAudioFocusButton = dictateKeyboardView.findViewById(R.id.edit_audio_focus_btn);
        audioFocusButton = dictateKeyboardView.findViewById(R.id.audio_focus_btn);

        View pipelineProgressLl = dictateKeyboardView.findViewById(R.id.pipeline_progress_ll);

        // CR-DEL (Theme C-R / C10-C3) — KeyboardStateManager DELETED.
        // Its visibility axes are owned by the armed CR3 controllers
        // (ContentAreaController / PromptVisibilityController /
        // OverlayResetHandler) + the catalog PAUSE enabledResolver/
        // alphaResolver, all state-reactive on the bound path. There is
        // no unbound fallback any more (the drive-call rollback surface
        // collapses at the point-of-no-return — chunks.json CR-DEL /
        // CR-RGATE GREEN §4). KeyboardViews removed with the class.

        // CR-DEL — KeyboardUiController DELETED; its pipeline-progress
        // step-row UI + PipelineUiState machinery (the Spec 1 §9.2
        // "stepRows bleibt View-side" BLEIBT) is relocated verbatim into
        // PipelineStepRowRenderer (state.render package, A3 option-a —
        // the binding CR3 disposition so AC-RR-7 zero-greps clean). The
        // onPipelineUiStateChanged hook replaces the legacy
        // stateManager.refresh() — a no-op on the bound path (prompts /
        // pipeline-progress container visibility is owned reactively by
        // the armed PromptVisibilityController off the orchestrator's
        // state.pipeline; the renderer owns only the step-row CONTENT +
        // the record-button-from-pipeline-state, not container
        // visibility).
        // Phase 5.B (Vol 2): PipelineStepRowRenderer is now a reactive
        // consumer driven by `ImeViewBackend.render` via `onState`. The
        // legacy callback-shape ctor args (Unit-lambda + dictate-button-text
        // provider) are gone — the renderer reads `state.pipeline.stepHistory`
        // directly off `DictateUiState`.
        pipelineStepRowRenderer = new PipelineStepRowRenderer(new PipelineStepRowRenderer.PipelineViews(
            dictateKeyboardView.findViewById(R.id.pipeline_steps_container),
            dictateKeyboardView.findViewById(R.id.pipeline_scroll_view),
            recordButton,
            LayoutInflater.from(context),
            mainHandler
        ));

        StaggeredGridLayoutManager promptsLayoutManager =
                new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.HORIZONTAL);
        promptsLayoutManager.setGapStrategy(StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS);
        promptsRv.setLayoutManager(promptsLayoutManager);

        // CR-DEL (Theme C-R / C10-C3 / AC-RR-6+AC-RR-7 = Epic AC-7+AC-10
        // render half) — MainButtonsController DELETED. Every render axis
        // it owned has a verified-present, IME-attached new owner (RR-3
        // per-class trace, PASS): ImeViewBackend.wireStaticHandlers (click
        // + long-press + key-press-animation), SpecialTouchHandlerInstaller
        // (SPACE/BACKSPACE/ENTER touch), EditBarController/EmojiController
        // (edit-bar/emoji listeners + the edit-row applyTheme residual),
        // OverlayCharactersController (overlay-chars strip), the catalog
        // AUDIO_FOCUS iconResolver + RECORD textResolver (state-reactive),
        // ImeViewBackend.applyTheme (8 logical buttons), the IME-held
        // EditNumbersAnimator (small-mode/bounce). There is NO unbound
        // fallback any more — the drive-call rollback surface (§6.1
        // staged-safety-net) collapses at the point-of-no-return
        // (chunks.json CR-DEL / CR-RGATE GREEN §4). The keyboard always
        // binds while open (bindService in onCreateInputView;
        // onServiceConnected re-runs the consolidation point).

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

        // CR-DEL (Theme C-R / C10-C3 / G9 BLEIBT, A3 option-a) —
        // RecordingUiController DELETED. Its recording-axis Main-button
        // side-effects (record/pause/resend appearance, BorderGlow
        // animation, resend-visibility) are DEAD on the bound path: the
        // legacy recordingStateController is never started on the new path
        // (C5; isEffectiveRecordingIdle KDoc) and the orchestrator's
        // RecordingModule + RecordingAnimationController + the catalog
        // resolvers + predResendVisible own them reactively. The only
        // still-LIVE responsibility — the QWERTZ rec-button +
        // prompts-visualizer (Spec 2 §9.4 "bleibt — QWERTZ-Bereich ist
        // orthogonal, eigener Controller") — is extracted verbatim into
        // QwertzRecordingController (the binding CR3 A3 option-a so the
        // kill-list class deletes and AC-RR-7 zero-greps clean).
        qwertzRecordingController = new QwertzRecordingController(
            context,
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
        // 2026-05-21 indirection-cleanup Chunk 4.5b — the custom
        // `inputLanguagesListener` SP-listener is GONE. PipelinePrefMirror
        // computed-mirrors Pref.InputLanguages/InputLanguagePos →
        // state.language.effective (Chunk 4.5a); the
        // LanguageEffectiveObserver re-renders the chip on the state-emit.
        // Start the observer here (after the binder is non-null + the
        // chip view is inflated; same site as the other state observers).
        if (languageEffectiveObserver != null) {
            languageEffectiveObserver.stop();
        }
        if (pipelineBinder != null) {
            languageEffectiveObserver = new LanguageEffectiveObserver(
                    pipelineBinder.getState(),
                    effective -> refreshLanguageChip());
            languageEffectiveObserver.start();
        }

        // 2026-05-21 indirection-cleanup Chunk 3.5 (C-3) — the custom
        // `audioFocusListener` SP-listener registration is REMOVED.
        // External Settings-Activity writes to Pref.AudioFocus flow via:
        //   1. PipelinePrefMirror → state.audio.audioFocusEnabledPref
        //   2. EditBarAudioFocusObserver re-renders the edit-bar twin
        //      (Chunk 3.3); the catalog AUDIO_FOCUS slot iconResolver
        //      re-renders the main-button-area twin.
        //   3. AudioModule.onCrossModuleStateChange cascades
        //      `ApplyAudioFocusRuntimeFromPref(value)` iff a recording
        //      is Active, which produces `Effect.ApplyAudioFocusRuntime`
        //      → the live AudioManager follows the pref (replaces
        //      `recordingStateController.setAudioFocusRuntime`).
        // No private listener field, no register/unregister — gone.

        // Pipeline UI side-effects driven reactively from the orchestrator
        // state (Phase 5.B of `2026-05-21 - dictate-render-cutover-completion-vol2`).
        // The reactive PipelineStepRowRenderer owns the step-row UI directly
        // via ImeViewBackend.render → onState; this observer carries only the
        // non-renderer responsibilities that the legacy PipelineUiCallback
        // mechanic delivered (queue-order sync, language-chip enable + label
        // refresh, QWERTZ rec-button updates). distinctUntilChanged on the
        // pipeline-phase guarantees one fire per transition.
        if (pipelineUiStateObserver != null) {
            pipelineUiStateObserver.stop();
        }
        if (pipelineBinder != null) {
            // Defensive — onCreateInputView may race with onServiceConnected
            // (the binder might still be null on the first inflate; the
            // post-bind call site re-runs the wiring once the binder
            // arrives). Without the guard the observer would launch against
            // a null StateFlow.
            pipelineUiStateObserver = new PipelineUiStateObserver(
                pipelineBinder.getState(),
                pipeline -> {
                    // Phase 2: language chip is permanently visible; only the
                    // editable-queue order tracks ReprocessStaging.
                    syncQueueOrder(pipeline);

                    // Phase 2 Quality-Gate W-6: chip stays clickable except while
                    // a transcription is in flight (Running / Preparing).
                    if (promptsAdapter != null) {
                        boolean pipelineRunning =
                            pipeline instanceof net.devemperor.dictate.state.PipelineUiState.Running
                                || pipeline instanceof net.devemperor.dictate.state.PipelineUiState.Preparing;
                        promptsAdapter.setLanguageChipEnabled(!pipelineRunning);
                    }

                    // D-13: entering / leaving staging or any pipeline transition can
                    // change what resolveEffectiveLanguage() returns, so the chip
                    // label must re-resolve. Idempotent (same code → same label).
                    refreshLanguageChip();

                    if (qwertzRecordingController == null) return;
                    if (pipeline instanceof net.devemperor.dictate.state.PipelineUiState.Idle) {
                        qwertzRecordingController.updateQwertzRecButton(false);  // QWERTZ → Mic-Icon
                    } else if (pipeline instanceof net.devemperor.dictate.state.PipelineUiState.Running) {
                        // Phase 5.B: enterPipelineDisplay + updatePipelineTimer are
                        // idempotent enough to call on every transition (one fire
                        // per phase via distinctUntilChanged). The per-step elapsedMs
                        // ticking happens via state.pipeline emits — every step start/
                        // complete carries a fresh elapsedMs, and Running→Running
                        // transitions land here as a new pipeline phase only when the
                        // elapsedMs (and stepHistory) actually changed.
                        net.devemperor.dictate.state.PipelineUiState.Running runningState =
                                (net.devemperor.dictate.state.PipelineUiState.Running) pipeline;
                        qwertzRecordingController.enterPipelineDisplay(runningState);
                        qwertzRecordingController.updatePipelineTimer(runningState, runningState.getElapsedMs());
                    } else if (pipeline instanceof net.devemperor.dictate.state.PipelineUiState.Preparing) {
                        // Upload phase: clear any leftover recording-state rendering.
                        qwertzRecordingController.updateQwertzRecButton(false);
                    }
                });
            pipelineUiStateObserver.start();
        }

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
        // ADR-0009: mandatory slot — RECORD_SECONDARY is declared in every
        // keyboard mode, so a missing map entry would be a render-time error(...).
        buttonViews.put(LogicalButtonId.RECORD_SECONDARY, secondaryRecordButton);
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

        // RecordingAnimationController: drive BorderGlow + the breathing
        // background animator from state.recording transitions. Spec 2
        // §11.5 keeps the animation outside the pure-resolver model —
        // the controller is forwarded from ImeViewBackend.render.
        // animationsEnabled is read live from Pref.Animations so a
        // settings flip is reflected on the next state emit.
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
        kotlin.jvm.functions.Function0<Integer> accentColorLambda =
            () -> DictatePrefsKt.get(sp, Pref.AccentColor.INSTANCE);
        RecordingAnimationController recordingAnimationCtrlForBackend =
            new RecordingAnimationController(
                recordingAnimationForBackend,
                recordButton,
                accentColorLambda,
                animationsEnabledLambda
            );
        // Phase 3 of dictate-render-cutover-completion-vol2 — single
        // writer for record_btn's left + right compound drawables
        // (including the dynamic AutoEnter ↵ BitmapDrawable in Running).
        net.devemperor.dictate.state.render.AutoEnterRenderer autoEnterRendererForBackend =
            new net.devemperor.dictate.state.render.AutoEnterRenderer(recordButton);
        // Phase 5.A of dictate-render-cutover-completion-vol2 — single
        // writer for record_btn's setTextColor axis (red on hasFailure,
        // white otherwise).
        net.devemperor.dictate.state.render.RecordButtonColorController recordButtonColorControllerForBackend =
            new net.devemperor.dictate.state.render.RecordButtonColorController(
                recordButton, 0xFFF44336, android.graphics.Color.WHITE);

        kotlin.jvm.functions.Function0<kotlin.Unit> vibrateLambda = () -> {
            vibrate();
            return kotlin.Unit.INSTANCE;
        };

        // CR2/CR4 (Theme C-R) — the SpecialTouchHandlerInstaller builds
        // the three Spec 2 §11.7 touch handlers (SPACE CursorSwipe /
        // BACKSPACE swipe-select / ENTER overlay) wired to the IME's real
        // InputConnection / accent / vibrate / accel-delete-cancel + the
        // SAME shared KeyPressAnimator (G3/G4/G5).
        //
        // RR-1 (THE trap of this block) — CR4 FLIP: the legacy
        // mainButtonsController.registerAllListeners() (the LIVE owner of
        // the SPACE/BACKSPACE/ENTER setOnTouchListener pre-CR4) is now
        // removed on the bound path (it runs only as the unbound
        // fallback — see the onCreateInputView CR4 comment). This whole
        // method (attachImeViewBackendIfReady) only runs when
        // pipelineBinder != null, so the legacy touch wiring was NOT
        // applied. CR4 therefore calls attachToViews() (NOT
        // installDormant()) so the new §11.7 handlers become the sole
        // live SPACE/BACKSPACE/ENTER touch owner — never both wired at
        // once (render-path-cutover.md §5/§6 RR-1; the dormant→attached
        // ledger transition CR2 established). installDormant() runs
        // first (build + single-owner-guard + cache) then attachToViews()
        // does the setOnTouchListener (the same cached handler instances).
        specialTouchHandlerInstaller = new SpecialTouchHandlerInstaller(
            () -> getCurrentInputConnection(),
            // P4: the single InsertionService owner — forwarded to the
            // SPACE/BACKSPACE/ENTER special-touch handlers for their writes.
            () -> insertionService(),
            () -> DictatePrefsKt.get(sp, Pref.AccentColor.INSTANCE),
            vibrateLambda,
            () -> {
                onBackspaceDeleteCancelled();
                return kotlin.Unit.INSTANCE;
            },
            qwertzKeyboardView.getKeyPressAnimator()
        );
        final SpecialTouchHandlerInstaller installerRef = specialTouchHandlerInstaller;
        kotlin.jvm.functions.Function1<Map<LogicalButtonId, ? extends View>, kotlin.Unit>
            staticHandlerInstaller = views -> {
                @SuppressWarnings("unchecked")
                Map<LogicalButtonId, View> typedViews = (Map<LogicalButtonId, View>) views;
                // CR4: build (+ single-owner guard + cache) THEN attach
                // to the live Views — the legacy touch wiring is gone on
                // the bound path so this is now the sole live owner.
                installerRef.installDormant(typedViews);
                installerRef.attachToViews(typedViews);
                return kotlin.Unit.INSTANCE;
            };

        // CR1 (Theme C-R) — Spec 2 §6 ctor now carries the shared
        // KeyPressAnimator (behaviour group G7). Pass the SAME instance
        // MainButtonsController uses (qwertzKeyboardView's animator) so the
        // new path's key-press scale animation is byte-identical to the
        // legacy one. The backend skips the three special-touch buttons
        // (SPACE/BACKSPACE/ENTER) when wiring press-animation — their
        // OnTouchListener is the staticHandlerInstaller's (CR2 builds it
        // dormant). RR-1: no double-wire — the backend's press-anim
        // listener on the *non-special* buttons is the same
        // KeyPressAnimator behaviour the legacy controller wired (returns
        // false, click/long-press unaffected); the special handlers
        // compose press-animation internally. CR4: the applyTheme axis
        // is now driven by imeViewBackend.applyTheme (the legacy
        // mainButtonsController.applyTheme drive is removed on the bound
        // path — see onStartInputView CR4 comment).
        // CR4 (Theme C-R / render-path-cutover.md §7 A1 / CR4-IMPL-3) —
        // the IME-side affordance hook. Several legacy
        // MainButtonsController.Callback button behaviours have NO
        // FSM/dispatch representation — the catalog/modules model only
        // part of what the legacy listener did and the remainder is a
        // pure IME-side side-effect with no Action/ModuleServices
        // surface:
        //  - RECORD long-press: legacy onRecordLongClicked did the Idle
        //    → Settings+file-picker launch + the autoSwitchKeyboard
        //    one-shot; the catalog resolveRecordLongPressAction +
        //    RecordingModule own only the FSM-half (discard-stop).
        //  - RESEND click: legacy onResendClicked does the
        //    last-keyboard-session DB lookup → ResendStatusDispatcher →
        //    insert/resume; the catalog ResendLastAudio → ResendModule
        //    ONLY arms the cooldown (no effect — the resend insertion
        //    has NO new-path implementation; CR4-IMPL-3 architecture
        //    gap, the §13.2-class "assumed-an-owner-that-was-never-
        //    created" pattern at the RESEND-action layer).
        //  - RESEND long-press: legacy onResendLongClicked enters
        //    ReprocessStaging with the last session; the catalog
        //    ResendLastAudioLong → ResendModule ONLY arms the cooldown.
        // The backend fires this hook (the EXACT legacy
        // onRecordLongClicked / onResendClicked / onResendLongClicked
        // Callback bodies — behaviour-identical) BEFORE the catalog
        // dispatch, so the affordances survive the cutover with zero
        // behaviour drift while the catalog dispatch still arms the
        // cooldown / models the FSM-half. render-path-cutover.md §7 A1
        // explicitly scopes "IME-side affordances with no FSM/dispatch
        // representation" as the CR4 IME-side activation.
        kotlin.jvm.functions.Function2<LogicalButtonId, Boolean, kotlin.Unit>
            imeSideAffordance = (id, isLongPress) -> {
                if (id == LogicalButtonId.RECORD && isLongPress) {
                    onRecordLongClicked();
                } else if (id == LogicalButtonId.OVERLAY_RECORD && isLongPress) {
                    // B-A / OQ-5 (dictate-pipeline-render-and-state-unification
                    // §5.4 + §9.5 Variante A): overlay-record long-press is a
                    // deliberate no-op. The catalog has no `longClickResolver`
                    // on OVERLAY_RECORD today, and the keyboard
                    // RECORD-long-press affordance (Settings + file-picker
                    // launch) is UX-inconsistent from a floating widget
                    // (Activity-launch would obscure / kill the widget).
                    // Documenting the no-op explicitly so a future reader
                    // doesn't add the keyboard-RECORD body here by accident.
                } else if (id == LogicalButtonId.BACKSPACE && isLongPress) {
                    // B-C (dictate-pipeline-render-and-state-unification §5.5
                    // Variante B): the accelerating-delete cascade
                    // (`onBackspaceLongClicked` → `deleteHandler.postDelayed`
                    // with 50→25→10→5 ms cascade) has NO catalog/dispatch
                    // representation. The CR-DEL render-cutover deleted its
                    // legacy caller (`MainButtonsController.Callback`) but
                    // left the body in place; this affordance branch
                    // re-attaches it. ACTION_UP / ACTION_CANCEL cancellation
                    // is owned by `BackspaceSwipeHandler.onTouch` →
                    // `onBackspaceDeleteCancelled()` (already intact).
                    onBackspaceLongClicked();
                } else if (id == LogicalButtonId.RESEND && isLongPress) {
                    onResendLongClicked();
                } else if (id == LogicalButtonId.RESEND) {
                    // CR4 (G8) — double-click guard on the bound path.
                    // The legacy guard was the synchronous imperative
                    // setResendEnabled(false) (now removed on the bound
                    // path); the catalog ResendLastAudio → ResendModule
                    // arms state.resend.resendCooldown but only AFTER the
                    // dispatch+render, so a fast second tap could slip a
                    // duplicate DB-lookup through before the
                    // enabledResolver disables the button. Mirror the
                    // ResendModule `if (!resendCooldown)` guard here so a
                    // second RESEND tap during the cooldown window is a
                    // no-op (idempotent — exactly the legacy
                    // setResendEnabled(false) intent).
                    boolean inCooldown = pipelineBinder != null
                            && pipelineBinder.getState().getValue()
                                .getResend().getResendCooldown();
                    if (!inCooldown) {
                        onResendClicked();
                    }
                } else if ((id == LogicalButtonId.RECORD
                        || id == LogicalButtonId.OVERLAY_RECORD)
                        && getPipelinePhase()
                            instanceof net.devemperor.dictate.state.PipelineUiState.ReprocessStaging) {
                    // F-001 (2026-07-03) — catalog-path staging send.
                    // In ReprocessStaging the big record button is a Send
                    // trigger. The catalog RECORD slot's actionResolver
                    // (resolveSendStagingAction) used to dispatch a
                    // queue-less SendStaging here — losing the user's
                    // staged edits AND the model/targetApp/totalSteps
                    // reprocess snapshot (the catalog path never ran
                    // handleReprocessSend, so no snapshotReprocess). Route
                    // the catalog record button through the SAME single
                    // submitter as the QWERTZ record button
                    // (onRecordClicked → handleReprocessSend): it snapshots
                    // the reprocess config and dispatches SendStaging
                    // carrying the staged queue as explicit content slots.
                    // The catalog resolver now returns null (see
                    // resolveSendStagingAction) so this affordance is the
                    // sole SendStaging dispatcher — symmetric with RESEND.
                    handleReprocessSend();
                } else if (id == LogicalButtonId.RECORD
                        || id == LogicalButtonId.OVERLAY_RECORD) {
                    // Post-cutover hotfix (symmetric to RESEND above; see
                    // ADR-0005 Decision-History "catalog-click affordance
                    // hook symmetry"). The RECORD-click "stop & send"
                    // (state Active|Paused → catalog returns
                    // StopRecordingAndSend) needs the IME-side R-1
                    // JobRequest snapshot + the pipeline-step-row prime
                    // BEFORE the catalog dispatches, because the
                    // orchestrator's PipelineModule.SubmitPipeline →
                    // PipelineRunnerSubsystemAdapter → resolveFresh runs
                    // asynchronously off the dispatch and would otherwise
                    // hit an empty snapshot (the loud
                    // UnsupportedOperationException tripwire fires; the
                    // pipeline FSM hangs in Preparing; the user sees
                    // endless "Sending…" with no step-rows / no
                    // progress-bar — the R-1 silent-data-loss class).
                    // Self-gating helper: no-op when state is not
                    // Active|Paused (mirrors the catalog resolver's null
                    // for those states).
                    //
                    // B-A fix (dictate-pipeline-render-and-state-unification
                    // §5.4): OVERLAY_RECORD is the merged RECORD+SEND
                    // catalog slot in the floating-widget overlay (see
                    // LayoutCatalog OVERLAY_5BUTTON.OVERLAY_RECORD). The
                    // OverlayBackend click branch already fires
                    // `imeSideAffordance(OVERLAY_RECORD, false)` BEFORE
                    // dispatching, but pre-fix this lambda only matched
                    // `RECORD` — so the OVERLAY_RECORD click dropped
                    // through to the catalog `StopRecordingAndSend`
                    // dispatch with no R-1 snapshot → pipeline FSM hung
                    // in Preparing → endless "sendet" with no editor
                    // commit. Treating both IDs identically keeps the
                    // RECORD-Single-SoT (one helper, two click-sites).
                    //
                    // F-003 (2026-07-03) — prime the auto-apply queue on
                    // the catalog record-START path. The catalog Idle→RECORD
                    // tap dispatches StartRecording directly (via
                    // ActionResolvers.resolveStartRecordingFromIdle),
                    // bypassing the IME's startRecording() where
                    // prepareAutoApplyQueue() lives (that helper is only on
                    // the QWERTZ record button + instant-prompt chip). So
                    // catalog-started recordings never front-loaded the
                    // autoApply prompts and sent verbatim transcripts. The
                    // affordance hook fires BEFORE the catalog dispatch, so
                    // priming here lands the autoApply IDs in the queue
                    // before the recording session starts — and before the
                    // send-tap's captureFreshConfigSnapshot reads them. The
                    // helper is a no-op for the send (Active|Paused) case:
                    // it only primes when recording is Idle (mirrors the
                    // catalog resolver's Idle-start arm).
                    prepareCatalogAutoApplyQueueIfIdle();
                    prepareCatalogStopRecordingIfActive();
                }
                return kotlin.Unit.INSTANCE;
            };

        // 2026-05-21 indirection-cleanup Chunk 4.2 — pass the container
        // + accent-text view lists so the backend owns the
        // `setBackgroundColor` + `setTextColor` passes that the IME's
        // `onStartInputView` previously inlined (B-3 + B-4).
        java.util.List<View> themedContainers = new java.util.ArrayList<>();
        if (dictateKeyboardView != null) themedContainers.add(dictateKeyboardView);
        if (emojiPickerCl != null) themedContainers.add(emojiPickerCl);
        if (qwertzContainer != null) themedContainers.add(qwertzContainer);
        java.util.List<TextView> accentTextViews = new java.util.ArrayList<>();
        // 2026-07-02 — the info-bar message view is deliberately NOT in
        // this list: InfoBarRenderer owns its text colour per item style
        // (INFO/ERROR/ACTION) and re-resolves it on every render.
        if (emojiPickerTitleTv != null) accentTextViews.add(emojiPickerTitleTv);

        imeViewBackend = new ImeViewBackend(
            new RealMotionSurface(motionLayout),
            buttonViews,
            context,
            pipelineBinder.getModuleServices(),
            recordingAnimationCtrlForBackend,
            autoEnterRendererForBackend,
            recordButtonColorControllerForBackend,
            // Phase 5.B (Vol 2): reactive step-row renderer driven by
            // ImeViewBackend.render → onState.
            pipelineStepRowRenderer,
            qwertzKeyboardView.getKeyPressAnimator(),
            staticHandlerInstaller,
            vibrateLambda,
            imeSideAffordance,
            themedContainers,
            accentTextViews
        );

        // dictate-widget-integration §8.3 Chunk 3.2 — share the exact
        // same IME-side affordance lambda with the service-owned
        // OverlayBackend, so an OVERLAY_RECORD click in the floating
        // widget fires the same R-1 `JobRequest` snapshot
        // (`prepareCatalogStopRecordingIfActive`) as a keyboard-RECORD
        // click. Without this registration the overlay SEND path would
        // dispatch `StopRecordingAndSend` with no snapshot in
        // `imePipelineConfigResolver`, the pipeline async `resolveFresh`
        // would hit the R-1 tripwire (UnsupportedOperationException),
        // the EffectFailure arm would catch it, and the pipeline FSM
        // would hang in Preparing forever — the exact bug Plan §5 traces.
        // Self-gating: the lambda's RECORD branch is a no-op when state
        // is not Active|Paused, so registering unconditionally is safe.
        pipelineBinder.registerImeSideAffordance(imeSideAffordance);

        try {
            keyboardLayoutManager.attachBackend(imeViewBackend);
        } catch (Throwable t) {
            Log.w("DictateIME", "KeyboardLayoutManager.attachBackend failed", t);
            imeViewBackend = null;
        }

        // Attach the three R.10 visibility controllers (G10/G11/G12)
        // and arm their gates — they are the SOLE LIVE writers of the
        // ContentArea / Promptbar / overlay-reset axes (Spec 2 §10 /
        // §11.8 5c). Historical (CR3/CR4 + CR-DEL, render-path-cutover.md
        // §6 RR-2): these controllers were first attached gated-dormant
        // (CR3) while the legacy `KeyboardStateManager` still drove
        // these axes, then armed in CR4 as the legacy drive was removed
        // (never two live writers at once — the RR-2 staged-safety-net),
        // and `KeyboardStateManager` was finally deleted in CR-DEL.
        // There is now no KSM and no fallback writer: see
        // attachDormantVisibilityControllers() for the post-CR-DEL
        // attach-failure mode.
        attachDormantVisibilityControllers();

        // CR-EXTRACT (Theme C-R) — build the three §13.2 owners
        // (EditBar / Emoji / OverlayChars) BUILD-BUT-DORMANT (CR4-IMPL-1
        // resolution). RR-1/RR-2: the legacy MainButtonsController
        // sub-registrations are still the SOLE LIVE owner until CR4
        // flips per-axis atomically. Same consolidation point as the
        // backend / visibility-controller attach so it runs on both
        // onCreateInputView and onServiceConnected (race-safe).
        attachDormantEditBarEmojiOwners();

        // 2026-05-21 ADR-0006 — (re)start the state-driven info-bar
        // renderer now that both the binder and the inflated views exist.
        // Single consolidation point called from onCreateInputView and
        // onServiceConnected, so the renderer is wired regardless of the
        // bind↔inflate race. stop() the prior renderer first so a
        // view-recreate doesn't leak the old collector scope.
        if (infoBarRenderer != null) {
            infoBarRenderer.stop();
        }
        if (infoBarContainer != null) {
            android.widget.TextView infoBarText =
                    dictateKeyboardView.findViewById(R.id.infobar_message_tv);
            android.widget.Button infoBarYes =
                    dictateKeyboardView.findViewById(R.id.infobar_confirm_btn);
            android.widget.Button infoBarNo =
                    dictateKeyboardView.findViewById(R.id.infobar_dismiss_btn);
            if (infoBarText != null && infoBarYes != null && infoBarNo != null) {
                infoBarRenderer = new net.devemperor.dictate.state.infobar.InfoBarRenderer(
                        (androidx.constraintlayout.widget.ConstraintLayout) infoBarContainer,
                        infoBarText,
                        infoBarYes,
                        infoBarNo,
                        pipelineBinder.getState(),
                        action -> {
                            // State-mutation pass: every click dispatches
                            // its action through the single sink, so the
                            // selector re-renders into the new state.
                            //
                            // Side-channels (ADR-0006 §"Failure Modes"):
                            // imperative operations that the pure state
                            // layer cannot reach (Activity launches,
                            // InputConnection.commitText) fire alongside
                            // dispatch. The IME service IS the Context-
                            // bearing seam.
                            //
                            // Read out the side-channel parameters BEFORE
                            // dispatch (the state mutation removes the
                            // session from pendingSessions, so reading
                            // .transcribedText after dispatch would race).
                            String pendingInsertText = null;
                            java.util.List<net.devemperor.dictate.state.insertion.PendingPart> pendingPartsBatch = null;
                            if (action instanceof net.devemperor.dictate.state.Action.PendingSessionsAction.AcceptAndInsert) {
                                String sid = ((net.devemperor.dictate.state.Action.PendingSessionsAction.AcceptAndInsert) action).getSessionId();
                                for (net.devemperor.dictate.state.PendingSession s : pipelineBinder.getState().getValue().getPendingSessions()) {
                                    if (s.getSessionId().equals(sid) && s.getTranscribedText() != null) {
                                        pendingInsertText = s.getTranscribedText();
                                        break;
                                    }
                                }
                            } else if (action instanceof net.devemperor.dictate.state.Action.PendingSessionsAction.AcceptAndInsertAll) {
                                // R4 aggregate confirm (ADR-0009 / spec §3.5) —
                                // snapshot the ordered COMPLETED+text parts BEFORE
                                // dispatch (the per-part AcceptAndInsert the flusher
                                // dispatches removes sessions from state, so reading
                                // after dispatch would race). Same recording-order
                                // sort as the InfoBar selector.
                                pendingPartsBatch = net.devemperor.dictate.state.insertion.PendingPartsFlusherKt
                                        .pendingPartsToFlush(pipelineBinder.getState().getValue().getPendingSessions());
                            }

                            pipelineBinder.dispatch(action);

                            if (pendingInsertText != null) {
                                // Pending-Insert confirm: drop the
                                // transcribed text at the current
                                // cursor. If the InputConnection went
                                // away between dispatch and this call,
                                // the commitText is silently ignored —
                                // the session is already marked
                                // inserted_at via the dispatched
                                // AcceptAndInsert action, so the user
                                // sees the dismiss outcome (item gone)
                                // even when the paste itself failed.
                                // Insertion unification — pending-insert accept
                                // routes through the single InsertionService.
                                insertionService().insert(new InsertionRequest(
                                        pendingInsertText, null,
                                        InsertionPolicy.KEYSTROKE, null, null));
                            } else if (pendingPartsBatch != null) {
                                // R4 flush: insert every part in recording order,
                                // consuming each only after its own successful
                                // commit (PendingPartsFlusher — insert-first,
                                // consume-after; stops at the first failure).
                                flushPendingParts(pendingPartsBatch);
                            } else {
                                launchInfoBarSideChannel(action);
                            }
                            return kotlin.Unit.INSTANCE;
                        },
                        getResources(),
                        () -> getTheme());
                infoBarRenderer.start();
            }
        }

        // 2026-05-21 indirection-cleanup Chunk 3.3 — start the reactive
        // edit-bar audio-focus-twin observer. It feeds `refreshAudioFocusIcon`
        // from `state.audio.audioFocusEnabledPref` on every distinct change
        // (including the first emit on subscribe — which subsumes the
        // post-attach seed `editBarController.refreshAudioFocusIcon(...)`
        // call in `attachDormantEditBarEmojiOwners`).
        if (editBarAudioFocusObserver != null) {
            editBarAudioFocusObserver.stop();
        }
        editBarAudioFocusObserver = new EditBarAudioFocusObserver(
                pipelineBinder.getState(),
                enabled -> {
                    if (editBarController != null) {
                        editBarController.refreshAudioFocusIcon(enabled);
                    }
                });
        editBarAudioFocusObserver.start();

        // 2026-05-22 — reactive small-mode rotation observer. Fires the
        // existing animator whenever `state.layout.smallMode` changes,
        // closing the gap left by SetContentArea's auto-exit (Reducer-
        // driven small-mode flips that don't route through
        // onSmallModeToggled).
        if (editNumbersSmallModeObserver != null) {
            editNumbersSmallModeObserver.stop();
        }
        editNumbersSmallModeObserver = new EditNumbersSmallModeObserver(
                pipelineBinder.getState(),
                smallMode -> {
                    if (editNumbersAnimator != null) {
                        editNumbersAnimator.animateSmallModeToggle(true);
                    }
                });
        editNumbersSmallModeObserver.start();

        // dictate-pipeline-render-and-state-unification §5.7 — start the
        // reactive prompt-chips-busy observer. Listens to the derived
        // `recording is Active|Paused|Preparing OR pipeline is
        // Preparing|Running` predicate and re-runs
        // `updatePromptButtonsEnabledState()` when it flips
        // (distinctUntilChanged on the boolean → one call per genuine
        // transition, not per tick). Replaces the legacy
        // `recordingStateController.getState()` read which never fired
        // on the new path (B-E regression).
        if (promptChipsBusyObserver != null) {
            promptChipsBusyObserver.stop();
        }
        promptChipsBusyObserver = new PromptChipsBusyObserver(
                pipelineBinder.getState(),
                busy -> updatePromptButtonsEnabledState());
        promptChipsBusyObserver.start();

        // dictate-pipeline-render-and-state-unification §5.2 — the
        // per-second `TickPipelineTimer` ticker is SERVICE-OWNED since
        // the 2026-07-11 external-start incident fix (it must advance
        // the widget's pipeline label even when no IME ever bound). See
        // DictatePipelineService onCreate Step 8b. Nothing to wire here.

        // Post-cutover hotfix #3+#4, ownership inverted 2026-07-11 —
        // the recording-animation ticker is SERVICE-OWNED (the external
        // entry points record without any IME; the service owns the
        // recorder + OverlayBackend). The IME registers its per-tick
        // sinks instead of constructing a ticker: the lambdas read the
        // IME fields at call time, so a view recreation needs no
        // re-registration (this method re-runs and re-registers anyway,
        // idempotently). Amplitude arrives RAW (0..32767) and is
        // normalised with the IME's own processor — per-surface EMA
        // state, same math as the service's overlay-side processor.
        // Single-poller invariant: never construct a second
        // RecordingActivityTickerObserver here (getMaxAmplitude is a
        // destructive read — two pollers split the peaks).
        pipelineBinder.registerRecordingTickSinks(
                elapsedMs -> {
                    if (imeViewBackend != null) imeViewBackend.onTimerTick(elapsedMs);
                    if (qwertzRecordingController != null) {
                        qwertzRecordingController.onTimerTick(elapsedMs);
                    }
                    return kotlin.Unit.INSTANCE;
                },
                amplitude -> {
                    // Post-cutover hotfix #AMP — normalize 0..32767 raw
                    // MediaRecorder amplitude into the 0..1 contract the
                    // renderer expects. See processor-field KDoc.
                    float level = recordingTickerAmplitudeProcessor.process(amplitude);
                    if (imeViewBackend != null) imeViewBackend.onAmplitude(level);
                    if (qwertzRecordingController != null) {
                        qwertzRecordingController.onAmplitude(level);
                    }
                    return kotlin.Unit.INSTANCE;
                });
        // Clean EMA-history before the next ticker cycle so a fresh
        // session starts at silence instead of inheriting the smoothed
        // tail of the previous one.
        recordingTickerAmplitudeProcessor.reset();
    }

    /**
     * CR3/CR4/CR-DEL (Theme C-R / AC-RR-5) — attach the R.10 visibility
     * controllers ({@link ContentAreaController} G10 + the 4th
     * editButtons axis, {@link PromptVisibilityController} G11, {@link
     * OverlayResetHandler} G12) as {@code backendType=null}
     * multi-backends (ambiguity A4 — parent B4 design, reused not
     * reinvented) and {@code arm()} them.
     *
     * <p><b>CR-DEL — the legacy KeyboardStateManager is DELETED.</b> The
     * gates are {@code arm()}-ed (the controllers are the SOLE LIVE
     * writer of the ContentArea / Promptbar / overlay-reset axes); the
     * shared {@link
     * net.devemperor.dictate.core.audit.VisibilityWriteAuditLogger}
     * ledger proves {@code doubleWriteCount == 0} because there is now
     * exactly one writer per axis (the controller — no KSM half), as
     * CR-RGATE's RenderPathCutoverGateTest verified GREEN. The
     * dormant→armed staged-safety-net (CR3 dormant / CR4 arm) collapses
     * here: no unbound fallback, the point-of-no-return.
     *
     * <p>Idempotent on view-recreate: the previous controllers are
     * detached in {@link #cleanupOldControllers()} / {@link
     * #onDestroy()} (symmetric with the {@code imeViewBackend}
     * lifecycle) before this rebuilds them against the fresh tree.
     */
    private void attachDormantVisibilityControllers() {
        if (pipelineBinder == null || keyboardLayoutManager == null) {
            return;
        }

        // CR-DEL — the legacy KeyboardStateManager (the no-double-write
        // proof's legacy half) is DELETED. The ledger now only sees the
        // armed controllers as live writers — `doubleWriteCount == 0`
        // holds because there is exactly one (the controller per axis),
        // proven by CR-RGATE's RenderPathCutoverGateTest.
        net.devemperor.dictate.core.audit.VisibilityWriteAuditLogger auditLogger =
            pipelineBinder.getVisibilityWriteAuditLogger();

        // pipeline_progress_ll is resolved fresh from the inflated tree
        // (it is a local in the KSM-setup path, not a service field —
        // re-resolving is cheap and matches how widget_toggle_btn is
        // handled above).
        View pipelineProgressLl =
            dictateKeyboardView.findViewById(R.id.pipeline_progress_ll);

        contentAreaGate = new RenderGate("ContentAreaController", auditLogger);
        // CR-DEL (RR-3 gap) — the 4th ContentArea axis: editButtonsKeyboardLl
        // (Spec 2 §13 row 2, BLEIBT ContentArea-Achse). The deleted KSM
        // owned it; relocated into ContentAreaController so no visibility
        // axis is stranded by the deletion.
        contentAreaController = new ContentAreaController(
            new ContentAreaViews(mainButtonsClGroup, qwertzContainer, emojiPickerCl,
                editButtonsKeyboardLl,
                dictateKeyboardView.findViewById(R.id.keyboard_minimal_strip)),
            contentAreaGate);

        promptVisibilityGate = new RenderGate("PromptVisibilityController", auditLogger);
        promptVisibilityController = new PromptVisibilityController(
            new PromptVisibilityViews(
                promptsCl, promptsRv, pipelineProgressLl, promptRecordingControlsLl),
            promptVisibilityGate);

        overlayResetGate = new RenderGate("OverlayResetHandler", auditLogger);
        overlayResetHandler = new OverlayResetHandler(
            new OverlayResetViews(overlayCharactersLl),
            overlayResetGate);

        // ADR-0013 — the review panel container + its own buttons (outside the
        // slot grid, InfoBar precedent). The renderer owns visibility/text/
        // enable; the click handlers own the imperative side (host commit,
        // recording start, job cancel) the reducer cannot reach.
        View reviewInsertBtn = dictateKeyboardView.findViewById(R.id.review_insert_btn);
        View reviewRedictateBtn = dictateKeyboardView.findViewById(R.id.review_redictate_btn);
        View reviewDiscardBtn = dictateKeyboardView.findViewById(R.id.review_discard_btn);
        reviewPanelRenderer = new net.devemperor.dictate.state.render.ReviewPanelRenderer(
            new net.devemperor.dictate.state.render.ReviewPanelViews(
                dictateKeyboardView.findViewById(R.id.review_panel_cl),
                dictateKeyboardView.findViewById(R.id.review_message_tv),
                dictateKeyboardView.findViewById(R.id.review_output_tv),
                dictateKeyboardView.findViewById(R.id.review_refining_tv),
                reviewInsertBtn, reviewRedictateBtn, reviewDiscardBtn));
        if (reviewInsertBtn != null) reviewInsertBtn.setOnClickListener(v -> onReviewInsertClicked());
        if (reviewRedictateBtn != null) reviewRedictateBtn.setOnClickListener(v -> onReviewRedictateClicked());
        if (reviewDiscardBtn != null) reviewDiscardBtn.setOnClickListener(v -> onReviewDiscardClicked());

        // ADR-0014 — the in-keyboard history panel: a RecyclerView fed by an
        // IME-owned Paging stream (KeyboardHistoryController), a close button,
        // and a renderer that toggles the container + drives the collector.
        androidx.recyclerview.widget.RecyclerView historyRv =
            dictateKeyboardView.findViewById(R.id.history_panel_rv);
        View historyPanelCl = dictateKeyboardView.findViewById(R.id.history_panel_cl);
        View historyCloseBtn = dictateKeyboardView.findViewById(R.id.history_panel_close_btn);
        if (historyRv != null && dictateDb != null) {
            historyRv.setLayoutManager(
                new androidx.recyclerview.widget.LinearLayoutManager(this));
            historyAdapter = new net.devemperor.dictate.history.KeyboardHistoryAdapter(
                new net.devemperor.dictate.history.KeyboardHistoryAdapter.Callback() {
                    @Override
                    public void onInsert(net.devemperor.dictate.database.entity.SessionEntity session,
                                         boolean pending) {
                        onKeyboardHistoryInsertClicked(session, pending);
                    }
                });
            historyRv.setAdapter(historyAdapter);
            // "Distinctly taller": ~50% of the display height, clamped to
            // [min, max] so the panel scales across device sizes.
            applyHistoryPanelHeight(historyRv);
            historyController = new net.devemperor.dictate.history.KeyboardHistoryController(
                new net.devemperor.dictate.history.KeyboardHistoryPager(dictateDb.sessionDao()),
                historyAdapter);
            historyController.onViewCreated();
        }
        historyPanelRenderer = new net.devemperor.dictate.state.render.HistoryPanelRenderer(
            new net.devemperor.dictate.state.render.HistoryPanelViews(historyPanelCl),
            open -> {
                if (historyController == null) return kotlin.Unit.INSTANCE;
                if (open) historyController.onPanelOpen(); else historyController.onPanelClosed();
                return kotlin.Unit.INSTANCE;
            });
        if (historyCloseBtn != null) {
            historyCloseBtn.setOnClickListener(v -> dispatchPipelineActionToOrchestrator(
                net.devemperor.dictate.state.Action.HistoryPanelAction.Close.INSTANCE,
                "HistoryPanel.Close"));
        }

        try {
            keyboardLayoutManager.attachBackend(contentAreaController);
            keyboardLayoutManager.attachBackend(promptVisibilityController);
            keyboardLayoutManager.attachBackend(overlayResetHandler);
            keyboardLayoutManager.attachBackend(reviewPanelRenderer);
            keyboardLayoutManager.attachBackend(historyPanelRenderer);
            // Arm the three gates so the controllers are the SOLE LIVE
            // writers of the ContentArea / Promptbar / overlay-reset
            // axes. Post-CR-DEL there is no legacy `KeyboardStateManager`
            // and no fallback writer — these controllers are the only
            // drivers (historical RR-2 staged-safety-net: armed in CR4
            // as the legacy drive was removed, KSM then deleted in
            // CR-DEL). The state.layout.contentArea axis itself is
            // mutated via dispatch(LayoutAction.SetContentArea) at the
            // former stateManager.setContentArea call-sites (LayoutModule
            // is the SoT — contentArea is NOT pref-mirrored, so the
            // dispatch is mandatory for the reactive ContentAreaController
            // to render).
            contentAreaGate.arm();
            promptVisibilityGate.arm();
            overlayResetGate.arm();
        } catch (Throwable t) {
            // Mirror the imeViewBackend attach failure handling: log +
            // detach so the detach paths stay consistent. F-5 (B5-VAL):
            // post-CR-DEL there is NO legacy KSM fallback — on a real
            // attach/arm throw the ContentArea / Promptbar /
            // overlay-reset axes have **no driver at all** (frozen /
            // blank, silent) until the next successful view-recreate
            // re-attaches the controllers. Attach failure here is rare;
            // the honest failure mode is "these visibility axes are
            // dead until the next attach", NOT a graceful KSM fallback
            // (that safety net was deleted at the point-of-no-return).
            Log.w("DictateIME",
                "Visibility-controller attach/arm failed — ContentArea/Promptbar/"
                    + "overlay-reset axes are dead until the next view-recreate attach "
                    + "(no legacy KSM fallback post-CR-DEL)", t);
            detachDormantVisibilityControllers();
        }
    }

    /**
     * Symmetric counterpart to {@link
     * #attachDormantVisibilityControllers()} — detaches the three R.10
     * controllers from the manager (releasing their View references)
     * and clears the fields. Called on view-recreate ({@link
     * #cleanupOldControllers()}) and process tear-down ({@link
     * #onDestroy()}), exactly like the {@code imeViewBackend} detach.
     * Detach is idempotent (the manager's {@code detachBackend} no-ops
     * an unattached backend), so this is safe to call in error paths.
     */
    private void detachDormantVisibilityControllers() {
        if (keyboardLayoutManager != null) {
            try {
                if (contentAreaController != null) {
                    keyboardLayoutManager.detachBackend(contentAreaController);
                }
                if (promptVisibilityController != null) {
                    keyboardLayoutManager.detachBackend(promptVisibilityController);
                }
                if (overlayResetHandler != null) {
                    keyboardLayoutManager.detachBackend(overlayResetHandler);
                }
                if (reviewPanelRenderer != null) {
                    keyboardLayoutManager.detachBackend(reviewPanelRenderer);
                }
                if (historyPanelRenderer != null) {
                    keyboardLayoutManager.detachBackend(historyPanelRenderer);
                }
            } catch (Throwable t) {
                Log.w("DictateIME", "Dormant visibility-controller detach failed", t);
            }
        }
        // ADR-0014: cancel the history-panel Paging scope (no leak / no query
        // after the input view is gone).
        if (historyController != null) {
            historyController.onViewDestroyed();
            historyController = null;
        }
        historyAdapter = null;
        contentAreaController = null;
        promptVisibilityController = null;
        overlayResetHandler = null;
        reviewPanelRenderer = null;
        historyPanelRenderer = null;
        contentAreaGate = null;
        promptVisibilityGate = null;
        overlayResetGate = null;
    }

    /**
     * CR-EXTRACT (Theme C-R / CR4-IMPL-1 resolution) — build the three
     * §13.2-prescribed-but-never-created owners
     * ({@link EditBarController} / {@link EmojiController} /
     * {@link OverlayCharactersController}) <b>build-but-dormant</b>.
     *
     * <p><b>RR-1/RR-2 — the load-bearing decision.</b> The legacy
     * {@code MainButtonsController.registerEditBarListeners() /
     * registerEmojiListeners() / initializeOverlayCharacters()} ran
     * earlier (via {@code registerAllListeners()}, line ~870) and are
     * the <b>sole LIVE owner</b> of the edit-bar / emoji / overlay-chars
     * axes until <b>CR4</b>. Android keeps only the most-recent
     * {@code setOnClickListener}, and a second overlay-chars inflate
     * would double the child count — so the new owners only
     * <b>build + cache</b> ({@code installDormant()}) / are
     * <b>gated dormant</b> ({@link RenderGate}, {@code armed=false}).
     * CR4 calls {@code attachToViews()} / {@code arm()} <em>in the same
     * chunk</em> it removes {@code registerAllListeners()} — never both
     * wired at once (identical staged-safety-net to CR2's
     * {@code installDormant} touch model + CR3's dormant
     * visibility-controller {@link RenderGate}).
     *
     * <p>Idempotent on view-recreate: the previous owners are cleared
     * by {@link #detachDormantEditBarEmojiOwners()} (called from
     * {@link #cleanupOldControllers()} / {@link #onDestroy()}) before
     * this rebuilds them against the fresh inflated tree.
     */
    private void attachDormantEditBarEmojiOwners() {
        if (editNumbersButton == null || editEmojiButton == null
                || overlayCharactersLl == null) {
            // The inflated tree isn't ready yet — the consolidation
            // point also fires from onServiceConnected before
            // onCreateInputView in some races; the later call rebuilds.
            return;
        }

        // CR4 (Theme C-R / CR4-IMPL-1 resolution — THE flip): this method
        // is only reachable from attachImeViewBackendIfReady() which
        // requires pipelineBinder != null, so the legacy
        // MainButtonsController.registerAllListeners() (the sole live
        // edit-bar/emoji/overlay-chars owner pre-CR4) was NOT applied
        // (it runs only as the unbound fallback — see the
        // onCreateInputView CR4 comment). The new owners therefore
        // installDormant() (build + single-owner-guard + cache) THEN
        // attachToViews() / arm() — becoming the sole live owner with no
        // overlap (RR-1/RR-2: never both wired at once; the
        // dormant→attached-cr4 ledger transition CR-EXTRACT established).
        editBarController = new EditBarController(
            new EditBarViews(
                editNumbersButton, editSettingsButton, editHistoryButton,
                pipelineCancelBtn, editAudioFocusButton, editKeyboardButton,
                editUndoButton, editRedoButton, editCutButton,
                editCopyButton, editPasteButton, editWidgetToggleButton),
            this);
        editBarController.installDormant();
        editBarController.attachToViews();
        // 2026-05-21 indirection-cleanup Chunk 3.3 — the legacy
        // imperative seed (`editBarController.refreshAudioFocusIcon(sp.get(Pref.AudioFocus))`)
        // is no longer needed: the reactive
        // `EditBarAudioFocusObserver` started further down emits the
        // current value on first subscribe and on every subsequent
        // change. F-3 (B5-VAL — edit-bar twin not covered by catalog
        // AUDIO_FOCUS slot) is now satisfied via the reactive observer
        // instead of imperative re-painting.

        emojiController = new EmojiController(
            new EmojiViews(editEmojiButton, emojiPickerCloseButton, emojiPickerView),
            this,
            // P4: the single InsertionService owner for the picked-emoji commit.
            () -> insertionService());
        emojiController.installDormant();
        emojiController.attachToViews();

        // CR4 (G15) — the edit-numbers animation owner. CR1 extracted
        // EditNumbersAnimator; MainButtonsController was a thin delegate
        // (removed on the bound path). The IME holds it directly and the
        // onSmallModeToggled / onSingleRowModeToggled / onStartInputView
        // call-sites drive it (re-pointed from mainButtonsController.*).
        // Decoupled suppliers: animationsEnabled ← Pref.Animations,
        // isSmallMode ← state.layout.smallMode (the SoT post-cutover;
        // KSM is on the kill-list). Pre-bind the legacy
        // mainButtonsController.animateSmallModeToggle still drives (the
        // unbound fallback ran registerAllListeners but the animate calls
        // are also guarded — see those call-sites).
        editNumbersAnimator = new net.devemperor.dictate.core.EditNumbersAnimator(
            editNumbersButton,
            () -> DictatePrefsKt.get(sp, Pref.Animations.INSTANCE),
            () -> pipelineBinder != null
                ? pipelineBinder.getState().getValue().getLayout().getSmallMode()
                : DictatePrefsKt.get(sp, Pref.SmallMode.INSTANCE));

        // Overlay-chars is a *write* axis (8 char-view inflate +
        // visibility/text/tint) → reuse the CR3 RenderGate dormant
        // model + the same shared Strict-Mode ledger as the visibility
        // controllers (RR-2 no-double-write proof, Spec 2 §10).
        net.devemperor.dictate.core.audit.VisibilityWriteAuditLogger auditLogger =
            pipelineBinder != null
                ? pipelineBinder.getVisibilityWriteAuditLogger()
                : null;
        overlayCharactersGate =
            new RenderGate("OverlayCharactersController", auditLogger);
        overlayCharactersController = new OverlayCharactersController(
            new OverlayCharactersViews(overlayCharactersLl),
            overlayCharactersGate);
        // CR4: arm the gate FIRST (the legacy
        // MainButtonsController.initializeOverlayCharacters() drive is
        // removed on the bound path — registerAllListeners ran only as
        // the unbound fallback), then initialize() inflates the 8
        // char-views for real. The per-input content/theme update is
        // re-pointed from mainButtonsController.updateOverlayCharacters
        // to overlayCharactersController.update(...) at the
        // onStartInputView call-site (the same drive cadence — overlay
        // chars are set on input-view-start, not per render-tick; the
        // RenderGate is reused only for the dormant/armed + ledger
        // proof, not a reactive RenderBackend). initialize() is
        // idempotent (childCount guard) so a view-recreate cannot stack
        // a second set of 8.
        overlayCharactersGate.arm();
        overlayCharactersController.initialize();
    }

    /**
     * Symmetric counterpart to
     * {@link #attachDormantEditBarEmojiOwners()} — clears the three
     * CR-EXTRACT owner fields (releasing their View references) on
     * view-recreate ({@link #cleanupOldControllers()}) and process
     * tear-down ({@link #onDestroy()}), exactly like the {@code
     * imeViewBackend} / dormant-visibility-controller detach. The owners
     * hold no manager registration (they are not RenderBackends — they
     * are imperatively driven), so clearing the fields is the whole
     * detach. Idempotent.
     */
    private void detachDormantEditBarEmojiOwners() {
        editBarController = null;
        emojiController = null;
        overlayCharactersController = null;
        overlayCharactersGate = null;
        // CR4 (G15) — the IME-held EditNumbersAnimator holds a direct
        // View reference; clear it symmetrically with the other
        // CR-EXTRACT owners (rebuilt against the fresh tree by
        // attachDormantEditBarEmojiOwners()).
        editNumbersAnimator = null;
        // 2026-05-21 ADR-0006 — the state-driven InfoBarRenderer holds
        // direct View references too; stop the collector + clear the
        // reference symmetric with editNumbersAnimator (rebuilt by the
        // next view-creation).
        if (infoBarRenderer != null) {
            infoBarRenderer.stop();
            infoBarRenderer = null;
        }
    }

    // method is called if the user closed the keyboard
    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);

        // DictateTrace — IME-Lifecycle log for the recording-loss-on-app-switch
        // investigation. Snapshots the recording / pipeline / viewMode at entry
        // so we can see exactly what was active when the app-switch arrived.
        try {
            String snap;
            if (pipelineBinder != null) {
                net.devemperor.dictate.state.DictateUiState s = pipelineBinder.getState().getValue();
                snap = "recording=" + s.getRecording().getClass().getSimpleName()
                        + " pipeline=" + s.getPipeline().getClass().getSimpleName()
                        + " viewMode=" + s.getViewMode()
                        + " widget=" + s.getWidget().getClass().getSimpleName();
            } else {
                snap = "no-binder";
            }
            Log.i("DictateTrace", "IME.onFinishInputView(finishing=" + finishingInput + ") " + snap);
        } catch (Throwable t) {
            Log.w("DictateTrace", "snapshot in onFinishInputView failed", t);
        }

        // Hide QWERTZ keyboard when the input view is finishing (app switch, background, etc.)
        hideQwertzKeyboard();

        // ADR-0014: stop the history-panel Paging collector while the keyboard
        // is hidden (belt-and-braces to the OnImeViewHidden cascade that closes
        // the panel). Idempotent; the scope stays alive for the next open.
        if (historyController != null) historyController.onPanelClosed();

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
        //
        // 2026-05-21 F — the legacy `recordingStateController.onKeyboardHidden()`
        // call (which paused the MediaRecorder with a 60s timeout) is
        // GONE. Post-cutover the RecordingStateController is dead code
        // on the bound path (its KDoc spells this out) and the
        // FGS-owned RecordingHardwareAdapter keeps the recording alive
        // across IME-view tear-down. The user's "Aufnahme darf nicht
        // verloren gehen bei Tastatur-Schließen" requirement is now
        // structurally satisfied: the recorder lives in the service
        // and the OnImeViewHidden dispatch below flips ViewMode to
        // HOVER so the Overlay surface takes over rendering.
        if (recordingStateController.getState().isRecordingOrPaused()
                || recordingStateController.getState() instanceof RecordingState.Preparing) {
            // State (A): Recording is active or paused — no IME-side
            // hardware mutation; recording continues in the FGS.
            // Content-area is collapsed so the leftover IME-view
            // chrome doesn't paint partial state during HOVER.
            setEffectiveContentArea(ContentArea.MAIN_BUTTONS);
        } else if (pipelineOrchestrator != null && pipelineOrchestrator.isRunning()) {
            // State (B): API request is running -> let it continue, just hide content panels
            setEffectiveContentArea(ContentArea.MAIN_BUTTONS);
        } else {
            // State (C): Idle -> full cleanup
            if (pipelineOrchestrator != null) {
                pipelineOrchestrator.cancel();
            }
            pendingLivePromptChain = false;
            // Note: AutoEnterConfig is owned by pipelineStepRowRenderer; stopPipeline() nulls it below.

            bluetoothScoManager.unregisterReceiver();

            // 2026-07-02 (ADR-0006 completion) — the legacy
            // infoBarController.dismiss() here is replaced by
            // InfoHintModule's cross-module observer: the IME-hide
            // dispatch (OnImeViewHidden above) flips imeViewVisible,
            // and the observer clears state.infoHints when recording +
            // pipeline are idle — exactly this "State (C)" branch.
            setEffectiveContentArea(ContentArea.MAIN_BUTTONS);
            // CR-DEL (RR-2): the legacy KSM refresh unbound fallback is
            // GONE (KeyboardStateManager deleted). The SetContentArea
            // dispatch above re-emits state → the armed visibility
            // controllers re-render every axis reactively.
            // Phase 5.B: PipelineStepRowRenderer is reactive — Idle is
            // already reflected because this branch is gated on
            // `!pipelineOrchestrator.isRunning()`. The legacy imperative
            // stopPipeline() was the View-side echo of that fact; no
            // longer needed.
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
            // B3.3 bridge — dispatch the same lifecycle event onto the
            // new Widget axis (ADR-0008) so `state.widget` and
            // `state.imeViewVisible` keep tracking the live IME state.
            // Both axes co-exist until B3.5 retires ViewMode; the
            // resolvers + layout predicates still read `viewMode`, so
            // this bridge is dispatch-only (no behaviour change visible
            // to the user yet).
            pipelineBinder.dispatch(
                    net.devemperor.dictate.state.Action.WidgetAction.OnImeViewHidden.INSTANCE);
            // Plan: dictate-enter-button-host-action Chunk 3 — the host
            // editor is gone (app switch / IME hide). Reset the
            // HostEditorState so a stale snapshot does not drive the
            // Catalog's resolveEnterIcon between now and the next
            // onStartInputView. Pre-bind no-op via the outer guard.
            pipelineBinder.dispatch(
                    net.devemperor.dictate.state.Action.KeyboardInputAction.HostEditorDetached.INSTANCE);
        }
    }

    // DictateTrace — additional IME-lifecycle overrides to make the
    // app-switch / unbind / window-hide sequence visible. These hooks
    // are critical for the recording-loss investigation: onUnbindInput
    // fires when the IME service is detached from a client (app-switch
    // to a non-EditText context), onWindowHidden when the IME-window
    // itself is taken off-screen.
    @Override
    public void onUnbindInput() {
        try {
            String snap = "no-binder";
            if (pipelineBinder != null) {
                net.devemperor.dictate.state.DictateUiState s = pipelineBinder.getState().getValue();
                snap = "recording=" + s.getRecording().getClass().getSimpleName()
                        + " pipeline=" + s.getPipeline().getClass().getSimpleName()
                        + " viewMode=" + s.getViewMode();
            }
            Log.i("DictateTrace", "IME.onUnbindInput() " + snap);
        } catch (Throwable t) {
            Log.w("DictateTrace", "snapshot in IME.onUnbindInput failed", t);
        }
        super.onUnbindInput();
    }

    @Override
    public void onWindowHidden() {
        try {
            String snap = "no-binder";
            if (pipelineBinder != null) {
                net.devemperor.dictate.state.DictateUiState s = pipelineBinder.getState().getValue();
                snap = "recording=" + s.getRecording().getClass().getSimpleName()
                        + " pipeline=" + s.getPipeline().getClass().getSimpleName()
                        + " viewMode=" + s.getViewMode();
            }
            Log.i("DictateTrace", "IME.onWindowHidden() " + snap);
        } catch (Throwable t) {
            Log.w("DictateTrace", "snapshot in IME.onWindowHidden failed", t);
        }
        super.onWindowHidden();
    }

    @Override
    public void onDestroy() {
        try {
            String snap = "no-binder";
            if (pipelineBinder != null) {
                net.devemperor.dictate.state.DictateUiState s = pipelineBinder.getState().getValue();
                snap = "recording=" + s.getRecording().getClass().getSimpleName()
                        + " pipeline=" + s.getPipeline().getClass().getSimpleName()
                        + " viewMode=" + s.getViewMode();
            }
            Log.i("DictateTrace", "IME.onDestroy() " + snap);
        } catch (Throwable t) {
            Log.w("DictateTrace", "snapshot in IME.onDestroy failed", t);
        }
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
        // CR3 — detach the three dormant R.10 visibility controllers
        // (symmetric with imeViewBackend) BEFORE nulling the manager.
        detachDormantVisibilityControllers();
        // CR-EXTRACT — clear the EditBar/Emoji/OverlayChars owners
        // (symmetric, hold direct View references too).
        detachDormantEditBarEmojiOwners();
        keyboardLayoutManager = null;

        // 2026-05-21 ADR-0006 — cancel the state-driven info-bar
        // collector scope so the SupervisorJob does not outlive the
        // service. Replaces the legacy OverlayOnboardingObserver
        // tear-down (B5 F-2 + Chunk 4.1).
        if (infoBarRenderer != null) {
            infoBarRenderer.stop();
            infoBarRenderer = null;
        }

        // 2026-05-21 indirection-cleanup Chunk 3.3 — symmetric tear-down
        // of the edit-bar audio-focus-twin reactive observer.
        if (editBarAudioFocusObserver != null) {
            editBarAudioFocusObserver.stop();
            editBarAudioFocusObserver = null;
        }
        if (editNumbersSmallModeObserver != null) {
            editNumbersSmallModeObserver.stop();
            editNumbersSmallModeObserver = null;
        }

        // dictate-pipeline-render-and-state-unification §5.7 — symmetric
        // tear-down of the prompt-chips-busy reactive observer.
        if (promptChipsBusyObserver != null) {
            promptChipsBusyObserver.stop();
            promptChipsBusyObserver = null;
        }

        // 2026-07-11 ownership inversion — both tickers are service-owned
        // now; the IME only clears its registered tick sinks so the
        // service ticker stops forwarding into dead View references.
        if (pipelineBinder != null) {
            pipelineBinder.registerRecordingTickSinks(null, null);
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
        // 2026-05-21 indirection-cleanup Chunk 4.5b — the
        // `inputLanguagesListener` unregistration is GONE with the
        // listener itself (PipelinePrefMirror computed-mirror replaces
        // it). Stop the LanguageEffectiveObserver symmetric with the
        // other observers below.
        if (languageEffectiveObserver != null) {
            languageEffectiveObserver.stop();
            languageEffectiveObserver = null;
        }
        // 2026-05-21 indirection-cleanup Chunk 3.5 — the audioFocusListener
        // unregistration is GONE with the listener itself (the C-3 cascade
        // through AudioModule.onCrossModuleStateChange replaces it).

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
        // Stop only the elapsed timer — no mode reset, no side-effects.
        if (pipelineStepRowRenderer != null) {
            // Phase 5.B (Vol 2): the per-run auto-enter override now lives
            // on the orchestrator's `state.pipeline.Running.autoEnterActive`
            // (D6 §4.5 of the plan). A view-recreate during Running does
            // NOT lose the toggle because the orchestrator state survives
            // the IME view tear-down. The legacy `restoreAutoEnter` bridge
            // is therefore a no-op on Phase-5.B; keep it cleared so it
            // cannot leak into a fresh pipeline.
            restoreAutoEnter = null;

            // W1: Capture "we were in staging" so we re-enter it on the
            // fresh renderer. The data lives in dedicated IME fields
            // (reprocess* below); they survive the view tear-down naturally.
            net.devemperor.dictate.state.PipelineUiState phase = getPipelinePhase();
            restoreReprocessStaging =
                    phase instanceof net.devemperor.dictate.state.PipelineUiState.ReprocessStaging;

            // Phase 5.B: stop the per-row timer so the discarded renderer's
            // ElapsedTimer thread does not leak (the new renderer rebuilds
            // its own per-row timer from `state.pipeline.stepHistory`).
            pipelineStepRowRenderer.stopActiveTimer();
        }

        // Phase 5.B: tear down the Service-side pipeline observer so its
        // coroutine scope does not retain a reference to the soon-to-be-
        // discarded view tree. The fresh onCreateInputView re-creates and
        // re-starts it.
        if (pipelineUiStateObserver != null) {
            pipelineUiStateObserver.stop();
            pipelineUiStateObserver = null;
        }
        // 2026-05-21 indirection-cleanup Chunk 4.5b — the
        // inputLanguagesListener is gone (PipelinePrefMirror does the
        // SP→state half via computed-mirror); the
        // LanguageEffectiveObserver stops here so the next
        // onCreateInputView builds a fresh one against the new view tree.
        if (languageEffectiveObserver != null) {
            languageEffectiveObserver.stop();
            languageEffectiveObserver = null;
        }
        // 2026-05-21 indirection-cleanup Chunk 3.5 — audioFocusListener
        // removed entirely; the view-recreate path no longer needs to
        // un-register a per-view listener.
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
        // CR3 — detach the three dormant R.10 visibility controllers
        // before re-inflate (they hold direct View references too,
        // symmetric with imeViewBackend). Rebuilt against the fresh
        // tree by attachDormantVisibilityControllers().
        detachDormantVisibilityControllers();
        // CR-EXTRACT — clear the EditBar/Emoji/OverlayChars owners
        // before re-inflate (direct View references). Rebuilt against
        // the fresh tree by attachDormantEditBarEmojiOwners().
        detachDormantEditBarEmojiOwners();
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
        // 2026-05-21 indirection-cleanup Chunk 4.6 (C-4) — the
        // `recordingStateController.setCallback(new Callback() { ... })`
        // block is REMOVED. On the bound path (all production paths
        // post-C5/CR-DEL) the legacy `recordingStateController` is never
        // started (`isEffectiveRecordingIdle` KDoc), so its callbacks
        // never fired anyway. The block was kept compiling-and-dead-on-
        // arrival as a behaviour-equivalence safety net for the import
        // path's `onRecordingCompleted` — Chunk 4.4 (A-5) closed that
        // hatch by dispatching `OnAudioFileImported` directly from
        // `onStartInputView`, so the safety net has no surviving caller.
        //
        // `RecordingStateController.setCallback` itself stays in the
        // controller until Block 5 (the RecordingStateController retire
        // follow-up plan) collapses the whole class into a thin
        // hardware-adapter. The controller's callback field is nullable
        // and every emission site uses `callback?.…` — leaving it
        // un-set is safe.

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
            // CR-DEL: RecordingUiController deleted. The recording-axis
            // record-button/animation/visibility is owned reactively by
            // RecordingAnimationController + the catalog resolvers off
            // the orchestrator's state.recording; only the QWERTZ
            // rec-button (G9 BLEIBT) needs the imperative re-apply on a
            // view-recreate restore (byte-equivalent to the deleted
            // RecordingUiController.onStateChanged step 3).
            if (qwertzRecordingController != null) {
                qwertzRecordingController.updateQwertzRecButton(
                    currentState.isRecordingOrPaused());
            }
            updatePromptButtonsEnabledState();
            updateKeepScreenAwake(currentState.isRecordingOrPaused());
        }

        // 2. Pipeline state → UI
        if (restoreReprocessStaging) {
            // W1: The user was editing the reprocess queue when the view
            // was recreated (rotation / theme change). The IME-Java mirror
            // fields (reprocessTargetSessionId / reprocessEditableQueue /
            // reprocessSelectedLanguage / reprocessAudioDurationSeconds /
            // reprocessSelectedModel) carry the staging payload across
            // view tear-down; re-dispatch EnterReprocessStaging to the
            // orchestrator so the canonical state.pipeline is back in
            // staging and downstream listeners (queue order, language
            // chip) refresh via the pipelineUiStateObserver.
            restoreReprocessStaging = false;
            if (pipelineBinder != null && reprocessTargetSessionId != null) {
                // F-6 (B5-VAL): re-seed the override carrier with the
                // staging's (possibly user-overridden) language so
                // resolveEffectiveLanguage() stays in lock-step.
                dispatchStagingOverride(reprocessSelectedLanguage);
                // Orchestrator state's ReprocessStaging carries only
                // (sessionId, transcript). The transcript is not
                // re-snapshotable at restore time -- the View-side staging
                // UI reads from the IME mirror fields directly via
                // reprocessStagingOrNull().
                pipelineBinder.dispatch(
                        new net.devemperor.dictate.state.Action.PipelineAction.StartReprocessStaging(
                                reprocessTargetSessionId));
            }
        }
        // Phase 5.B (Vol 2): the legacy else-if Running branch imperatively
        // re-drove pipelineStepRowRenderer.startPipeline + addRunningStep
        // to redraw Running after a view tear-down. The reactive renderer
        // repaints Running from state.pipeline.stepHistory on the very
        // first ImeViewBackend.render after re-attach, so no IME-side
        // bookkeeping is required. The restoreAutoEnter bridge is dead too
        // (state.pipeline.Running.autoEnterActive is the SoT and outlives
        // the view tree).
        restoreAutoEnter = null;

        // 3. Small mode from preferences. CR-DEL (Theme C-R / RR-2):
        // Pref.SmallMode is mirrored into state.layout.smallMode by
        // PipelinePrefMirror and the armed visibility controllers +
        // ImeViewBackend render it reactively — the legacy KSM
        // setSmallMode unbound fallback is GONE (KeyboardStateManager
        // deleted at the point-of-no-return).
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
                // Phase 5.B: phase comes from the orchestrator state, not the (now-reactive) renderer.
                net.devemperor.dictate.state.PipelineUiState currentState = getPipelinePhase();
                if (currentState instanceof net.devemperor.dictate.state.PipelineUiState.ReprocessStaging) {
                    handleReprocessPromptToggle(model);
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
                            insertionService().editAction(EditAction.SELECT_ALL);
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

            @Override
            public void onTextOnlyItemApplyRequested(Integer position) {
                // Long-press on a greyed (recording/pipeline-busy) text-only pill:
                // apply it exactly as an idle short-press would. Text-only pills are
                // requiresSelection == false by definition, so the standalone
                // orchestrator path is the full enabled behaviour — there is no
                // selection step and no queue-toggle branch to reuse here.
                PromptEntity model = promptsAdapter.getItem(position);
                if (model == null || model.getId() < 0) return;
                vibrate();
                runStandalonePromptViaOrchestrator(model);
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

        // Post-cutover hotfix #5 — defensive: fill the adapter as soon as
        // it is constructed. Without this, the first frame after a
        // force-stop / service-re-init shows only the language-chip control
        // sentinel because the InvalidationTracker has not fired yet AND
        // the next onStartInputView reloadPrompts may early-return when
        // promptDao is still null mid-bind. This direct call carries its
        // own promptDao guard inside `reloadPrompts()` so it is a no-op
        // when the DB is genuinely not ready (the InvalidationTracker
        // will then take over on first table change).
        reloadPrompts();
    }

    /**
     * Phase 2 §2.7b: Syncs the prompts-adapter's queued-prompt order to the
     * pipeline state. The language chip is now permanently visible (its
     * label is refreshed via {@link #refreshLanguageChip()} on every
     * effective-language change), so this method has only a single
     * responsibility — keep the editable queue or the regular queue in
     * the adapter, depending on the active state.
     */
    private void syncQueueOrder(net.devemperor.dictate.state.PipelineUiState newState) {
        if (promptsAdapter == null) return;
        // Phase 5.B (Vol 2): the orchestrator's
        // `state.PipelineUiState.ReprocessStaging` carries only
        // `(sessionId, transcript)`; the editable queue lives in the
        // IME-Java mirror field `reprocessEditableQueue`.
        if (newState instanceof net.devemperor.dictate.state.PipelineUiState.ReprocessStaging) {
            promptsAdapter.setQueuedPromptOrder(reprocessEditableQueue);
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
     * <p><b>F-6 collapsed (B3-VAL) &amp; lifecycle completed (B5-VAL).</b>
     * The ReprocessStaging override is read from the <b>single</b>
     * {@code LanguageState.override} carrier (the orchestrator's SoT),
     * <i>not</i> the legacy
     * {@code PipelineUiState.ReprocessStaging.selectedLanguage} carrier
     * (owned by the deleted {@code KeyboardUiController}). B5-VAL closed
     * the gap left when the read-side collapsed without the write/clear
     * side: that carrier's lifecycle is now fully wired via
     * {@link #dispatchStagingOverride(String)} —
     * <ul>
     *   <li><b>seeded</b> with the session language on ReprocessStaging
     *       entry ({@code onResendLongClicked} + the view-recreate
     *       staging restore),</li>
     *   <li><b>overridden</b> by an explicit re-pick
     *       ({@code LanguageAction.SetOverride} from
     *       {@link #setLanguageFromPicker(String)}),</li>
     *   <li><b>cleared</b> ({@code SetOverride(null)}) on every staging
     *       exit ({@code cancelReprocessStaging} + the reprocess-send →
     *       Preparing transition) so it cannot leak into the next
     *       staging session.</li>
     * </ul>
     * The permanent value still comes from the static
     * {@link net.devemperor.dictate.preferences.LanguageResolver}.
     * R-3 boot-before-bind: when unbound the binder read returns null and
     * we fall through to the permanent resolver (never a stale cache or
     * NPE).</p>
     */
    private String resolveEffectiveLanguage() {
        String override = reprocessStagingOverrideOrNull();
        if (override != null) return override;
        return net.devemperor.dictate.preferences.LanguageResolver.INSTANCE
                .effectiveLanguage(sp);
    }

    /**
     * The current {@link net.devemperor.dictate.state.PipelineUiState.ReprocessStaging}
     * staging state, or {@code null} when the orchestrator's
     * {@code state.pipeline} is not in ReprocessStaging.
     *
     * <p>Phase 5.B (Vol 2): the orchestrator's
     * {@code state.PipelineUiState.ReprocessStaging} now carries only
     * {@code (sessionId, transcript)}. The View-side payload
     * (audio-duration, editable-queue, selected-language, selected-model)
     * lives in dedicated IME mirror fields ({@link #reprocessTargetSessionId},
     * {@link #reprocessAudioDurationSeconds}, {@link #reprocessEditableQueue},
     * {@link #reprocessSelectedLanguage}, {@link #reprocessSelectedModel}).
     * Used for the staging-session detection — NOT for the language
     * override (which is the collapsed {@code LanguageState.override}
     * carrier; see {@link #reprocessStagingOverrideOrNull()}).</p>
     */
    private net.devemperor.dictate.state.PipelineUiState.ReprocessStaging reprocessStagingOrNull() {
        net.devemperor.dictate.state.PipelineUiState phase = getPipelinePhase();
        if (phase instanceof net.devemperor.dictate.state.PipelineUiState.ReprocessStaging) {
            return (net.devemperor.dictate.state.PipelineUiState.ReprocessStaging) phase;
        }
        return null;
    }

    /**
     * The trimmed, non-blank ReprocessStaging language override, or
     * {@code null} when not in ReprocessStaging / unbound / unset.
     *
     * <p><b>F-6 collapsed.</b> Reads the single {@code LanguageState.override}
     * carrier off the bound orchestrator (the new SoT), guarded so it
     * only counts while the renderer is actually in ReprocessStaging
     * (the override is a per-staging-session transient — outside staging
     * the permanent resolver must win, exactly as the legacy carrier
     * scoped it). The blank-guard is preserved (F-3): exactly one place
     * decides what a valid override is.</p>
     */
    private String reprocessStagingOverrideOrNull() {
        // Scope: an override only applies while in ReprocessStaging (the
        // legacy carrier was structurally scoped that way — the override
        // lived inside PipelineUiState.ReprocessStaging). The collapsed
        // LanguageState.override carrier is process-wide, so re-apply the
        // staging scope here.
        if (reprocessStagingOrNull() == null) return null;
        if (pipelineBinder == null) return null;
        String override = pipelineBinder.getState().getValue().getLanguage().getOverride();
        if (override != null && !override.trim().isEmpty()) return override;
        return null;
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
        // CR-DEL (Theme C-R / G14): the RECORD-slot textResolver
        // (resolveRecordButtonText, state-driven) re-renders the label —
        // the RefreshFromPref dispatch above moves state.language.effective,
        // which re-emits state → the attached ImeViewBackend re-renders
        // the RECORD slot. The legacy mainButtonsController.updateRecordButtonText
        // unbound fallback is GONE (MainButtonsController deleted at the
        // point-of-no-return).
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
     * <p>Phase 5.B (Vol 2): the editable queue lives in the IME-Java
     * mirror field {@link #reprocessEditableQueue} (the orchestrator's
     * {@code state.PipelineUiState.ReprocessStaging} does not carry it).
     * After mutation we fan out the new queue to the adapter directly
     * (the old callback-driven syncQueueOrder loop went through the
     * renderer; the renderer is now reactive).</p>
     */
    private void handleReprocessPromptToggle(PromptEntity model) {
        if (model.getId() < 0) return;  // sentinel items have no meaning here
        List<Integer> queue = new ArrayList<>(reprocessEditableQueue);
        int promptId = model.getId();
        if (queue.contains(promptId)) {
            queue.removeIf(id -> id == promptId);
        } else {
            queue.add(promptId);
        }
        reprocessEditableQueue = queue;
        if (promptsAdapter != null) {
            promptsAdapter.setQueuedPromptOrder(reprocessEditableQueue);
        }
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
     *   <li><b>ReprocessStaging</b> → a <i>transient</i> override
     *       (an explicit re-pick that supersedes the session language
     *       seeded on staging entry via
     *       {@link #dispatchStagingOverride(String)}; the carrier is
     *       cleared again on staging exit — see that method + F-6 in
     *       {@link #resolveEffectiveLanguage()}).
     *       Dispatched as {@code LanguageAction.SetOverride(code)} —
     *       {@code LanguageState.override} is the <b>single</b> SoT the
     *       effective-language read now uses (F-6 collapsed, CR-DEL). The
     *       relocated {@link net.devemperor.dictate.state.render.PipelineStepRowRenderer#updateReprocessLanguage(String)}
     *       still mirrors it into the View-side
     *       {@code PipelineUiState.ReprocessStaging.selectedLanguage}
     *       staging state (kept for the record-button label + queue
     *       restore — the BLEIBT staging state, Spec 1 §9.2), but the
     *       language READ no longer depends on that carrier. Never
     *       persisted.</li>
     *   <li><b>any other state</b> → a <i>permanent</i> write with
     *       auto-curation via the static
     *       {@link net.devemperor.dictate.preferences.LanguageResolver},
     *       followed by {@link #pushPermanentLanguageToOrchestrator()}
     *       (refreshes chip + label + dispatches RefreshFromPref).</li>
     * </ul>
     */
    private void setLanguageFromPicker(String code) {
        // F-3 (B3-VAL): route on the canonical ReprocessStaging detector
        // (shared with resolveEffectiveLanguage). Routing semantics are
        // unchanged — in ReprocessStaging the pick is a transient
        // override regardless of whether one was already set; otherwise
        // it is a permanent write. (The picker `code` is always a
        // resource-array-derived ISO code, never blank, so the
        // read-side blank-guard does not apply to this write path.)
        if (reprocessStagingOrNull() != null) {
            // F-6 collapsed (CR-DEL): LanguageState.override is the single
            // SoT the effective-language read uses. Dispatch it first.
            if (pipelineBinder != null) {
                try {
                    pipelineBinder.dispatch(
                            new net.devemperor.dictate.state.Action.LanguageAction.SetOverride(code));
                } catch (Throwable t) {
                    Log.w("DictateIME", "SetOverride dispatch failed", t);
                }
            }
            // Phase 5.B (Vol 2): mirror into the IME-Java staging field
            // (the orchestrator's `state.PipelineUiState.ReprocessStaging`
            // does not carry the selected language). The chip label
            // refreshes via the pipelineUiStateObserver's refreshLanguageChip
            // hook on the next LanguageAction.SetOverride emit above.
            reprocessSelectedLanguage = code;
            refreshLanguageChip();
        } else {
            // 2026-05-21 indirection-cleanup Chunk 4.5c (A-6) — the
            // legacy two-step
            //   1. LanguageResolver.setLanguage(sp, code) — curate+persist
            //   2. pushPermanentLanguageToOrchestrator() — dispatch
            //      RefreshFromPref so the orchestrator picks up the SP
            //      write
            // collapses to a single dispatch. The LanguageModule reducer
            // arm writes state.language.effective and emits
            // Effect.PersistEffectiveLanguage; the effect delegates the
            // curate+persist to LanguageResolver.setLanguage (no
            // duplication of the curation algorithm).
            //
            // Pre-bind fallback retained — when pipelineBinder is null
            // (narrow service-not-yet-bound window) write through the
            // resolver directly so the user's pick survives.
            if (pipelineBinder != null) {
                try {
                    pipelineBinder.dispatch(
                            new net.devemperor.dictate.state.Action.LanguageAction.SetEffectiveLanguage(code));
                } catch (Throwable t) {
                    Log.w("DictateIME", "SetEffectiveLanguage dispatch failed", t);
                }
                refreshLanguageChip();
            } else {
                // PRE-BIND-FALLBACK: SP-write authorized because dispatcher
                // unavailable (review-fix G2, 2026-05-21). LanguageResolver
                // is the SoT for the curated-list-write algorithm (same call
                // the dispatched Effect.PersistEffectiveLanguage delegates
                // to — see RecordingModule / LanguageModule D-3 in state.md).
                net.devemperor.dictate.preferences.LanguageResolver.INSTANCE
                        .setLanguage(sp, code);
                pushPermanentLanguageToOrchestrator();
            }
        }
    }

    /**
     * F-6 staging-override lifecycle (B5-VAL, re-opened &amp; closed).
     * Seed / clear the <b>single</b> {@code LanguageState.override}
     * carrier at the ReprocessStaging boundary so
     * {@link #resolveEffectiveLanguage()} (chip + transcription-config
     * snapshot) reflects the staging session's language without
     * re-introducing the dual-carrier F-6 collapsed.
     *
     * <p>Mirrors the {@link #setLanguageFromPicker(String)} dispatch
     * idiom ({@code pipelineBinder}-guarded, swallow + log on failure).
     * {@code code == null} clears the override (the reducer treats null
     * as "no override"; the read-side blank-guard in
     * {@link #reprocessStagingOverrideOrNull()} is consistent with
     * this). Called with the session language on staging entry, with
     * {@code null} on every staging exit (cancel / discard /
     * reprocess-send → Preparing). The explicit picker
     * ({@link #setLanguageFromPicker}) still overrides a seeded value
     * when the user re-picks.</p>
     */
    private void dispatchStagingOverride(@androidx.annotation.Nullable String code) {
        if (pipelineBinder == null) return;
        try {
            pipelineBinder.dispatch(
                    new net.devemperor.dictate.state.Action.LanguageAction.SetOverride(code));
        } catch (Throwable t) {
            Log.w("DictateIME", "Staging SetOverride dispatch failed", t);
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

        // DictateTrace — IME-Lifecycle log. Snapshots the state at entry so we
        // can correlate with the rotation-bug + recording-loss investigations.
        try {
            String snap;
            if (pipelineBinder != null) {
                net.devemperor.dictate.state.DictateUiState s = pipelineBinder.getState().getValue();
                snap = "smallMode=" + s.getLayout().getSmallMode()
                        + " contentArea=" + s.getLayout().getContentArea()
                        + " recording=" + s.getRecording().getClass().getSimpleName()
                        + " pipeline=" + s.getPipeline().getClass().getSimpleName()
                        + " viewMode=" + s.getViewMode()
                        + " widget=" + s.getWidget().getClass().getSimpleName();
            } else {
                snap = "no-binder smallModePref="
                        + DictatePrefsKt.get(sp, Pref.SmallMode.INSTANCE);
            }
            Log.i("DictateTrace", "IME.onStartInputView(restarting=" + restarting + ") " + snap);
        } catch (Throwable t) {
            Log.w("DictateTrace", "snapshot in onStartInputView failed", t);
        }

        // Plan: dictate-enter-button-host-action Chunk 5 — the Enter
        // button's icon AND action are both derived from this
        // HostEditorState snapshot via the Catalog's resolveEnterIcon /
        // resolveEnterAction. The legacy updateEnterButtonIcon method
        // and performEnterAction helper are gone (zero-grep).
        if (pipelineBinder != null) {
            pipelineBinder.dispatch(
                    new net.devemperor.dictate.state.Action.KeyboardInputAction.HostEditorAttached(
                            net.devemperor.dictate.state.HostEditorMapper.hostEditorStateFrom(info)));
        }
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
            // B3.3 bridge — same lifecycle event onto the Widget axis.
            // See the symmetric OnImeViewHidden dispatch above for the
            // rationale.
            pipelineBinder.dispatch(
                    net.devemperor.dictate.state.Action.WidgetAction.OnImeViewShown.INSTANCE);
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
            // CR4 (Theme C-R / §9.6 — Spec 2 §13.1 rows 25/26): the
            // imperative resend setVisibility (the onStartInputView
            // Idle-branch V/G mutations §9.6 :1345/:1347) is REMOVED on
            // the bound path — the RESEND-slot `isResendVisible`
            // predicate owns visibility state-reactively. lastAudioExists
            // is carried via state.resend: intra-process it is set by the
            // PipelineDone cascade / onShowResend; across process
            // restarts it is seeded by PipelineRecovery Phase 6 (F-005 —
            // resendableSeedProbe → ResendableSessionPolicy), so a fresh
            // onStartInputView after a service restart with a resendable
            // last session + Pref.ResendButton evaluates the predicate
            // true. The imperative call is the UNBOUND fallback only (no
            // reactive render without a binder). Same for the
            // record-button label (RECORD textResolver owns it on the
            // bound path).
            if (pipelineBinder == null) {
                // Phase 6 of dictate-render-cutover-completion-vol2 — the
                // resolveResendVisibility predicate now reads the
                // orchestrator's `state.PipelineUiState.Idle.INSTANCE`,
                // not the deleted `core.PipelineUiState` sealed class.
                resendButton.setVisibility(KeyboardVisibilityPredicates.resolveResendVisibility(
                        new File(getCacheDir(), DictatePrefsKt.get(sp, Pref.LastFileName.INSTANCE)).exists(),
                        DictatePrefsKt.get(sp, Pref.ResendButton.INSTANCE),
                        RecordingState.Idle.INSTANCE,
                        net.devemperor.dictate.state.PipelineUiState.Idle.INSTANCE));

                // Phase 3 of dictate-render-cutover-completion-vol2 — the
                // Catalog `resolveRecordButtonText` is the single writer
                // for `record_btn.text`. In the unbound-fallback path the
                // catalog hasn't fanned out yet, but the XML-inflated
                // default (`@string/dictate_record`) is already visible
                // and gets overwritten by the catalog on the first
                // render-tick after `attachImeViewBackendIfReady`. The
                // previous `recordButton.setText(getDictateButtonText())`
                // here was a transient violation of the single-writer
                // invariant (AC-A1); removed as part of the C-1
                // post-validation fix.
            }
        }

        // Block 3b: audio-focus is read on-demand from the pref by the
        // controller's startRecording() path — no service-side caching.

        // fill all overlay characters. CR4 (Theme C-R / G-overlay-chars,
        // §13.2 / §9.2 :481-493): on the bound path the armed
        // OverlayCharactersController owns the strip
        // (initialize()+update()); the legacy
        // mainButtonsController.updateOverlayCharacters is the UNBOUND
        // fallback only (RR-2: the legacy drive removed in the same
        // chunk the gate was armed). Same imperative drive cadence
        // (set on input-view-start, not per render-tick).
        int accentColor = DictatePrefsKt.get(sp, Pref.AccentColor.INSTANCE);
        String charactersString = DictatePrefsKt.get(sp, Pref.OverlayCharacters.INSTANCE);
        // CR-DEL (Theme C-R / overlay-chars, §13.2 / §9.2 :481-493): the
        // armed OverlayCharactersController owns the strip
        // (initialize()+update()); the legacy
        // mainButtonsController.updateOverlayCharacters unbound fallback
        // is GONE (MainButtonsController deleted at the
        // point-of-no-return). Same imperative drive cadence (set on
        // input-view-start, not per render-tick).
        if (overlayCharactersController != null) {
            overlayCharactersController.update(charactersString, accentColor);
        }

        // update theme — the "does Pref.Theme force night?" rule is shared
        // with the overlay inflate (F-119); see EffectiveNightMode.kt.
        String theme = DictatePrefsKt.get(sp, Pref.Theme.INSTANCE);
        int keyboardBackgroundColor;
        if (EffectiveNightModeKt.effectiveNight(theme, getResources().getConfiguration())) {
            keyboardBackgroundColor = getResources().getColor(R.color.dictate_keyboard_background_dark, getTheme());
        } else {
            keyboardBackgroundColor = getResources().getColor(R.color.dictate_keyboard_background_light, getTheme());
        }
        // 2026-05-21 indirection-cleanup Chunk 4.2 (B-3 + B-4) — the
        // container background + accent-text-view passes are now owned
        // by `ImeViewBackend.applyKeyboardBackground` / `applyTheme`.
        // The inline `setBackgroundColor` + `setTextColor` loops here
        // are gone — the backend is the single writer for these axes.
        // Pre-bind fallback: if the backend is null (narrow window), do
        // the writes inline so the first-frame paint is correct.
        if (imeViewBackend != null) {
            imeViewBackend.applyKeyboardBackground(keyboardBackgroundColor);
            imeViewBackend.applyTheme(accentColor);
        } else {
            // pre-bind fallback — backend not constructed yet
            dictateKeyboardView.setBackgroundColor(keyboardBackgroundColor);
            emojiPickerCl.setBackgroundColor(keyboardBackgroundColor);
            qwertzContainer.setBackgroundColor(keyboardBackgroundColor);
            emojiPickerTitleTv.setTextColor(accentColor);
        }
        // CR-DEL (Theme C-R / G6 — Spec 2 §9.2 "Theme-Mutation ist eine
        // separate Achse, nicht state-getrieben"): the theme axis now has
        // THREE faithful owners, no `mainButtonsController.applyTheme`
        // remains (AC-RR-6/AC-RR-7 zero-grep):
        //  - ImeViewBackend.applyTheme — the 8 logical buttons + the
        //    container background pass + accent-text pass (Chunk 4.2).
        //  - EditBarController.applyTheme — the 10 edit-row residual
        //    buttons (CR-RGATE's flagged residual; owned by the class
        //    that owns the edit-bar views — sibling-faithful to the §9.2
        //    "separate Theme-Klasse" intent, no extra class, byte-identical
        //    legacy tiers).
        //  - EmojiController.applyTheme — editEmoji + emojiPickerClose.
        // Theme is a non-state, non-double-write-sensitive axis (§9.2);
        // imperative call after re-inflate / accent change.
        if (editBarController != null) {
            editBarController.applyTheme(accentColor);
        }
        if (emojiController != null) {
            emojiController.applyTheme(accentColor);
        }
        // Recording-animation accent: ImeViewBackend forwards into
        // RecordingAnimationController (the recording-axis collapse
        // target); the prompts-visualizer accent is the QWERTZ owner's
        // (G9 BLEIBT). Replaces the deleted
        // recordingUiController.updateAnimationColor (which did both).
        if (imeViewBackend != null) {
            imeViewBackend.updateAccentColor(accentColor);
        }
        if (qwertzRecordingController != null) {
            qwertzRecordingController.updateAnimationColor(accentColor);
        }
        qwertzController.applyColors(accentColor, DictateUtils.darkenColor(accentColor, 0.18f), DictateUtils.darkenColor(accentColor, 0.35f));

        // Show infos for updates, ratings or donations (DB query on
        // background thread). 2026-07-02 (ADR-0006 completion): the
        // trigger conditions stay IME-side (pref + usage-DB reads that a
        // pure reducer cannot perform) but the RESULT is dispatched onto
        // state.infoHints.engagementHint — the InfoBarSelector derives
        // the bar, InfoHintModule owns the dismiss persistence. Pre-bind
        // window: dispatchPipelineActionToOrchestrator no-ops without a
        // binder; the trigger re-fires on the next onStartInputView.
        if (DictatePrefsKt.get(sp, Pref.LastVersionCode.INSTANCE) < BuildConfig.VERSION_CODE) {
            dispatchPipelineActionToOrchestrator(
                    new net.devemperor.dictate.state.Action.InfoHintAction.ShowEngagementHint(
                            net.devemperor.dictate.state.EngagementHint.UPDATE),
                    "ShowEngagementHint(UPDATE)");
        } else {
            dbExecutor.execute(() -> {
                Long totalAudioTimeOrNull = usageDao.getTotalAudioTime();
                long totalAudioTime = totalAudioTimeOrNull != null ? totalAudioTimeOrNull : 0;
                mainHandler.post(() -> {
                    if (totalAudioTime > 180 && totalAudioTime <= 600 && !DictatePrefsKt.get(sp, Pref.FlagHasRated.INSTANCE)) {
                        dispatchPipelineActionToOrchestrator(
                                new net.devemperor.dictate.state.Action.InfoHintAction.ShowEngagementHint(
                                        net.devemperor.dictate.state.EngagementHint.RATE),
                                "ShowEngagementHint(RATE)");
                    } else if (totalAudioTime > 600 && !DictatePrefsKt.get(sp, Pref.FlagHasDonated.INSTANCE)) {
                        dispatchPipelineActionToOrchestrator(
                                new net.devemperor.dictate.state.Action.InfoHintAction.ShowEngagementHint(
                                        net.devemperor.dictate.state.EngagementHint.DONATE),
                                "ShowEngagementHint(DONATE)");
                    }
                });
            });
        }

        // Sync animations preference to QWERTZ keyboard
        qwertzKeyboardView.getKeyPressAnimator().setAnimationsEnabled(
                DictatePrefsKt.get(sp, Pref.Animations.INSTANCE));

        // Sync small mode from prefs and apply visibility + animation.
        // CR-DEL (Theme C-R / RR-2 + G15): Pref.SmallMode is mirrored
        // into state.layout.smallMode by PipelinePrefMirror → the armed
        // visibility controllers + ImeViewBackend (MotionLayout scene)
        // render it reactively. The legacy KSM setSmallMode unbound
        // fallback is GONE (KeyboardStateManager deleted). The
        // edit-numbers rotation animation is the IME-held
        // EditNumbersAnimator (the legacy MainButtonsController delegate
        // is deleted at the point-of-no-return).
        if (editNumbersAnimator != null) {
            editNumbersAnimator.animateSmallModeToggle(false);
        }

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
            // 2026-05-21 indirection-cleanup Chunk 4.4 (A-5) — dispatch
            // the import-recognized action; the RecordingModule reducer
            // emits the atomic LastFileName-persist + TranscriptionAudioFile-clear
            // effect pair through the canonical PrefPersistenceService
            // seam. Pre-bind fallback writes SP inline so the import
            // file-name is not lost if the binder is unavailable in
            // this narrow window.
            if (pipelineBinder != null) {
                pipelineBinder.dispatch(
                        new net.devemperor.dictate.state.Action.RecordingAction.OnAudioFileImported(importedAudio));
            } else {
                // PRE-BIND-FALLBACK: SP-write authorized because dispatcher
                // unavailable (review-fix G2, 2026-05-21). The two writes
                // are sequential — the State-side atomicity guaranteed by
                // the dispatched action's reducer arm cannot be reproduced
                // outside the orchestrator (see RecordingModule
                // PersistImportedAudioFileName KDoc + review-fix G6).
                DictatePrefsKt.put(sp.edit(), Pref.LastFileName.INSTANCE, importedAudio.getName()).apply();
                sp.edit().remove(Pref.TranscriptionAudioFile.INSTANCE.getKey()).apply();
            }
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

    /**
     * CR4 (Theme C-R / G10 — render-path-cutover.md §5 / Spec 2 §9.3) —
     * the authoritative content-area read.
     *
     * <p>On the <b>bound</b> path the SoT is
     * {@code state.layout.contentArea} (owned by {@code LayoutModule};
     * the armed {@link ContentAreaController} renders it). CR-DEL: the
     * legacy {@code KeyboardStateManager} unbound owner is GONE
     * (deleted at the point-of-no-return). Pre-bind the keyboard is in
     * its initial inflate state ({@code MAIN_BUTTONS}) — there is no
     * content-area switch possible before the binder arrives (which it
     * does near-instantly in {@code onCreateInputView}), so the unbound
     * read returns the initial default.</p>
     *
     * <p>{@code contentArea} is <b>not</b> Pref-mirrored
     * ({@code PipelinePrefMirror} mirrors only the 3 LayoutState
     * booleans), so the bound write MUST go through
     * {@code dispatch(LayoutAction.SetContentArea)} — see
     * {@link #setEffectiveContentArea(ContentArea)}.</p>
     */
    private ContentArea effectiveContentArea() {
        if (pipelineBinder != null) {
            return pipelineBinder.getState().getValue().getLayout().getContentArea();
        }
        return ContentArea.MAIN_BUTTONS;
    }

    /**
     * CR4/CR-DEL — set the authoritative content-area. Bound:
     * {@code dispatch(LayoutAction.SetContentArea(area))} →
     * {@code LayoutModule} mutates {@code state.layout.contentArea} →
     * state emit → the armed {@link ContentAreaController} renders the
     * container visibility (and the small-mode structural-rejection in
     * {@code LayoutModule.reduce} is preserved).
     *
     * <p>CR-DEL: the legacy {@code stateManager.setContentArea} unbound
     * fallback is GONE (KeyboardStateManager deleted). Pre-bind there is
     * no content-area state to set and no switch is possible (the binder
     * arrives near-instantly); the call is a no-op until bound, after
     * which the next dispatch + reactive render is authoritative.</p>
     */
    private void setEffectiveContentArea(ContentArea area) {
        if (pipelineBinder != null) {
            pipelineBinder.dispatch(
                    new net.devemperor.dictate.state.Action.LayoutAction.SetContentArea(area));
        }
    }

    private void toggleEmojiPicker() {
        if (effectiveContentArea() == ContentArea.EMOJI_PICKER) {
            hideEmojiPicker();
        } else {
            showEmojiPicker();
        }
    }

    private void showEmojiPicker() {
        setEffectiveContentArea(ContentArea.EMOJI_PICKER);
        emojiPickerCl.bringToFront();
    }

    private void hideEmojiPicker() {
        if (effectiveContentArea() == ContentArea.EMOJI_PICKER) {
            setEffectiveContentArea(ContentArea.MAIN_BUTTONS);
        }
    }

    private void handleSelectAllToggle() {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection == null) return;

        ExtractedText extractedText = inputConnection.getExtractedText(new ExtractedTextRequest(), 0);
        CharSequence selectedText = inputConnection.getSelectedText(0);

        if ((selectedText == null || selectedText.length() == 0)
                && extractedText != null && extractedText.text != null && extractedText.text.length() > 0) {
            insertionService().editAction(EditAction.SELECT_ALL);
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
        if (effectiveContentArea() == ContentArea.QWERTZ) {
            hideQwertzKeyboard();
        } else {
            showQwertzKeyboard();
        }
    }

    private void showQwertzKeyboard() {
        if (qwertzContainer == null) return;
        setEffectiveContentArea(ContentArea.QWERTZ);
        qwertzContainer.bringToFront();
        qwertzController.checkAutoShiftAtCursor();
    }

    private void hideQwertzKeyboard() {
        if (qwertzContainer == null) return;
        if (effectiveContentArea() == ContentArea.QWERTZ) {
            setEffectiveContentArea(ContentArea.MAIN_BUTTONS);
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

/**
     * Dispatches {@link net.devemperor.dictate.state.Action.KeyboardInputAction#EnterKey}
     * after a delay, so terminal emulators (e.g. Termux → Claude Code)
     * treat the Enter as a separate keystroke rather than as part of the
     * pasted text block. For character-by-character mode, the delay is
     * extended past the last character's animated reveal.
     *
     * <p>The dispatched action is processed by {@link
     * net.devemperor.dictate.state.KeyboardInputModule} which reads the
     * current {@link net.devemperor.dictate.state.HostEditorState} and
     * picks the right effect (performEditorAction / commitText("\n") /
     * sendKeyEvent). Semantically equivalent to the legacy
     * performEnterAction read of {@code getCurrentInputEditorInfo()} at
     * fire-time, because the orchestrator's host-editor snapshot is
     * refreshed on every {@code onStartInputView}.
     */
    /** Base per-character delay (ms) of the slow-output animation at speed 5. */
    private static final long SLOW_OUTPUT_BASE_DELAY_MS = 20L;
    /** Speed value that maps to {@link #SLOW_OUTPUT_BASE_DELAY_MS} (1× speed). */
    private static final float SLOW_OUTPUT_SPEED_REFERENCE = 5f;

    /**
     * Per-character delay (ms) of the slow-output animation for the 1-based
     * char {@code index} at the user's {@code speed} pref. Single source of
     * truth shared by the {@link SlowOutputAnimator} wiring and
     * {@link #scheduleAutoEnter} (which must fire <i>after</i> the last
     * animated character lands) so the two cannot drift out of sync.
     */
    private static long slowOutputDelayForIndex(int index, int speed) {
        return (long) (index * (SLOW_OUTPUT_BASE_DELAY_MS / (speed / SLOW_OUTPUT_SPEED_REFERENCE)));
    }

    private void scheduleAutoEnter(String output) {
        Runnable dispatchEnter = () -> {
            if (pipelineBinder != null) {
                pipelineBinder.dispatch(
                        net.devemperor.dictate.state.Action.KeyboardInputAction.EnterKey.INSTANCE);
            } else {
                // Pre-bind fallback (rare, post-boot narrow window):
                // a physical KEYCODE_ENTER preserves the legacy
                // "no editor info → sendKeyEvent" behaviour and
                // produces DOM keydown/keyup in WebViews.
                sendPhysicalEnterFallback();
            }
        };
        if (mainHandler == null) {
            // No handler available — fire immediately
            dispatchEnter.run();
            return;
        }

        long baseDelay = DictatePrefsKt.get(sp, Pref.AutoEnterDelay.INSTANCE);
        if (!DictatePrefsKt.get(sp, Pref.InstantOutput.INSTANCE) && output.length() > 0) {
            // Character-by-character: add delay after the last character finishes
            int speed = DictatePrefsKt.get(sp, Pref.OutputSpeed.INSTANCE);
            long lastCharDelay = slowOutputDelayForIndex(output.length() - 1, speed);
            baseDelay += lastCharDelay;
        }

        mainHandler.postDelayed(dispatchEnter, baseDelay);
    }

    /**
     * Pre-bind fallback used by QWERTZ-Enter and the
     * {@link #scheduleAutoEnter} runnable when the orchestrator binder
     * is still null (sub-second window right after IME boot). Sends a
     * physical KEYCODE_ENTER directly through the live
     * {@code InputConnection} — mirrors the
     * {@link net.devemperor.dictate.state.KeyboardInputModule.Effect.SendPhysicalEnter}
     * effect the orchestrator emits in the same scenario, so the
     * pre-bind and bound paths behave identically.
     */
    private void sendPhysicalEnterFallback() {
        // Routed through the single InsertionService (control → executeControlOp
        // → sendKeyEvent). The service is lazily built and independent of the
        // orchestrator binder, so it is available even on this pre-bind path.
        insertionService().control(ControlOp.PhysicalEnter.INSTANCE);
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
            // 2026-05-22 — discard the R-1 config snapshot keyed by the
            // current session id (read off state.recording, the single
            // source of truth — same pattern as stopRecording()). The
            // previous newPathRecordingSessionId field was only populated
            // on the QWERTZ start-path and stayed null whenever recording
            // was kicked off from the catalog (main keyboard) surface, so
            // the discard silently skipped the snapshot cleanup for those
            // sessions.
            if (imePipelineConfigResolver != null) {
                net.devemperor.dictate.state.RecordingState rs =
                        pipelineBinder.getState().getValue().getRecording();
                String sessionId = null;
                if (rs instanceof net.devemperor.dictate.state.RecordingState.Active) {
                    sessionId = ((net.devemperor.dictate.state.RecordingState.Active) rs).getSessionId();
                } else if (rs instanceof net.devemperor.dictate.state.RecordingState.Paused) {
                    sessionId = ((net.devemperor.dictate.state.RecordingState.Paused) rs).getSessionId();
                } else if (rs instanceof net.devemperor.dictate.state.RecordingState.Preparing) {
                    sessionId = ((net.devemperor.dictate.state.RecordingState.Preparing) rs).getSessionId();
                }
                if (sessionId != null) {
                    imePipelineConfigResolver.discard(sessionId);
                }
            }
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

        // Pre-Dispatch-Allocation (Spec 1 §4.11.4, R.2). The initial
        // segment file is minted via the AudioFileRepository (F-000 fix),
        // which names it `sess_{sid}_seg1.m4a` under cacheDir/audio/ —
        // the same `sess_*` prefix the multi-segment muxer scans for.
        //
        // The legacy fixed `cacheDir/audio.m4a` path is migrated by
        // LegacyAudioFileMigration on the next Service boot.
        //
        // The repository is only available once the Service binder is up
        // (`onServiceConnected`). The early-tap defensive path below
        // toasts and bails — the user retries once the bind lands.
        if (pipelineBinder == null) {
            android.widget.Toast.makeText(
                    this, R.string.dictate_service_not_ready,
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        // B2 / ADR-0008 §"Auto-Continuation" — before allocating a fresh
        // recording session, ask the ContinuationLookup whether the most
        // recent RECORDING_INTERRUPTED row is fresh enough to continue
        // (within Pref.ContinuationFreshnessMs). A non-null result reuses
        // the existing session-id, the next pre-allocated segment file,
        // and the codec params read off the prior segment.
        // See resolveRecordAction (LayoutCatalog resolver) for the
        // symmetric Kotlin-side check — both paths use the same lookup
        // so the behavior is byte-identical across UI surfaces.
        net.devemperor.dictate.state.EligibleContinuation continuation =
                pipelineBinder.getModuleServices().getContinuationLookup().lookup();
        if (continuation != null) {
            pipelineBinder.dispatch(
                    new net.devemperor.dictate.state.Action.RecordingAction.StartRecordingContinuation(
                            net.devemperor.dictate.state.InsertionTarget.INPUT_CONNECTION,
                            continuation.getNextSegmentFile(),
                            continuation.getSessionId(),
                            continuation.getCodecParams()));
            return;
        }

        // D-14 (C9-C2): the allocated file is handed to the orchestrator
        // via StartRecording and becomes RecordingState's authoritative
        // payload (Spec 1 §15.2). The IME keeps only this method-local
        // reference (LastFileName mirror + the action arg); the
        // send-tap reads it back from state.recording, not an IME field.
        //
        // F-000 (2026-07-03) — the initial file MUST be allocated via
        // AudioFileRepository.allocateFirst(sessionId), NOT the legacy
        // AudioFileFactory. The multi-segment muxer (RecordingHardwareAdapter
        // pre-arms rolling segments via allocateNext → sess_{sid}_seg*) only
        // sees files under the `sess_{sid}_seg*` prefix; the legacy factory's
        // `rec_{ts}_{uuid}.m4a` name is invisible to segments(sid). Allocating
        // through the factory here caused silent, unrecoverable audio loss on
        // the QWERTZ record button and instant-prompt chip surfaces: long
        // recordings dropped the first chunk, short recordings uploaded the
        // pre-armed 0-byte sess_seg1 while the real rec_* audio was deleted.
        //
        // The sessionId is minted BEFORE the allocate so the repository can
        // name the initial file `sess_{sid}_seg1.m4a` — byte-identical to the
        // catalog start path (ActionResolvers.resolveStartRecordingFromIdle).
        // Both surfaces now share one allocation contract.
        String preAllocatedId = java.util.UUID.randomUUID().toString();
        File audioFile;
        try {
            audioFile = pipelineBinder.getModuleServices()
                    .getAudioFileRepository()
                    .allocateFirst(preAllocatedId);
        } catch (java.io.IOException e) {
            // Storage full / FS permission. Surface a user-visible
            // toast and bail out — the reducer never sees the failure
            // (R.2 Pure-Reducer invariant: IO lives in the resolver).
            Log.w("DictateIME", "AudioFileRepository.allocateFirst failed", e);
            android.widget.Toast.makeText(
                    this, R.string.dictate_storage_full,
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }

        // 2026-05-21 indirection-cleanup Chunk 4.4 (A-4) — `Pref.LastFileName`
        // is persisted by `RecordingModule.Effect.PersistLastFileName`
        // emitted from the `StartRecording` reducer arm (both BT and
        // non-BT branches). No inline SP write here.

        // The orchestrator's RecordingModule drives the real
        // RecordingHardwareAdapter MediaRecorder (AC-2). The pre-allocated
        // UUID is the FSM's single sessionId source (F-10); it is carried
        // into RecordingState.Preparing → Active → Paused and read back on
        // the payload-less StopRecordingAndSend (FN-4). useBluetooth is read
        // off Pref into AudioState by the orchestrator (the StartRecording
        // reducer reads ctx.global.audio.useBluetoothMic — already
        // pref-mirrored), so it is NOT threaded on the action.
        pipelineBinder.dispatch(new net.devemperor.dictate.state.Action.RecordingAction.StartRecording(
                net.devemperor.dictate.state.InsertionTarget.INPUT_CONNECTION,
                audioFile,
                preAllocatedId));
    }

    private void stopRecording() {
        // 2026-05-22 — sessionId is read from state.recording (the orchestrator-
        // authoritative source — same pattern as togglePauseEffectiveRecording
        // and prepareCatalogStopRecordingIfActive). The previous read from a
        // private newPathRecordingSessionId field caused the QWERTZ-Send-Bug:
        // the catalog Start path (ActionResolvers.resolveRecordAction) mints
        // its own UUID and does NOT route through this IME.startRecording(),
        // so the field stayed null whenever the user started recording from
        // the main keyboard surface — and the QWERTZ Send-tap then hit the
        // "no in-flight session — skipping send" bail-out. Sourcing the id
        // from state.recording unifies the two surfaces onto a single source.
        if (pipelineBinder == null || imePipelineConfigResolver == null) {
            Log.w("DictateIME",
                    "stopRecording (new path): binder/resolver missing — skipping send");
            return;
        }

        // B2-VAL-W1 F-3 — sendable-state guard BEFORE the destructive
        // pre-dispatch. `StopRecordingAndSend` from a non-bearing
        // recording state (still `Preparing` — BT-SCO wait unresolved,
        // or a slow `MediaRecorder.prepare()`) is a reducer no-op
        // (RecordingModule has no `Preparing + StopRecordingAndSend`
        // arm → Rejected). But `captureFreshConfigSnapshot` and
        // `primePipelineUiForNewPath` are irreversible *before* any FSM
        // check: the former consumes/resets one-shot flags (livePrompt /
        // autoSwitchKeyboard / pendingLivePromptChain), the latter shows
        // the "Sending…" keyboard. The F-1/F-2 Preparing-SCO redesign
        // *widens* the Preparing window (a BT-mic recording can stay
        // `Preparing(awaitingSco)` for up to 2500 ms), so a
        // Send-while-Preparing race is materially more likely. Bail
        // cleanly here — nothing destructive has run yet.
        net.devemperor.dictate.state.RecordingState rs =
                pipelineBinder.getState().getValue().getRecording();
        String sessionId;
        File recordingAudioFile;
        if (rs instanceof net.devemperor.dictate.state.RecordingState.Active) {
            net.devemperor.dictate.state.RecordingState.Active active =
                    (net.devemperor.dictate.state.RecordingState.Active) rs;
            sessionId = active.getSessionId();
            recordingAudioFile = active.getAudioFile();
        } else if (rs instanceof net.devemperor.dictate.state.RecordingState.Paused) {
            net.devemperor.dictate.state.RecordingState.Paused paused =
                    (net.devemperor.dictate.state.RecordingState.Paused) rs;
            sessionId = paused.getSessionId();
            recordingAudioFile = paused.getAudioFile();
        } else {
            Log.w("DictateIME",
                    "stopRecording (new path): recording not Active/Paused "
                            + "(still Preparing?) — skipping send, recording preserved");
            return;
        }
        if (sessionId == null || recordingAudioFile == null) {
            Log.w("DictateIME",
                    "stopRecording (new path): missing sessionId/audioFile in "
                            + "state.recording — skipping send, recording preserved");
            return;
        }

        captureFreshConfigSnapshot(sessionId, recordingAudioFile);
        // Drive the legacy keyboard pipeline UI (KeyboardUiController is
        // still the render path until Theme-C/C3 retires it) so the
        // keyboard shows "Sending…"/progress exactly as the legacy
        // trigger did. The orchestrator owns state.pipeline; this is the
        // thin IME-side UI bookkeeping the legacy path also performed.
        primePipelineUiForNewPath();

        pipelineBinder.dispatch(
                net.devemperor.dictate.state.Action.RecordingAction.StopRecordingAndSend.INSTANCE);
    }

    /**
     * Post-cutover hotfix — IME-side affordance for the catalog-driven
     * RECORD click on Active|Paused (the "Stop &amp; Send" tap).
     *
     * <p><strong>Two call-sites (B-A symmetry).</strong> This helper is
     * the IME-side R-1 snapshot trigger for the {@code Stop &amp; Send}
     * gesture, regardless of which catalog slot the user tapped:
     * <ul>
     *   <li>{@link LogicalButtonId#RECORD} — the keyboard's record button
     *       click on {@code Active|Paused} (catalog returns
     *       {@code StopRecordingAndSend}).</li>
     *   <li>{@link LogicalButtonId#OVERLAY_RECORD} — the floating-widget
     *       merged record/send button click on {@code Active|Paused}
     *       (catalog returns the same {@code StopRecordingAndSend} via
     *       {@code resolveOverlayRecordAction} composition).</li>
     * </ul>
     * Both IDs fan in through the {@code imeSideAffordance} lambda
     * (declared once in {@code onCreateInputView}, registered both on
     * {@code ImeViewBackend} and {@code OverlayBackend} so the lambda is
     * a single behavioural seam). The {@code ImeViewBackend} click branch
     * fires {@code imeSideAffordance(RECORD, false)} and the
     * {@code OverlayBackend} click branch fires
     * {@code imeSideAffordance(OVERLAY_RECORD, false)}; the lambda
     * matches both IDs and dispatches into this helper. The structural
     * symmetry is regression-locked by
     * {@code CutoverArchitectureInvariantTest.affordanceHookHandlesBothRecordIds}
     * (dictate-pipeline-render-and-state-unification §5.4 / AC-P-4).</p>
     *
     * <p>Captures the IME-runtime {@code JobRequest} snapshot and primes
     * the pipeline-step-row UI <em>before</em> the catalog dispatches
     * {@code StopRecordingAndSend}. Symmetric to the RESEND affordance
     * (see {@code ImeViewBackend.wireStaticHandlers} click branch +
     * the {@code imeSideAffordance} lambda above; render-path-cutover.md
     * §7 A1 — "IME-side affordances with no FSM/dispatch
     * representation").</p>
     *
     * <p>Without this affordance the orchestrator's
     * {@code PipelineModule.SubmitPipeline} →
     * {@code PipelineRunnerSubsystemAdapter} → {@code resolveFresh}
     * runs asynchronously off the catalog dispatch and finds no
     * snapshot — the loud
     * {@link ImePipelineConfigResolver}{@code .resolveFresh}
     * {@code UnsupportedOperationException} tripwire fires (R-1
     * silent-data-loss class), gets caught as an {@code EffectFailure},
     * and {@code state.pipeline} hangs in {@code Preparing} forever:
     * endless "Sending…" with no step-rows and no progress UI.</p>
     *
     * <p><strong>sessionId source.</strong> The sessionId is read from
     * {@code state.recording.{Active,Paused}} — the orchestrator-
     * authoritative id — the single source for both the catalog and
     * QWERTZ surfaces (2026-05-22 unification; the previous IME-private
     * {@code newPathRecordingSessionId} field caused the QWERTZ-Send-Bug
     * because the catalog Start path mints its own UUID via
     * {@code ActionResolvers.newSessionId} and never populated the
     * field). {@code state.recording.Active.sessionId} is the id the
     * catalog dispatch carries forward into {@code state.pipeline
     * .Preparing.sessionId} and then {@code Effect.SubmitPipeline(
     * sessionId)}, so it is the only id the helper can match.</p>
     *
     * <p><strong>Self-gating</strong> — when state is not Active|Paused
     * (catalog resolver returns null for those states), or when the
     * binder / config-resolver / audioFile / sessionId are missing
     * (binder-dropped / preparing-race), this is a no-op. The
     * defensive bail-outs mirror {@link #stopRecording()}'s pre-dispatch
     * guards so a stale tap cannot orphan the recording.</p>
     *
     * <p><strong>Dispatch ownership</strong> — this helper performs the
     * IME-side imperative work only; the catalog driver
     * ({@code ImeViewBackend} click handler) is the sole dispatcher of
     * {@code StopRecordingAndSend}. The {@link #stopRecording()} method
     * still does both (snapshot + prime + dispatch) because the QWERTZ
     * onSend path bypasses the catalog and needs all three.</p>
     *
     * @see CutoverArchitectureInvariantTest the architecture-invariant
     *     test that locks against re-introducing the asymmetry by
     *     requiring every catalog-emitting RECORD/RESEND click-site to
     *     also fire an affordance hook.
     */
    private void prepareCatalogStopRecordingIfActive() {
        if (pipelineBinder == null || imePipelineConfigResolver == null) {
            return;
        }
        net.devemperor.dictate.state.RecordingState rs =
                pipelineBinder.getState().getValue().getRecording();
        String sessionId;
        File recordingAudioFile;
        if (rs instanceof net.devemperor.dictate.state.RecordingState.Active) {
            net.devemperor.dictate.state.RecordingState.Active active =
                    (net.devemperor.dictate.state.RecordingState.Active) rs;
            sessionId = active.getSessionId();
            recordingAudioFile = active.getAudioFile();
        } else if (rs instanceof net.devemperor.dictate.state.RecordingState.Paused) {
            net.devemperor.dictate.state.RecordingState.Paused paused =
                    (net.devemperor.dictate.state.RecordingState.Paused) rs;
            sessionId = paused.getSessionId();
            recordingAudioFile = paused.getAudioFile();
        } else {
            // Not Active|Paused — catalog resolver returns null too; nothing to do.
            return;
        }
        if (sessionId == null || recordingAudioFile == null) {
            return;
        }

        Log.d("DictateIME",
                "prepareCatalogStopRecordingIfActive: snapshot for sessionId="
                        + sessionId + " (audioFile=" + recordingAudioFile.getName() + ")");
        captureFreshConfigSnapshot(sessionId, recordingAudioFile);
        primePipelineUiForNewPath();
    }

    /**
     * F-003 (2026-07-03) — prime the auto-apply prompt queue for a
     * catalog-started recording.
     *
     * <p>The main-keyboard record button and the overlay widget record
     * button both start recording via the catalog
     * ({@code ActionResolvers.resolveStartRecordingFromIdle} →
     * {@code StartRecording} dispatch), which does NOT route through the
     * IME's {@link #startRecording()} — the only other caller of
     * {@link PromptQueueManager#prepareAutoApplyQueue()}. Without this the
     * catalog path recorded without front-loading the user's
     * {@code autoApply=true} prompts, so those dictations transcribed
     * verbatim (F-003).</p>
     *
     * <p>Self-gating on recording {@code Idle}: this fires from the
     * shared RECORD/OVERLAY_RECORD affordance branch, which also handles
     * the Active|Paused "stop &amp; send" case — priming only makes sense
     * at record-START. {@code prepareAutoApplyQueue()} is itself
     * idempotent + a no-op when rewording is disabled, but the Idle guard
     * keeps the send-tap from needlessly re-ordering the queue.</p>
     */
    private void prepareCatalogAutoApplyQueueIfIdle() {
        if (pipelineBinder == null) return;
        net.devemperor.dictate.state.RecordingState rs =
                pipelineBinder.getState().getValue().getRecording();
        if (rs instanceof net.devemperor.dictate.state.RecordingState.Idle) {
            promptQueueManager.prepareAutoApplyQueue();
        }
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

        // ADR-0013: a review-refinement recording (S2) is transcription-only —
        // it must never run a turn or trigger a review/pending, so it forces
        // ALWAYS_INSERT. A normal recording carries the user's ambiguity mode.
        boolean transcriptionOnly = reviewRefinementTargetSessionId != null;
        net.devemperor.dictate.preferences.AmbiguityMode ambiguityMode = transcriptionOnly
                ? net.devemperor.dictate.preferences.AmbiguityMode.ALWAYS_INSERT
                : currentAmbiguityMode();
        // ADR-0014: tag the S2 review-refinement carrier so the in-keyboard
        // history panel can hide it (the full-screen activity still shows it).
        net.devemperor.dictate.database.entity.SessionOrigin origin = transcriptionOnly
                ? net.devemperor.dictate.database.entity.SessionOrigin.REVIEW_REFINEMENT
                : net.devemperor.dictate.database.entity.SessionOrigin.KEYBOARD;
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
                        showResend,
                        ambiguityMode,
                        transcriptionOnly,
                        origin));

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
     * C5 — drive the pipeline step-row UI for the new path so the
     * keyboard still shows the "Sending…"/progress affordance the legacy
     * pre-C7 fresh-recording trigger set up. Shared by
     * {@link #stopRecording()} and
     * {@link #transcribeImportedAudioFileViaOrchestrator()} (C7-IMPL-1).
     * The orchestrator owns the authoritative {@code state.pipeline};
     * this is the same thin UI bookkeeping the legacy trigger performed,
     * now driving the relocated
     * {@link net.devemperor.dictate.state.render.PipelineStepRowRenderer}
     * (G13 BLEIBT, Spec 1 §9.2 View-side).
     */
    private void primePipelineUiForNewPath() {
        try {
            // Phase 5.B (Vol 2): step-row priming was the legacy View-side
            // echo of `state.pipeline` transitions. The reactive renderer
            // now paints Preparing/Running directly from
            // `state.pipeline.stepHistory`; the orchestrator's
            // PipelineModule reduces `TriggerPipeline → Preparing` and
            // (on the first runner-callback) `Preparing → Running` via the
            // `onStepStarted_dispatchOrchestratorSync` bridge. No
            // imperative step-row drive remains.
            //
            // 2026-07-02 (ADR-0006 completion) — the legacy
            // infoBarController.dismiss() is replaced by InfoHintModule's
            // cross-module observer (pipeline Idle → non-Idle clears
            // state.infoHints).
            updatePromptButtonsEnabledState();
        } catch (RuntimeException e) {
            // UI bookkeeping is best-effort -- a view-recreation race must
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
        // 2026-07-02 — stale info-hints clear reactively via
        // InfoHintModule's observer once the pipeline leaves Idle.
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

        // Phase 5.B (Vol 2): the legacy imperative
        // `pipelineStepRowRenderer.startPipeline(1, ..., 0)` reset is gone.
        // Step-row repaint is driven by `state.pipeline.stepHistory` via
        // the reactive renderer; the orchestrator's Preparing/Running FSM
        // transitions are dispatched by `onStepStarted_dispatchOrchestratorSync`
        // (it reads the previous pipeline's `Running.autoEnterActive`
        // automatically and falls back to `Pref.AutoEnter` when the
        // previous renderer state is not Running -- which is the
        // direct-prompt-button entry case).

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

    /**
     * Post-cutover D4 hotfix — bridge the legacy
     * {@link PipelineOrchestrator.PipelineCallback} events into the
     * orchestrator's {@code state.pipeline} FSM via Action dispatches.
     *
     * <p>Pre-hotfix the cutover left this bridge missing: the legacy
     * {@code PipelineOrchestrator} ran its steps and called back into the
     * IME, but no code path translated those calls into
     * {@code Action.PipelineAction.StartPipeline / StepStarted /
     * StepCompleted / PipelineDone / PipelineFailed}. Consequence:
     * {@code state.pipeline} was driven into {@code Preparing} by the
     * catalog's send-tap and then froze there forever (no actor advanced
     * it). Two independent pipeline FSMs co-existed: (a) the imperative
     * {@code PipelineStepRowRenderer.state} (driven via
     * {@code primePipelineUiForNewPath} + the {@code addRunningStep /
     * completeStep / failStep} calls below) and (b) the orchestrator's
     * {@code state.pipeline} (driven by Action dispatches and consumed by
     * every {@code LayoutCatalog} resolver). Without sync, the catalog
     * label stayed "Sende" forever, the next recording's RECORD tap hit
     * a stuck FSM, the FGS notification stuck on "preparing".
     *
     * <p>The fix invokes the existing imperative renderer paths AND
     * dispatches mirror Actions to the orchestrator. The dispatches read
     * {@code sessionId} off {@code state.pipeline} (the orchestrator's
     * authoritative id, set by the {@code TriggerPipeline → Preparing}
     * reducer arm); {@code totalSteps} / {@code autoEnterActive} are
     * taken from the renderer's own {@code Running} snapshot (it was
     * just primed with these by {@link #primePipelineUiForNewPath()} on
     * the same main thread). All dispatches are wrapped in defensive
     * try/catch — a misbehaving dispatch must NOT abort the existing
     * imperative UI path (the imperative renderer is what the user
     * actually sees today; the orchestrator-state sync is additive).
     *
     * <p>Architecture lock: a follow-up
     * {@code CutoverArchitectureInvariantTest} assertion (g) will pin
     * the existence of these dispatches so future refactors cannot
     * silently re-introduce the "imperative-only" half-cutover.
     */
    private void dispatchPipelineActionToOrchestrator(net.devemperor.dictate.state.Action action, String tag) {
        if (pipelineBinder == null) return;
        try {
            pipelineBinder.dispatch(action);
        } catch (Throwable t) {
            Log.w("DictateIME", tag + " dispatch failed (orchestrator-sync best-effort)", t);
        }
    }

    private void onStepStarted_dispatchOrchestratorSync(String stepName) {
        if (pipelineBinder == null) return;
        net.devemperor.dictate.state.PipelineUiState p = getPipelinePhase();
        // First step -> transition Preparing -> Running via StartPipeline.
        //
        // Phase 5.B (Vol 2): the legacy reader of the imperative renderer
        // state is gone -- the renderer is reactive and has no totalSteps /
        // autoEnter accessor. Recompute the values directly from the same
        // sources the legacy `primePipelineUiForNewPath` used (the
        // `auto-format +1` rule + Pref.AutoEnter). This is byte-equivalent
        // to the pre-Phase-5.B fallback arm.
        if (p instanceof net.devemperor.dictate.state.PipelineUiState.Preparing) {
            net.devemperor.dictate.state.PipelineUiState.Preparing prep =
                    (net.devemperor.dictate.state.PipelineUiState.Preparing) p;
            int totalSteps = 1;
            if (autoFormattingService.isEnabled()) totalSteps++;
            totalSteps += promptQueueManager.getQueuedIds().size();
            boolean autoEnter = DictatePrefsKt.get(sp, Pref.AutoEnter.INSTANCE);
            dispatchPipelineActionToOrchestrator(
                    new net.devemperor.dictate.state.Action.PipelineAction.StartPipeline(
                            prep.getSessionId(), totalSteps, autoEnter),
                    "StartPipeline");
        }
        // Then dispatch StepStarted (read sessionId off state.pipeline -- may
        // be Running now after the StartPipeline above, or already-Running
        // for non-first steps).
        net.devemperor.dictate.state.PipelineUiState p2 = getPipelinePhase();
        if (p2 instanceof net.devemperor.dictate.state.PipelineUiState.Running) {
            String sid = ((net.devemperor.dictate.state.PipelineUiState.Running) p2).getSessionId();
            dispatchPipelineActionToOrchestrator(
                    new net.devemperor.dictate.state.Action.PipelineAction.StepStarted(sid, stepName),
                    "StepStarted");
        }
    }

    private String currentPipelineSessionId() {
        net.devemperor.dictate.state.PipelineUiState p = getPipelinePhase();
        if (p instanceof net.devemperor.dictate.state.PipelineUiState.Running) {
            return ((net.devemperor.dictate.state.PipelineUiState.Running) p).getSessionId();
        }
        if (p instanceof net.devemperor.dictate.state.PipelineUiState.Preparing) {
            return ((net.devemperor.dictate.state.PipelineUiState.Preparing) p).getSessionId();
        }
        return null;
    }

    /**
     * Phase-2 cutover helper — single accessor that returns the
     * orchestrator's current {@code state.PipelineUiState} (returns
     * {@code PipelineUiState.Idle} when the binder is not attached).
     *
     * <p>Bundles the ~8 call sites that today open-code
     * {@code pipelineBinder.getState().getValue().getPipeline()} +
     * {@code instanceof} casts in this file. Reduces the cutover
     * surface area for Phase 5 of
     * {@code 2026-05-21 - dictate-render-cutover-completion-vol2}, where
     * every pipeline-read path consolidates onto the orchestrator
     * sealed class.
     */
    private net.devemperor.dictate.state.PipelineUiState getPipelinePhase() {
        if (pipelineBinder == null) return net.devemperor.dictate.state.PipelineUiState.Idle.INSTANCE;
        return pipelineBinder.getState().getValue().getPipeline();
    }

    /**
     * Host-commit guard (B3.5 / plan §4 B3, ADR-0008 §"Send-during-widget").
     *
     * Returns {@code true} when the IME may safely
     * {@link InputConnection#commitText(CharSequence, int)} text into the
     * current input target — i.e. when the IME-View is on screen and is
     * therefore the active surface backing {@code getCurrentInputConnection()}
     * with a real, focused host field. Returns {@code false} when the
     * IME-View is hidden: the live {@code InputConnection} is then either
     * {@code null} or belongs to whatever window happens to have focus —
     * committing transcript into it would leak the user's text into the
     * wrong field.
     *
     * <p><b>Axis correctness (2026-05-22).</b> This guard keys on
     * {@code state.imeViewVisible}, NOT on {@code state.widget}. The two
     * are orthogonal: the floating widget can be visible <i>while the
     * IME-View is also visible</i> (the keyboard and the widget on screen
     * together — the normal "dictate with the widget floating over the
     * keyboard" flow). The earlier {@code widget instanceof Visible} check
     * wrongly blocked every commit while the widget floated, so a widget
     * Send produced a transcript that never reached the focused field —
     * it was always deferred to Pending-Insert. {@code imeViewVisible} is
     * the single axis that actually answers "is there a valid host field
     * to commit into", and it is the same axis the overlay Send
     * action-resolver ({@code resolveOverlayRecordAction}) gates on, so
     * the action layer and the commit layer now agree.
     *
     * Callers that have a pending text to insert MUST persist it via the
     * Pending-Insert info-bar surface (B4) when this returns {@code false}
     * so the user can re-attach the IME and tap to insert.
     *
     * Defensive: when {@code pipelineBinder} is null (pre-bind window
     * during service start-up), returns {@code true} — the bind establishes
     * before any pipeline can complete, but a stray commit in the gap is
     * structurally impossible and the safe default is "allow".
     */
    private boolean canCommitToHost() {
        if (pipelineBinder == null) return true;
        return net.devemperor.dictate.state.DictateUiStateKt.getCanCommitToHost(
                pipelineBinder.getState().getValue());
    }

    @Override
    public void onStepStarted(@androidx.annotation.NonNull String stepName) {
        mainHandler.post(() -> {
            // Phase 5.B (Vol 2): dispatch StartPipeline (first step) +
            // StepStarted so state.pipeline tracks the live FSM. The
            // reactive PipelineStepRowRenderer paints the inflated step
            // row on the next ImeViewBackend.render emit -- no imperative
            // renderer drive remains.
            onStepStarted_dispatchOrchestratorSync(stepName);
        });
    }

    @Override
    public void onStepCompleted(@androidx.annotation.NonNull String stepName, long durationMs) {
        mainHandler.post(() -> {
            // Phase 5.B (Vol 2): orchestrator-sync only -- StepCompleted
            // advances state.pipeline.completedSteps + restamps elapsedMs;
            // the reactive renderer finalises the row + duration column
            // from the new state.pipeline.stepHistory emit. The legacy
            // imperative completeStep call is gone (the stepName +
            // durationMs payload reaches the renderer through the
            // StepCompleted reducer arm, which finalises the last RUNNING
            // entry to COMPLETED with the duration; see DictateUiState
            // KDoc on stepHistory).
            String sid = currentPipelineSessionId();
            if (sid != null) {
                dispatchPipelineActionToOrchestrator(
                        new net.devemperor.dictate.state.Action.PipelineAction.StepCompleted(sid),
                        "StepCompleted");
            }
        });
    }

    @Override
    public void onStepFailed(@androidx.annotation.NonNull String stepName) {
        mainHandler.post(() -> {
            // Phase 5.B (Vol 2): orchestrator-sync only -- StepFailed
            // finalises the last RUNNING entry in state.pipeline.stepHistory
            // to FAILED + sets Running.hasFailure=true. The reactive
            // renderer repaints the row + the record-button colour
            // (RecordButtonColorController side-channel) on the next emit.
            String sid = currentPipelineSessionId();
            if (sid != null) {
                dispatchPipelineActionToOrchestrator(
                        new net.devemperor.dictate.state.Action.PipelineAction.StepFailed(sid, stepName),
                        "StepFailed");
            }
        });
    }

    @Override
    public void onPipelineCompleted(@androidx.annotation.NonNull String text, @androidx.annotation.NonNull InsertionSource source,
                                    @androidx.annotation.Nullable net.devemperor.dictate.ai.conversation.PostProcessingReview review) {
        // ADR-0013: `review` carries the post-processing verdict. Three
        // outcomes decided here (never blocking on the pipeline thread):
        // (A) live-prompt chain, (B) review-refinement carrier S2 → continue
        // S1, (C) verdict says review AND the IME is visible → hold in the
        // panel; otherwise the legacy insert/defer path.
        mainHandler.post(() -> {
            // Capture `sid` before any dispatch — `currentPipelineSessionId()`
            // reads from `state.pipeline`, which the PipelineDone dispatch
            // below resets to Idle.
            String sid = currentPipelineSessionId();
            String refineTarget = reviewRefinementTargetSessionId;
            if (pendingLivePromptChain) {
                // Live prompt: transcription result becomes the prompt for a completion call.
                // Dispatch PipelineDone first so the chained completion's
                // primePipelineUiForNewPath sees a clean Idle state.
                // committed=true: live-prompt chains do not call
                // commitTextToInputConnection on this transcript (the
                // text is fed into a follow-up completion call), so
                // there is no commit to gate on — the legacy MarkSessionInserted
                // path stays correct for this branch.
                if (sid != null) {
                    dispatchPipelineActionToOrchestrator(
                            new net.devemperor.dictate.state.Action.PipelineAction.PipelineDone(sid, text, true),
                            "PipelineDone");
                }
                pendingLivePromptChain = false;
                if (pipelineStepRowRenderer == null) return;  // View recreation not yet complete
                PromptEntity liveEntity = new PromptEntity(-1, Integer.MIN_VALUE, "", text, true, false);
                runStandalonePromptViaOrchestrator(liveEntity);
            } else if (refineTarget != null) {
                // (B) This recording (S2) is a review refinement: its transcript
                // becomes the next user turn on the S1 conversation. Do NOT
                // insert. Close S2's FSM (committed=true, no host commit — like
                // the live-prompt branch), mark the panel refining, and enqueue
                // the continuation on the run-queue (ADR-0009).
                reviewRefinementTargetSessionId = null;
                if (sid != null) {
                    dispatchPipelineActionToOrchestrator(
                            new net.devemperor.dictate.state.Action.PipelineAction.PipelineDone(sid, text, true),
                            "PipelineDone");
                }
                dispatchPipelineActionToOrchestrator(
                        net.devemperor.dictate.state.Action.ReviewPanelAction.MarkRefining.INSTANCE,
                        "ReviewPanel.MarkRefining");
                startReviewContinuationJob(refineTarget, text);
            } else if (review != null
                    && canShowReviewPanel()
                    && net.devemperor.dictate.ai.conversation.ReviewDecision.INSTANCE.decide(
                            currentAmbiguityMode(), review.getNeedsClarification(), review.getMessage())
                        == net.devemperor.dictate.ai.conversation.Verdict.REVIEW) {
                // (C) Ambiguous + IME visible → hold the output in the review
                // panel instead of inserting. PipelineDone(heldForReview) moves
                // the FSM Idle WITHOUT inserting or creating a pending part; the
                // reviewPanel axis owns the surface.
                if (sid != null) {
                    dispatchPipelineActionToOrchestrator(
                            new net.devemperor.dictate.state.Action.PipelineAction.PipelineDone(sid, text, false, true),
                            "PipelineDone(heldForReview)");
                    dispatchPipelineActionToOrchestrator(
                            new net.devemperor.dictate.state.Action.ReviewPanelAction.Show(sid, text, review.getMessage()),
                            "ReviewPanel.Show");
                }
            } else {
                // RESERVED SEAM — Auto-send-to-Windows dispatch (M1/M4, later
                // Windows package) docks HERE, at the terminal delivery point of
                // a completed pipeline. When the "auto-send to Windows" mode is
                // active this branch routes the final text to the Windows sink
                // instead of (or before) the host commit below; on an
                // unreachable PC it reuses the existing DeferredToPending →
                // committed=false → pending-part fallback (see the
                // `insertionService().insert(...)` result handling further down),
                // which already satisfies the M4 "fallback auf Pending-Part"
                // contract. The complementary per-row "send to Windows" seam is
                // the GONE `item_kbd_history_send_btn` + `onSendToWindows` hook
                // (ADR-0014). Kept as a documented anchor so the later package
                // does not have to rediscover the dispatch point.
                //
                // Post-cutover hotfix #AE-DEEP — commit text BEFORE the
                // PipelineDone dispatch. `commitTextToInputConnection`
                // calls `isAutoEnterActive()`, which (post-#AE) reads from
                // `state.pipeline.Running.autoEnterActive`. If we dispatch
                // PipelineDone first, state.pipeline transitions to Idle
                // and `isAutoEnterActive()` falls back to the global
                // `Pref.AutoEnter` — the per-run override the user just
                // toggled is silently lost. Doing the commit while
                // state.pipeline is still Running keeps the per-run flag
                // load-bearing. The PipelineDone-after-commit ordering
                // preserves the D4-hotfix invariant (CRITICAL: dispatch
                // must happen so the keyboard doesn't stick in SEND_MODE
                // and the next StartRecording isn't rejected by a stale
                // pipeline FSM — see git history) — the dispatch still
                // fires, just 1-2 ms later.
                // R4 (ADR-0009 / spec §3.5) — flush older pending parts FIRST
                // when a host field is available, so document order equals
                // dictation order. `flushedCount > 0` then space-prefixes the
                // fresh result (D4 single-space joiner). The flush is a no-op
                // when there are no COMPLETED pending parts.
                int flushedCount = 0;
                if (canCommitToHost()) {
                    java.util.List<net.devemperor.dictate.state.insertion.PendingPart> olderParts =
                            net.devemperor.dictate.state.insertion.PendingPartsFlusherKt
                                    .pendingPartsToFlush(pipelineBinder.getState().getValue().getPendingSessions());
                    if (!olderParts.isEmpty()) {
                        flushedCount = flushPendingParts(olderParts);
                    }
                }
                // Insertion unification — the pipeline transcript commit now
                // routes through the single InsertionService (PIPELINE policy
                // = animate + auto-enter + host-guard + audit). DeferredToPending
                // (widget host-block) maps to committed=false, preserving the
                // PipelineDone→RefreshPendingSessions branch below.
                //
                // D7 audit hardening: the request always carries an explicit
                // sessionIdOverride = sid so the audit never falls back to
                // SessionTracker.getCurrentSessionId() (ambiguous with queued
                // runs; 9637fc3 family).
                String freshText = flushedCount > 0 ? " " + text : text;
                boolean committed = insertionService().insert(
                        new InsertionRequest(freshText, source, InsertionPolicy.PIPELINE, null, sid)
                ) instanceof InsertionResult.Committed;
                if (sid != null) {
                    // 2026-05-22 — pass the commit-result to PipelineDone so
                    // the reducer can branch: success → MarkSessionInserted,
                    // blocked (B3.5 widget-host-block) → RefreshPendingSessions
                    // so the InfoBar surfaces a "Tap to paste" item live.
                    dispatchPipelineActionToOrchestrator(
                            new net.devemperor.dictate.state.Action.PipelineAction.PipelineDone(sid, text, committed),
                            "PipelineDone");
                }
            }
        });
    }

    @Override
    public void onReviewTurnCompleted(@androidx.annotation.NonNull String sessionId,
                                      @androidx.annotation.NonNull String output,
                                      @androidx.annotation.Nullable String message,
                                      boolean needsClarification) {
        // ADR-0013: a dictated follow-up turn finished (non-terminal). Re-run
        // the verdict: still ambiguous → refresh the panel; otherwise commit the
        // refined output and close.
        mainHandler.post(() -> {
            net.devemperor.dictate.ai.conversation.Verdict v =
                    net.devemperor.dictate.ai.conversation.ReviewDecision.INSTANCE.decide(
                            currentAmbiguityMode(), needsClarification, message);
            if (v == net.devemperor.dictate.ai.conversation.Verdict.REVIEW && canShowReviewPanel()) {
                dispatchPipelineActionToOrchestrator(
                        new net.devemperor.dictate.state.Action.ReviewPanelAction.Update(output, message),
                        "ReviewPanel.Update");
            } else {
                insertionService().insert(new InsertionRequest(
                        output, InsertionSource.TRANSCRIPTION, InsertionPolicy.PIPELINE, null, sessionId));
                dispatchPipelineActionToOrchestrator(
                        net.devemperor.dictate.state.Action.ReviewPanelAction.Insert.INSTANCE,
                        "ReviewPanel.Insert");
            }
        });
    }

    // ── ADR-0013 review-panel helpers + button handlers ──────────────────

    /**
     * ADR-0013 review-continuation carve-out — the ONLY IME
     * {@code JobExecutor.start} besides the RESUME carve-out
     * ({@code startResumeJob}). A dictated review refinement runs as a
     * follow-up turn on the run-queue (ADR-0009), off the main pipeline FSM
     * (like a regenerate), and surfaces via the non-terminal
     * {@code onReviewTurnCompleted}. Kept in a named method so the
     * architecture-invariant test can whitelist exactly this call-site.
     */
    private void startReviewContinuationJob(@androidx.annotation.NonNull String sessionId,
                                            @androidx.annotation.NonNull String followUpText) {
        net.devemperor.dictate.core.JobExecutor.INSTANCE.start(
                this,
                new net.devemperor.dictate.core.JobRequest.ConversationContinuation(sessionId, followUpText, 1));
    }

    private net.devemperor.dictate.preferences.AmbiguityMode currentAmbiguityMode() {
        return net.devemperor.dictate.preferences.AmbiguityMode.fromPersistKey(
                DictatePrefsKt.get(sp, Pref.AmbiguityMode.INSTANCE));
    }

    /** The review panel opens only when the IME view is visible (ADR-0013 §7). */
    private boolean canShowReviewPanel() {
        return pipelineBinder != null && pipelineBinder.getState().getValue().getImeViewVisible();
    }

    @androidx.annotation.Nullable
    private net.devemperor.dictate.state.ReviewPanelState currentReviewPanel() {
        if (pipelineBinder == null) return null;
        return pipelineBinder.getState().getValue().getReviewPanel();
    }

    private boolean isReviewActive() {
        net.devemperor.dictate.state.ReviewPanelState p = currentReviewPanel();
        return reviewRefinementTargetSessionId != null || (p != null && p.getOpen());
    }

    /** "Re-dictate" — start a transcription-only recording whose transcript
     *  continues the reviewed conversation (S1). Mirrors the live-prompt chip. */
    private void onReviewRedictateClicked() {
        net.devemperor.dictate.state.ReviewPanelState panel = currentReviewPanel();
        if (panel == null || !panel.getOpen() || panel.getRefining() || panel.getSessionId() == null) return;
        reviewRefinementTargetSessionId = panel.getSessionId();
        if (isEffectiveRecordingIdle()) {
            startRecording();
        } else if (isEffectiveRecordingActiveOrPaused()) {
            stopRecording();
        }
    }

    /** "Insert" — commit the reviewed output into the host, then clear + ack. */
    private void onReviewInsertClicked() {
        net.devemperor.dictate.state.ReviewPanelState panel = currentReviewPanel();
        if (panel == null || !panel.getOpen() || panel.getRefining()) return;
        insertionService().insert(new InsertionRequest(
                panel.getOutput(), InsertionSource.TRANSCRIPTION, InsertionPolicy.PIPELINE, null, panel.getSessionId()));
        dispatchPipelineActionToOrchestrator(
                net.devemperor.dictate.state.Action.ReviewPanelAction.Insert.INSTANCE, "ReviewPanel.Insert");
    }

    /** "Discard" — while refining it cancels the continuation job; otherwise it
     *  clears + acknowledges the session (no host commit). */
    private void onReviewDiscardClicked() {
        net.devemperor.dictate.state.ReviewPanelState panel = currentReviewPanel();
        if (panel == null || !panel.getOpen()) return;
        if (panel.getRefining()) {
            if (panel.getSessionId() != null) {
                net.devemperor.dictate.core.JobExecutor.INSTANCE.cancel(panel.getSessionId());
            }
            dispatchPipelineActionToOrchestrator(
                    net.devemperor.dictate.state.Action.ReviewPanelAction.CancelRefinement.INSTANCE,
                    "ReviewPanel.CancelRefinement");
        } else {
            dispatchPipelineActionToOrchestrator(
                    net.devemperor.dictate.state.Action.ReviewPanelAction.Discard.INSTANCE,
                    "ReviewPanel.Discard");
        }
    }

    @Override
    public void onPipelineError(@androidx.annotation.NonNull String errorInfoKey, boolean vibrate, @androidx.annotation.Nullable String providerName) {
        mainHandler.post(() -> {
            // Orchestrator-sync (D4 hotfix) — PipelineFailed moves
            // state.pipeline → Idle + Effect.MarkSessionFailed.
            String sid = currentPipelineSessionId();
            if (sid != null) {
                String reason = providerName != null
                        ? errorInfoKey + " (" + providerName + ")"
                        : errorInfoKey;
                dispatchPipelineActionToOrchestrator(
                        new net.devemperor.dictate.state.Action.PipelineAction.PipelineFailed(sid, reason),
                        "PipelineFailed");
            }
            // 2026-07-02 (ADR-0006 completion) — error info surfaces as
            // STATE: the typed kind lands on state.infoHints.pipelineError
            // (InfoHintModule) and the InfoBarSelector derives the error
            // bar from it. Replaces the legacy showInfo(errorInfoKey)
            // view mutation — force-expand + prompts-mutex now apply to
            // error bars by construction, and the bar survives a view
            // recreation because it re-derives from state.
            //
            // fromInfoKey returns null for "cancelled" (user-initiated,
            // silent by design — F-076) and for unknown keys
            // (fail-closed instead of the legacy stale-bar trap).
            net.devemperor.dictate.state.PipelineErrorKind kind =
                    net.devemperor.dictate.state.PipelineErrorKind.Companion.fromInfoKey(errorInfoKey);
            if (kind != null) {
                dispatchPipelineActionToOrchestrator(
                        new net.devemperor.dictate.state.Action.InfoHintAction.PipelineErrorOccurred(
                                kind, providerName),
                        "PipelineErrorOccurred");
            }
        });
        if (vibrate && vibrationEnabled) {
            vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    @Override
    public void onShowResend() {
        mainHandler.post(() -> {
            if (resendButton == null) return;  // View recreation not yet complete
            // CR4 (Theme C-R / §9.6 — Spec 2 §13.1 row 28 / §9.6 :1839):
            // the imperative resendButton.setVisibility(VISIBLE) is
            // REPLACED by `dispatch(ResendAction.MarkLastAudio(exists =
            // true))` → ResendModule sets state.resend.lastAudioExists =
            // true → state emit → the RESEND-slot `isResendVisible`
            // predicate evaluates true → the attached ImeViewBackend
            // renders RESEND visible. This is the exact Spec 2 §9.6
            // replacement form (the Block-1a "Quick-Win exception" the
            // old KDoc described — the predicate folding + completion
            // re-order — is precisely this CR4 cutover). The imperative
            // setVisibility is the UNBOUND fallback only (no
            // ResendModule / reactive render without a binder).
            if (pipelineBinder != null) {
                try {
                    pipelineBinder.dispatch(
                            new net.devemperor.dictate.state.Action.ResendAction
                                    .MarkLastAudio(true));
                } catch (Throwable t) {
                    Log.w("DictateIME", "MarkLastAudio dispatch failed", t);
                }
            } else {
                resendButton.setVisibility(View.VISIBLE);
            }
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
        // ADR-0013: while a review is active (panel open or a refinement
        // recording/continuation in flight), keep the session tracking the
        // panel depends on — do not clear it here.
        if (isReviewActive()) return;

        // Clear the transient current-session tracking (DB is source of truth
        // for "last keyboard session" — see SessionTracker.getLastKeyboardSession).
        sessionTracker.clearCurrent();
        // Phase 5.B (Vol 2): no imperative renderer drive remains -- the
        // orchestrator's PipelineDone/PipelineFailed/CancelPipeline reducer
        // arms move state.pipeline to Idle, the reactive PipelineStepRowRenderer
        // clears its rows on the next render-tick, and the catalog +
        // AutoEnterRenderer repaint the record-button. QWERTZ reset fires
        // via the pipelineUiStateObserver's Idle branch.
    }

    private boolean isAutoEnterActive() {
        // Post-cutover hotfix #AE — read from the orchestrator's
        // PipelineUiState.{Preparing,Running}.autoEnterActive. That is
        // the source of truth since PipelineAction.ToggleRunningAutoEnter
        // flips it on the second SEND-tap. The legacy
        // pipelineStepRowRenderer.getAutoEnterConfig() path was never
        // wired to the catalog click-dispatch so it stayed stale.
        //
        // #AE-DEEP2: also accept Preparing — a tap landing in the
        // upload window (Preparing) carries the override into Running
        // via the StartPipeline reducer (autoEnterActive merge). The
        // Preparing read here covers the equally-real case where the
        // pipeline completes before Running was ever materialised
        // (very short jobs); the Preparing flag is then the only
        // surviving record of the user's toggle.
        net.devemperor.dictate.state.PipelineUiState ps = getPipelinePhase();
        if (ps instanceof net.devemperor.dictate.state.PipelineUiState.Running) {
            return ((net.devemperor.dictate.state.PipelineUiState.Running) ps)
                    .getAutoEnterActive();
        }
        if (ps instanceof net.devemperor.dictate.state.PipelineUiState.Preparing) {
            return ((net.devemperor.dictate.state.PipelineUiState.Preparing) ps)
                    .getAutoEnterActive();
        }
        // Pipeline not Preparing/Running (Idle) — fall back to the
        // global pref so end-of-pipeline triggers that race ahead of
        // the state.pipeline=Idle transition still respect the user's
        // setting. The legacy AutoEnterConfig fallback is intentionally
        // gone (out-of-sync with the catalog).
        return DictatePrefsKt.get(sp, Pref.AutoEnter.INSTANCE);
    }

    private void toggleAutoEnterOverride() {
        // Phase 5.B (Vol 2): the orchestrator's
        // `state.pipeline.{Preparing,Running}.autoEnterActive` is the single
        // SoT; dispatch ToggleRunningAutoEnter and the reducer flips it
        // (Preparing OR Running). The pre-Phase-5.B dual-write bridge to
        // the legacy core.PipelineUiState carrier is gone.
        //
        // #AE-DEEP2: accept BOTH Preparing and Running. The orchestrator
        // only reaches Running once the runner emits StartPipeline
        // (typically 500ms-2s after the SEND-tap). A double-tap in that
        // window hits Preparing -- pre-fix the strict `is Running` guard
        // rejected it and the autoEnterActive stayed defaulted in the
        // Preparing -> Running reducer arm.
        if (pipelineBinder == null) return;
        net.devemperor.dictate.state.PipelineUiState ps = getPipelinePhase();
        if (ps instanceof net.devemperor.dictate.state.PipelineUiState.Running
                || ps instanceof net.devemperor.dictate.state.PipelineUiState.Preparing) {
            pipelineBinder.dispatch(
                    net.devemperor.dictate.state.Action.PipelineAction.ToggleRunningAutoEnter.INSTANCE);
        }
    }

    /**
     * Insert the given ordered pending parts (recording order) into the
     * host field via {@link net.devemperor.dictate.state.insertion.PendingPartsFlusher}
     * — insert-first, consume-after; stops at the first failed commit. Each
     * successful commit dispatches its per-part {@code AcceptAndInsert}
     * through the single dispatch sink (state + DB marking stays
     * per-session). @see ADR-0009, spec §3.5.
     *
     * @return the number of parts successfully inserted.
     */
    private int flushPendingParts(java.util.List<net.devemperor.dictate.state.insertion.PendingPart> parts) {
        return new net.devemperor.dictate.state.insertion.PendingPartsFlusher(
                insertionService(),
                action -> {
                    pipelineBinder.dispatch(action);
                    return kotlin.Unit.INSTANCE;
                }
        ).flush(parts);
    }

    /**
     * Lazily builds the single {@link InsertionService} — the sole owner of
     * every host {@link InputConnection} write. Each collaborator is a thin
     * adapter over the service methods that already drive the (robust)
     * pipeline + resend paths, so behaviour is preserved while every path is
     * funnelled through one place. The slow-output committer uses the
     * recovery-aware {@link SlowOutputAnimator} (W1: aborts on a stale IC and
     * reports the dropped tail instead of silently losing it).
     */
    private InsertionService insertionService() {
        if (insertionService == null) {
            insertionService = new InsertionService(
                    // IcProvider — live IC + EditorInfo (null when detached).
                    () -> {
                        InputConnection ic = getCurrentInputConnection();
                        return ic == null ? null : new HostTarget(ic, getCurrentInputEditorInfo());
                    },
                    // HostCommitGuard — widget host-block (B3.5 / ADR-0008).
                    this::canCommitToHost,
                    // TextCommitter — instant vs. recovery-aware slow-output.
                    (ic, text) -> {
                        if (DictatePrefsKt.get(sp, Pref.InstantOutput.INSTANCE) || mainHandler == null) {
                            return ic.commitText(text, 1);
                        }
                        final int speed = DictatePrefsKt.get(sp, Pref.OutputSpeed.INSTANCE);
                        SlowOutputAnimator animator = new SlowOutputAnimator(
                                (delayMs, action) -> mainHandler.postDelayed(action::invoke, delayMs),
                                (index) -> slowOutputDelayForIndex(index, speed),
                                (remaining) -> Log.w("DictateIME",
                                        "slow-output tail dropped on stale IC (" + remaining.length() + " chars)"));
                        return animator.run(ic, text);
                    },
                    // ControlExecutor — backspace / enter / cursor.
                    this::executeControlOp,
                    // AutoEnterScheduler — per-run override + Enter tick.
                    new AutoEnterScheduler() {
                        @Override public boolean isActive() { return isAutoEnterActive(); }
                        @Override public void schedule(@androidx.annotation.NonNull String text) { scheduleAutoEnter(text); }
                    },
                    // InsertionAuditLog — pre-commit selection capture + DB row.
                    new InsertionAuditLog() {
                        @Override public String captureReplaced(@androidx.annotation.NonNull InputConnection ic) {
                            return safeReadSelectedText(ic);
                        }
                        @Override public void record(@androidx.annotation.NonNull String text, String replaced,
                                EditorInfo editor, @androidx.annotation.NonNull InsertionSource source,
                                String sessionIdOverride) {
                            final String fSessionId = sessionIdOverride != null
                                    ? sessionIdOverride : sessionTracker.getCurrentSessionId();
                            final String fStepId = sessionTracker.getCurrentStepId();
                            final String fTranscriptionId = sessionTracker.getCurrentTranscriptionId();
                            final String pkg = editor != null ? editor.packageName : null;
                            dbExecutor.execute(() -> {
                                sessionManager.logTextInsertion(fSessionId, text, replaced, pkg,
                                        null, fStepId, fTranscriptionId, InsertionMethod.COMMIT);
                                if (fSessionId != null) {
                                    sessionManager.updateFinalOutputText(fSessionId, text);
                                }
                            });
                        }
                    },
                    // RecoveryHandler — focus-lost toast + resume job (resend).
                    new RecoveryHandler() {
                        @Override public void notifyFocusLost() {
                            Toast.makeText(DictateInputMethodService.this,
                                    R.string.dictate_resend_focus_lost, Toast.LENGTH_SHORT).show();
                        }
                        @Override public void resume(@androidx.annotation.NonNull String sessionId) {
                            startResumeJob(sessionId);
                        }
                    },
                    // ClipboardGateway — soft context-menu action + manual fallback.
                    new ClipboardGateway() {
                        @Override public boolean performHostAction(@androidx.annotation.NonNull InputConnection ic,
                                @androidx.annotation.NonNull EditAction action) {
                            return ic.performContextMenuAction(action.getAndroidId());
                        }
                        @Override public void fallback(@androidx.annotation.NonNull InputConnection ic,
                                @androidx.annotation.NonNull EditAction action) {
                            performClipboardFallback(ic, action.getAndroidId());
                        }
                    },
                    // HostTextReader — selection + surrounding-text peek that lets
                    // InsertionService.control() own the grapheme/selection-aware
                    // backspace + cursor-move decisions (F-018 / F-021). Fail-soft:
                    // a throwing/stale IC yields the safe "nothing" value.
                    new HostTextReader() {
                        @Override public HostSelection selection(@androidx.annotation.NonNull InputConnection ic) {
                            try {
                                ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
                                if (et == null || et.selectionStart < 0 || et.selectionEnd < 0) {
                                    return HostSelection.NONE;
                                }
                                int base = et.startOffset < 0 ? 0 : et.startOffset;
                                return new HostSelection(base + et.selectionStart, base + et.selectionEnd);
                            } catch (Exception e) {
                                return HostSelection.NONE;
                            }
                        }
                        @Override public String textBeforeCursor(@androidx.annotation.NonNull InputConnection ic, int maxChars) {
                            try {
                                CharSequence before = ic.getTextBeforeCursor(maxChars, 0);
                                return before == null ? "" : before.toString();
                            } catch (Exception e) {
                                return "";
                            }
                        }
                    });
        }
        return insertionService;
    }

    /** Execute a non-text {@link ControlOp} on {@code ic} (always succeeds). */
    private boolean executeControlOp(InputConnection ic, ControlOp op) {
        if (op instanceof ControlOp.Backspace) {
            ic.deleteSurroundingText(1, 0);
        } else if (op instanceof ControlOp.DeleteSurrounding) {
            ControlOp.DeleteSurrounding d = (ControlOp.DeleteSurrounding) op;
            ic.deleteSurroundingText(d.getBefore(), d.getAfter());
        } else if (op instanceof ControlOp.Enter) {
            ControlOp.Enter e = (ControlOp.Enter) op;
            if (e.getRole() == EnterButtonRole.NEWLINE) {
                ic.commitText("\n", 1);
            } else {
                ic.performEditorAction(e.getActionId());
            }
        } else if (op instanceof ControlOp.PhysicalEnter) {
            long now = android.os.SystemClock.uptimeMillis();
            ic.sendKeyEvent(new android.view.KeyEvent(now, now,
                    android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER, 0));
            ic.sendKeyEvent(new android.view.KeyEvent(now, now,
                    android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER, 0));
        } else if (op instanceof ControlOp.CursorNudge) {
            // Legacy empty-commit caret nudge (offset 2 = right, -1 = left).
            ic.commitText("", ((ControlOp.CursorNudge) op).getOffset());
        } else if (op instanceof ControlOp.SetSelection) {
            ControlOp.SetSelection s = (ControlOp.SetSelection) op;
            ic.setSelection(s.getStart(), s.getEnd());
        } else if (op instanceof ControlOp.DeleteSelection) {
            ic.commitText("", 1);
        }
        // ControlOp.Backspace / DeleteGrapheme / CursorMove are resolved into
        // concrete primitives by InsertionService.control() before they reach
        // here; DeleteGrapheme/CursorMove never arrive raw.
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

    /**
     * Refresh the non-selection-prompt-chip disable predicate from the
     * orchestrator's [DictateUiState].
     *
     * <p>B-E fix (dictate-pipeline-render-and-state-unification §5.7 +
     * AC-E + AC-P-1): the pre-fix body read
     * {@code recordingStateController.getState()}, which is the legacy
     * controller. Post-cutover the orchestrator's
     * {@link RecordingHardwareAdapter} owns MediaRecorder directly and
     * the legacy controller is never started — its {@code state} stays
     * permanently {@code Idle}, so the predicate was permanently
     * {@code false} and every chip was tappable during Recording /
     * Pipeline (the user-reported B-E regression).</p>
     *
     * <p>New predicate reads the orchestrator-authoritative
     * {@code state.recording} AND {@code state.pipeline} axes:
     * <ul>
     *   <li>{@code state.recording is Active|Paused|Preparing} —
     *       covers the recording-in-flight window.</li>
     *   <li>{@code state.pipeline is Preparing|Running} — covers the
     *       upload + step-execution window (formerly out of scope for
     *       the legacy predicate because the legacy controller didn't
     *       know about the pipeline FSM at all).</li>
     * </ul>
     * Either axis being busy disables the non-selection chips.</p>
     *
     * <p>The actual {@code RecyclerView} {@code notifyDataSetChanged}
     * fan-out still goes through the existing
     * {@code mainHandler.post(...)} bridge so this method can be safely
     * called from any thread (including the {@code PipelineUiStateObserver}
     * collect-coroutine on {@code Dispatchers.Main}).</p>
     */
    private void updatePromptButtonsEnabledState() {
        boolean recordingBusy = false;
        boolean pipelineBusy = false;
        if (pipelineBinder != null) {
            net.devemperor.dictate.state.DictateUiState ui =
                    pipelineBinder.getState().getValue();
            net.devemperor.dictate.state.RecordingState rec = ui.getRecording();
            recordingBusy =
                    net.devemperor.dictate.state.DictateUiStateKt.isActiveOrPaused(rec)
                            || rec instanceof net.devemperor.dictate.state.RecordingState.Preparing;
            net.devemperor.dictate.state.PipelineUiState pipe = ui.getPipeline();
            pipelineBusy =
                    pipe instanceof net.devemperor.dictate.state.PipelineUiState.Preparing
                            || pipe instanceof net.devemperor.dictate.state.PipelineUiState.Running;
        }
        disableNonSelectionPrompts = recordingBusy || pipelineBusy;
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

    /**
     * Activity-launch side-channel for info-bar confirm actions
     * (2026-07-02, ADR-0006 completion). The state mutation (clearing
     * the hint) happens via the dispatched {@code InfoHintAction} /
     * {@code OverlayAction}; this method performs only the launch that
     * a pure reducer cannot (the IME service is the Context-bearing
     * seam — ADR-0005 Decision-History 2026-05-15).
     *
     * <p>Launch mapping (parity with the deleted legacy
     * legacy info-bar controller click handlers):
     * <ul>
     *   <li>{@code RequestOverlayPermission} → system overlay-permission
     *       settings deep-link</li>
     *   <li>{@code ConfirmPipelineError} — INVALID_API_KEY /
     *       MODEL_NOT_FOUND / BAD_REQUEST → app settings;
     *       QUOTA_EXCEEDED → provider billing page (the selector only
     *       offers the confirm button when the provider has one)</li>
     *   <li>{@code ConfirmEngagementHint} — UPDATE → app settings
     *       (changelog); RATE → Play-Store page; DONATE → PayPal</li>
     * </ul>
     */
    private void launchInfoBarSideChannel(net.devemperor.dictate.state.Action action) {
        if (action == net.devemperor.dictate.state.Action.OverlayAction
                .RequestOverlayPermission.INSTANCE) {
            try {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                Log.w("DictateIME", "Failed to launch overlay-permission settings", e);
            }
        } else if (action instanceof net.devemperor.dictate.state.Action.InfoHintAction.ConfirmPipelineError) {
            net.devemperor.dictate.state.Action.InfoHintAction.ConfirmPipelineError confirm =
                    (net.devemperor.dictate.state.Action.InfoHintAction.ConfirmPipelineError) action;
            switch (confirm.getKind()) {
                case INVALID_API_KEY:
                case MODEL_NOT_FOUND:
                case BAD_REQUEST:
                    openSettingsActivity();
                    break;
                case QUOTA_EXCEEDED:
                    String billingUrl = net.devemperor.dictate.ai.AIProvider
                            .fromPersistKey(confirm.getProviderKey()).getBillingUrl();
                    if (billingUrl != null) openUrlInBrowser(billingUrl);
                    break;
                case INTERNET_ERROR:
                    // Dismiss-only kind — the selector never offers a
                    // confirm button; nothing to launch.
                    break;
            }
        } else if (action instanceof net.devemperor.dictate.state.Action.InfoHintAction.ConfirmEngagementHint) {
            switch (((net.devemperor.dictate.state.Action.InfoHintAction.ConfirmEngagementHint) action).getHint()) {
                case UPDATE:
                    openSettingsActivity();
                    break;
                case RATE:
                    openUrlInBrowser(PLAY_STORE_URL);
                    break;
                case DONATE:
                    openUrlInBrowser(DONATE_URL);
                    break;
            }
        }
    }

    /** Best-effort ACTION_VIEW launch from the IME context. */
    private void openUrlInBrowser(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.w("DictateIME", "Failed to open URL " + url, e);
        }
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

    /**
     * User backspace. The selection- and grapheme-aware decision (delete the
     * selection vs. remove exactly one grapheme cluster) now lives once in
     * {@link InsertionService#control} behind {@link ControlOp.DeleteGrapheme}
     * (F-018) — every backspace path (main-keyboard tap, QWERTZ tap, long-press
     * repeat) dispatches the same op, so short-tap and hold can no longer
     * diverge.
     */
    private void deleteOneCharacter() {
        insertionService().control(ControlOp.DeleteGrapheme.INSTANCE);
    }

    // ===== MainButtonsController.Callback =====

    @Override
    public void onVibrate() {
        vibrate();
    }

    // CR-DEL: was MainButtonsController.Callback (deleted); now invoked
    // via the ImeViewBackend imeSideAffordance hook (RECORD click).
    public void onRecordClicked() {
        // 2026-07-02 (ADR-0006 completion) — the legacy
        // infoBarController.dismiss() is replaced by InfoHintModule's
        // cross-module observer (recording Idle → non-Idle clears
        // state.infoHints when the tap actually starts a recording).

        // Phase 5.B (Vol 2): read the active pipeline phase off the
        // orchestrator state (the renderer is reactive and no longer
        // exposes its own state).
        net.devemperor.dictate.state.PipelineUiState phase = getPipelinePhase();
        if (phase instanceof net.devemperor.dictate.state.PipelineUiState.ReprocessStaging) {
            // ReprocessStaging: the big record button becomes a Send trigger for the
            // currently staged queue (Phase 9.3).
            handleReprocessSend();
            return;
        }

        if (phase instanceof net.devemperor.dictate.state.PipelineUiState.Running
                || phase instanceof net.devemperor.dictate.state.PipelineUiState.Preparing) {
            // Pipeline running or preparing -> toggle auto-enter (no-op
            // during Preparing). The Preparing-window check closes the
            // race on the QWERTZ record button (which otherwise fell
            // through to startRecording() during audio upload).
            toggleAutoEnterOverride();
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            openSettingsActivity();
        } else if (isEffectiveRecordingIdle()) {
            startRecording();
        } else if (isEffectiveRecordingActiveOrPaused()) {
            stopRecording();
        }
    }

    // CR-DEL: was MainButtonsController.Callback; invoked via
    // imeSideAffordance(RECORD, isLongPress=true).
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

    // CR-DEL: was MainButtonsController.Callback; invoked via
    // imeSideAffordance(RESEND, isLongPress=false).
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
        // can't kick off a parallel DB lookup + insertion.
        // CR-DEL (Theme C-R / G8): the RESEND-slot
        // `enabledResolver = { !state.resend.resendCooldown }` +
        // `alphaResolver` own the disabled state state-reactively — the
        // catalog `ResendLastAudio` dispatch (fired alongside this
        // affordance call on the same RESEND click) arms
        // state.resend.resendCooldown, and the CR4-IMPL-2
        // ResendCooldownExpired postDelayed-dispatch (below) clears it.
        // The legacy mainButtonsController.setResendEnabled unbound
        // fallback is GONE (MainButtonsController deleted at the
        // point-of-no-return).

        // F-029 (2026-07-03) — the resend-cooldown *clear* is no longer
        // scheduled here. ResendModule now owns the cooldown timer: both
        // arm actions (ResendLastAudio / ResendLastAudioLong) emit
        // Effect.ScheduleCooldownExpiry, whose handler dispatches
        // ResendCooldownExpired after 500 ms on the service scope. Doing
        // it UI-side only covered the short-press path (this method) — a
        // RESEND long-press armed the cooldown via the catalog
        // ResendLastAudioLong dispatch but nothing here ran, so the
        // enabledResolver `{ !resendCooldown }` latched the button
        // disabled until service restart. Moving the timer into the
        // module makes the arm→expiry round-trip hold for every arming
        // path. The catalog `ResendLastAudio` dispatch fired alongside
        // this affordance call now schedules the clear itself.

        dbExecutor.execute(() -> {
            try {
                SessionEntity lastSession = sessionTracker.getLastKeyboardSession();
                if (lastSession == null) return;

                // Resolve the resend text through the authoritative
                // fallback chain (last step output → current transcription
                // → denormalized column) rather than the raw
                // `final_output_text` cache. The transcription-only pipeline
                // never writes that denormalized column itself — it is
                // populated only as a best-effort side-effect of the IME
                // insertion-audit callback, which is skipped whenever the
                // SessionTracker's currentSessionId has already been cleared
                // (end-of-run) by the time the audit's dbExecutor task runs.
                // Reading the column directly therefore left short-press
                // resend with an empty output for every plain dictation, so
                // the dispatcher returned NoOp/Resume and nothing was
                // inserted. `getFinalOutput` reads the transcription row,
                // which IS reliably persisted. See ResendStatusDispatcher +
                // SessionManager.getFinalOutput.
                String resolvedOutput = sessionManager.getFinalOutput(lastSession.getId());
                if (resolvedOutput == null || resolvedOutput.isEmpty()) {
                    resolvedOutput = lastSession.getFinalOutputText();
                }

                ResendAction action = ResendStatusDispatcher.INSTANCE.decide(
                        lastSession.getStatusEnum(),
                        resolvedOutput,
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
                // CR-DEL (Theme C-R / G8): the CR4-IMPL-2
                // ResendCooldownExpired postDelayed-dispatch (scheduled
                // above in onResendClicked) clears
                // state.resend.resendCooldown → the RESEND-slot
                // enabledResolver re-enables the button reactively. The
                // legacy mainButtonsController.setResendEnabled(true)
                // unbound fallback is GONE (MainButtonsController deleted
                // at the point-of-no-return).
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
        // Insertion unification — the 3-stage resend strategy is now expressed
        // as the RESEND policy on the single InsertionService:
        //   live (same editor as the click-time anchor) → captured IC →
        //   focus-lost toast + resume job.
        // enableAutoEnter is folded into the policy (RESEND ⇒ autoEnter=false):
        // a resend is a recovery insert, never a new transcription, and Stage 2
        // commits on the captured IC while auto-enter would target the live IC.
        insertionService().insert(new InsertionRequest(
                output,
                InsertionSource.TRANSCRIPTION,
                InsertionPolicy.RESEND,
                capturedIc == null ? null : new HostTarget(capturedIc, capturedEditor),
                sessionId));
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

    // CR-DEL: was MainButtonsController.Callback; invoked via
    // imeSideAffordance(RESEND, isLongPress=true).
    public void onResendLongClicked() {
        // Phase 9.2 — enter ReprocessStaging with the last keyboard session.
        // Phase 5.B (Vol 2): isBusy() check reads the orchestrator state
        // (not the renderer); busy means anything other than Idle.
        if (!(getPipelinePhase() instanceof net.devemperor.dictate.state.PipelineUiState.Idle)) return;

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
                if (pipelineBinder == null) return;
                // F-6 (B5-VAL): seed the single LanguageState.override
                // carrier with the session language BEFORE dispatching
                // StartReprocessStaging — the state.pipeline emit triggers
                // the pipelineUiStateObserver -> refreshLanguageChip() ->
                // resolveEffectiveLanguage(), which must already see the
                // seeded override (else the chip / config-snapshot show
                // the wrong, permanent language for this staging session).
                // Same thread (mainHandler.post) so ordering is deterministic.
                dispatchStagingOverride(lastSession.getLanguage());
                // Phase 5.B (Vol 2): mirror the staging payload into the
                // IME fields BEFORE the StartReprocessStaging dispatch so
                // the syncQueueOrder() fan-out (fires off the state emit)
                // reads the populated queue.
                reprocessTargetSessionId = lastSession.getId();
                reprocessAudioDurationSeconds = lastSession.getAudioDurationSeconds();
                reprocessEditableQueue = new ArrayList<>(historicalQueue);
                reprocessSelectedLanguage = lastSession.getLanguage();
                reprocessSelectedModel = null;
                pipelineBinder.dispatch(
                        new net.devemperor.dictate.state.Action.PipelineAction.StartReprocessStaging(
                                lastSession.getId()));
                updatePromptButtonsEnabledState();
            });
        });
    }

    // CR-DEL: was MainButtonsController.Callback (deleted). BACKSPACE
    // click is now the catalog Action.KeyboardInputAction.Backspace on
    // the bound path; this body is kept for any non-catalog caller.
    public void onBackspaceClicked() {
        deleteOneCharacter();
    }

    // CR-DEL: was MainButtonsController.Callback (deleted). The
    // accel-delete cascade is owned by SpecialTouchHandlerInstaller's
    // BackspaceSwipeHandler (§11.7); this body kept for parity.
    public void onBackspaceLongClicked() {
        isDeleting = true;
        startDeleteTime = System.currentTimeMillis();
        currentDeleteDelay = BackspaceDeleteSpeedCurve.INITIAL_DELAY_MS;
        deleteRunnable = new Runnable() {
            @Override
            public void run() {
                if (isDeleting) {
                    deleteOneCharacter();
                    // The 50→25→10→5 ms cascade lives in the pure-Kotlin
                    // BackspaceDeleteSpeedCurve helper (B-C). Keeping the
                    // threshold logic out of this Handler-loop is what
                    // makes the cascade JVM-testable without Robolectric.
                    long diff = System.currentTimeMillis() - startDeleteTime;
                    BackspaceDeleteSpeedCurve.StepTransition step =
                            BackspaceDeleteSpeedCurve.INSTANCE.nextDelay(diff, currentDeleteDelay);
                    if (step.getAdvanced()) {
                        vibrate();
                        currentDeleteDelay = step.getNextDelayMs();
                    }
                    deleteHandler.postDelayed(this, currentDeleteDelay);
                }
            }
        };
        deleteHandler.post(deleteRunnable);
    }

    // CR-DEL: was MainButtonsController.Callback (deleted); invoked by
    // SpecialTouchHandlerInstaller's onBackspaceDeleteCancelled lambda.
    public void onBackspaceDeleteCancelled() {
        isDeleting = false;
        if (deleteRunnable != null) deleteHandler.removeCallbacks(deleteRunnable);
    }

    // CR-DEL: was MainButtonsController.Callback (deleted). TRASH is the
    // catalog actionResolver on the bound path; body kept for parity.
    public void onTrashClicked() {
        // Phase 5.B (Vol 2): the trash button cancels staging back to Idle
        // via the CancelReprocessStaging action; the reactive renderer +
        // catalog repaint Idle on the next state emit.
        net.devemperor.dictate.state.PipelineUiState phase = getPipelinePhase();
        if (phase instanceof net.devemperor.dictate.state.PipelineUiState.ReprocessStaging) {
            net.devemperor.dictate.state.PipelineUiState.ReprocessStaging stage =
                    (net.devemperor.dictate.state.PipelineUiState.ReprocessStaging) phase;
            if (pipelineBinder != null) {
                pipelineBinder.dispatch(
                        new net.devemperor.dictate.state.Action.PipelineAction.CancelReprocessStaging(
                                stage.getSessionId()));
            }
            // F-6 (B5-VAL): clear the override on staging exit
            // (cancel / discard) so it does not leak into the next
            // staging session (stale-override-leak fix).
            dispatchStagingOverride(null);
            // Phase 5.B: clear the IME-Java staging mirror too.
            reprocessTargetSessionId = null;
            reprocessEditableQueue = new ArrayList<>();
            reprocessSelectedLanguage = null;
            reprocessSelectedModel = null;
            reprocessAudioDurationSeconds = 0L;
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
    private void handleReprocessSend() {
        // Phase 5.B (Vol 2): snapshot staging payload from the IME-Java
        // mirror fields (the orchestrator's state.PipelineUiState.ReprocessStaging
        // carries only sessionId/transcript; the editable queue + language
        // override live IME-side).
        final String targetSessionId = reprocessTargetSessionId;
        final String selectedLanguage = reprocessSelectedLanguage;
        final String selectedModel = reprocessSelectedModel;
        final List<Integer> editableQueue = new ArrayList<>(reprocessEditableQueue);
        final EditorInfo info = getCurrentInputEditorInfo();
        final String targetAppPackage = info != null && info.packageName != null
                ? info.packageName.toString() : null;

        if (targetSessionId == null) return;  // defensive — should never happen in ReprocessStaging

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
                // calls JobExecutor.start internally -- that is the adapter,
                // not an IME call-site). The reprocess modelOverride /
                // targetAppPackage / AutoFormatting-+1 are threaded via the
                // ImePipelineConfigResolver reprocess snapshot so the
                // adapter's resolver rebuilds the JobRequest faithfully
                // (C3-IMPL-2). Single-dispatch -- no double-run.
                // B2-VAL-W1 F-9 -- the not-bound condition is "service not
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
                // F-001 (2026-07-03) — SINGLE SUBMIT for BOTH surfaces.
                // Pre-fix this path dispatched a queue-less SendStaging
                // (whose reducer arm emitted Effect.SubmitReprocess with
                // queue=emptyList → live-queue fallback) AND then called
                // pipelineRunner.submitReprocess(...) directly with the
                // real queue — but that second submit was dropped as a
                // duplicate (ActiveJobRegistry already had the job). The
                // staged edits were lost both ways.
                //
                // Now the staged queue + language ride the SendStaging
                // action as EXPLICIT content slots (empty list = run zero
                // prompts, NOT unset). The reducer's Effect.SubmitReprocess
                // carries them through to the runner via the C5 reprocess
                // snapshot (model/targetApp above) — one flow, one submit.
                // The IME no longer calls submitReprocess directly; the
                // PipelineModule reducer arm (ReprocessStaging → Preparing)
                // is the single-submit guard.
                java.util.List<net.devemperor.dictate.core.PromptQueueSlot> stagedSlots =
                        net.devemperor.dictate.core.PromptQueueSlot.fromIds(editableQueue);
                pipelineBinder.dispatch(
                        new net.devemperor.dictate.state.Action.PipelineAction.SendStaging(
                                targetSessionId, stagedSlots, selectedLanguage));

                // F-6 (B5-VAL): clear the override now that staging is
                // exiting (-> Preparing). selectedLanguage was already
                // snapshotted onto the SendStaging action (above), so
                // clearing here does NOT affect the in-flight reprocess
                // job's language -- it only resets the per-staging-session
                // transient so the next staging session starts clean.
                dispatchStagingOverride(null);
                // Phase 5.B: also clear the IME-Java staging mirror once
                // the payload has been handed off to the runner.
                reprocessTargetSessionId = null;
                reprocessEditableQueue = new ArrayList<>();
                reprocessSelectedLanguage = null;
                reprocessSelectedModel = null;
                reprocessAudioDurationSeconds = 0L;
            });
        });
    }

    // CR-DEL: was MainButtonsController.Callback (deleted). PAUSE is the
    // catalog actionResolver on the bound path; also invoked by the
    // QwertzRecordingController prompt-pause toggle. Body kept.
    public void onPauseClicked() {
        togglePauseEffectiveRecording();
    }

    @Override
    public void onKeyboardToggleClicked() {
        toggleQwertzKeyboard();
    }

    @Override
    public void onKeyboardLongClicked() {
        switchToPreviousKeyboard();
    }

    /**
     * Edit-bar widget-toggle click (2026-05-22 relocation). Permission-
     * aware: if the overlay permission is missing, surface the
     * onboarding flow instead of attempting the toggle (mirrors the
     * resolveWidgetToggleAction logic the old main-row slot used).
     *
     * Pre-bind fallback unnecessary because the keyboard view is only
     * inflated post-bind (the IME service has the binder by then).
     */
    @Override
    public void onWidgetToggleClicked() {
        if (pipelineBinder == null) return;
        net.devemperor.dictate.state.DictateUiState state =
                pipelineBinder.getState().getValue();
        net.devemperor.dictate.state.Action action;
        if (state.getOverlay().getHasPermission()) {
            action = net.devemperor.dictate.state.Action.ViewModeAction.ToggleViewModeWidget.INSTANCE;
        } else {
            action = net.devemperor.dictate.state.Action.OverlayAction.ShowOverlayOnboarding.INSTANCE;
        }
        Log.i("DictateTrace", "IME.onWidgetToggleClicked() dispatching=" + action.getClass().getSimpleName()
                + " viewMode=" + state.getViewMode()
                + " widget=" + state.getWidget().getClass().getSimpleName()
                + " imeViewVisible=" + state.getImeViewVisible()
                + " hasPermission=" + state.getOverlay().getHasPermission()
                + " userPrefersWidget=" + state.getOverlay().getUserPrefersWidget());
        pipelineBinder.dispatch(action);
        net.devemperor.dictate.state.DictateUiState after =
                pipelineBinder.getState().getValue();
        Log.i("DictateTrace", "IME.onWidgetToggleClicked() AFTER viewMode=" + after.getViewMode()
                + " widget=" + after.getWidget().getClass().getSimpleName());
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
        // 2026-05-22 — the previous in-flight-recording cancel-reflex
        // was the root cause of "Aufnahme geht verloren beim Settings-
        // Wechsel". The reflex predates the FGS-owned recording stack:
        // back then, opening Settings would unbind the IME and the
        // MediaRecorder would die anyway, so canceling explicitly kept
        // the state consistent. Today recording lives in the
        // foreground-service path (RecordingHardwareAdapter +
        // DictatePipelineService FGS), so Settings-Open is harmless —
        // the recording continues in the background and the
        // notification carries Pause/Stop affordance.
        //
        // The previous code:
        //   if (isEffectiveRecordingInFlight()) {
        //       cancelEffectiveRecording();
        //       livePrompt = false;
        //       updatePromptButtonsEnabledState();
        //   }
        // is removed. Consistent with onHistoryClicked() (which never
        // cancelled) and openLanguageSettings() (also no cancel).
        // Trash-button + WidgetClose-X remain the explicit
        // user-driven cancel paths.
        //
        // 2026-07-02 (ADR-0006 completion) — clear the in-RAM info
        // hints when heading into the settings (the user is likely
        // fixing the surfaced problem; a stale error bar on return
        // would mislead). Pref-persisted triggers re-evaluate on the
        // next onStartInputView, mirroring the legacy dismiss() here.
        dispatchPipelineActionToOrchestrator(
                net.devemperor.dictate.state.Action.InfoHintAction.ClearTransientHints.INSTANCE,
                "ClearTransientHints(settings-open)");
        openSettingsActivity();
    }

    @Override
    public void onHistoryClicked() {
        // ADR-0014: short press toggles the in-keyboard history panel. Opening
        // is gated on a visible IME and no active review (the review panel holds
        // an uninserted turn awaiting a decision and outranks history).
        if (pipelineBinder == null) { openHistoryActivity(); return; }
        net.devemperor.dictate.state.DictateUiState s = pipelineBinder.getState().getValue();
        if (s.getHistoryPanel().getOpen()) {
            dispatchPipelineActionToOrchestrator(
                net.devemperor.dictate.state.Action.HistoryPanelAction.Close.INSTANCE,
                "HistoryPanel.Close");
        } else if (canOpenHistoryPanel(s)) {
            dispatchPipelineActionToOrchestrator(
                net.devemperor.dictate.state.Action.HistoryPanelAction.Open.INSTANCE,
                "HistoryPanel.Open");
        }
    }

    /** History panel opens only over a visible IME and never atop the review panel. */
    private boolean canOpenHistoryPanel(net.devemperor.dictate.state.DictateUiState s) {
        return s.getImeViewVisible() && !s.getReviewPanel().getOpen();
    }

    @Override
    public void onHistoryLongClicked() {
        // Long-press always opens the full-screen HistoryActivity (search /
        // audio / detail), independent of the in-keyboard panel.
        openHistoryActivity();
    }

    private void openHistoryActivity() {
        Intent intent = new Intent(this, HistoryActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    /**
     * "Insert" tapped on a history-panel row (ADR-0014). Commits the session's
     * final output into the host (side-channel — the reducer cannot reach the
     * InputConnection), then acknowledges a pending row so recovery does not
     * re-surface it: a row tracked in the pendingSessions axis goes through
     * AcceptAndInsert (removes the part + acknowledges); an older uninserted row
     * goes through HistoryPanelAction.AcknowledgeInsert (acknowledge only). An
     * already-inserted row is a pure re-commit (no state change).
     */
    private void onKeyboardHistoryInsertClicked(
            net.devemperor.dictate.database.entity.SessionEntity session, boolean pending) {
        if (session == null || sessionManager == null) return;
        String sid = session.getId();
        String text = sessionManager.getFinalOutput(sid);
        if (text == null || text.isEmpty()) return;
        insertionService().insert(new net.devemperor.dictate.state.insertion.InsertionRequest(
            text,
            pending
                ? net.devemperor.dictate.database.entity.InsertionSource.PENDING_PART
                : net.devemperor.dictate.database.entity.InsertionSource.TRANSCRIPTION,
            net.devemperor.dictate.state.insertion.InsertionPolicy.PIPELINE,
            null, sid));
        if (!pending) return;
        boolean inAxis = false;
        if (pipelineBinder != null) {
            for (net.devemperor.dictate.state.PendingSession ps
                    : pipelineBinder.getState().getValue().getPendingSessions()) {
                if (ps.getSessionId().equals(sid)) { inAxis = true; break; }
            }
        }
        dispatchPipelineActionToOrchestrator(
            inAxis
                ? new net.devemperor.dictate.state.Action.PendingSessionsAction.AcceptAndInsert(sid)
                : new net.devemperor.dictate.state.Action.HistoryPanelAction.AcknowledgeInsert(sid),
            "HistoryPanel.Insert");
    }

    /**
     * Sets the history-panel list height to ~50% of the display height, clamped
     * to [min, max] (ADR-0014) so the panel is "distinctly taller" than the grid
     * across device sizes. The XML default is the pre-measurement fallback.
     */
    private void applyHistoryPanelHeight(android.view.View list) {
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int minPx = getResources().getDimensionPixelSize(R.dimen.dictate_history_panel_list_min);
            int maxPx = getResources().getDimensionPixelSize(R.dimen.dictate_history_panel_list_max);
            int target = Math.max(minPx, Math.min(maxPx, dm.heightPixels / 2));
            android.view.ViewGroup.LayoutParams lp = list.getLayoutParams();
            lp.height = target;
            list.setLayoutParams(lp);
        } catch (Throwable t) {
            Log.w("DictateIME", "history panel height sizing failed; using XML default", t);
        }
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

        // Phase 5.B (Vol 2): dispatch CancelPipeline so the orchestrator
        // FSM moves state.pipeline -> Idle; the reactive PipelineStepRowRenderer
        // clears its rows on the next render-tick, and the Catalog +
        // AutoEnterRenderer repaint the record-button Idle visual reactively.
        if (pipelineBinder != null) {
            String activeSid = currentPipelineSessionId();
            pipelineBinder.dispatch(
                    new net.devemperor.dictate.state.Action.PipelineAction.CancelPipeline(activeSid));
        }

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
                mainHandler.post(() -> insertionService().insert(new InsertionRequest(
                        finalOutput, InsertionSource.TRANSCRIPTION, InsertionPolicy.PIPELINE, null, null)));
            }
        });
    }

    @Override
    public void onSmallModeToggled() {
        // 2026-05-21 indirection-cleanup (A-1): dispatch directly to the
        // orchestrator. The reducer flips state.layout.smallMode + clamps
        // contentArea atomically AND emits Effect.PersistSmallMode which
        // writes SharedPreferences. PipelinePrefMirror still mirrors
        // *external* SP changes (settings activity) — the Effect-write
        // round-trips through it but the duplicate state-update is
        // absorbed by StateFlow's distinct-emission contract (no feedback
        // loop). The 7-stage SP-roundtrip ("click → SP → mirror → state
        // → render") collapses to 3 ("click → dispatch → reducer+effect →
        // render").
        //
        // Pre-bind fallback: when pipelineBinder is null (Service not yet
        // bound — narrow window during onCreateInputView), write SP
        // directly so the user's choice survives. The mirror picks it up
        // on bind. EditNumbersAnimator runs in either branch.
        if (pipelineBinder != null) {
            pipelineBinder.dispatch(
                    net.devemperor.dictate.state.Action.LayoutAction.ToggleSmallMode.INSTANCE);
        } else {
            // PRE-BIND-FALLBACK: SP-write authorized because dispatcher
            // unavailable (review-fix G2, 2026-05-21). The
            // CutoverArchitectureInvariantTest lock-grep allow-lists
            // SP-writes only at lines carrying this exact tag string.
            boolean current = DictatePrefsKt.get(sp, Pref.SmallMode.INSTANCE);
            DictatePrefsKt.put(sp.edit(), Pref.SmallMode.INSTANCE, !current).apply();
        }
        if (editNumbersAnimator != null) {
            editNumbersAnimator.animateSmallModeToggle(true);
        }
    }

    @Override
    public void onSingleRowModeToggled() {
        // 2026-05-21 indirection-cleanup (A-2): see onSmallModeToggled
        // for the architecture. Effect.PersistSingleRowMode is emitted
        // by the reducer arm; the legacy SP-roundtrip is retired.
        if (pipelineBinder != null) {
            pipelineBinder.dispatch(
                    net.devemperor.dictate.state.Action.LayoutAction.ToggleSingleRowMode.INSTANCE);
        } else {
            // PRE-BIND-FALLBACK: SP-write authorized because dispatcher
            // unavailable (review-fix G2, 2026-05-21).
            boolean current = DictatePrefsKt.get(sp, Pref.SingleRowMode.INSTANCE);
            DictatePrefsKt.put(sp.edit(), Pref.SingleRowMode.INSTANCE, !current).apply();
        }
        if (editNumbersAnimator != null) {
            editNumbersAnimator.animateEditNumbersBounce();
        }
    }

    @Override
    public void onAudioFocusToggled() {
        // 2026-05-21 indirection-cleanup A-3 Final / Chunk 3.4 — the
        // legacy 4-step imperative path (SP-write → setAudioFocusRuntime
        // → edit-bar-twin refresh → main-twin via mirror) collapses to
        // a single dispatch. The AudioModule reducer flips
        // state.audio.audioFocusEnabledPref and emits:
        //   1. Effect.PersistAudioFocusPref → SharedPrefs.AudioFocus
        //      (Chunk 3.1).
        //   2. Effect.ApplyAudioFocusRuntime → live-AudioManager update
        //      only when recording is Active (Chunk 3.2). Replaces
        //      RecordingStateController.setAudioFocusRuntime, scheduled
        //      for retire in the Block-5 RecordingStateController
        //      Folge-Plan.
        // Edit-bar twin re-renders via EditBarAudioFocusObserver
        // (Chunk 3.3) on the state-emit. Main-button-area twin
        // re-renders via the catalog AUDIO_FOCUS slot iconResolver.
        //
        // Pre-bind fallback: when pipelineBinder is null (narrow
        // window before service-bind during onCreateInputView), write
        // SP directly so the user's choice survives. The mirror picks
        // it up on bind.
        if (pipelineBinder != null) {
            pipelineBinder.dispatch(
                    net.devemperor.dictate.state.Action.AudioAction.ToggleAudioFocusPref.INSTANCE);
        } else {
            // PRE-BIND-FALLBACK: SP-write authorized because dispatcher
            // unavailable (review-fix G2, 2026-05-21).
            boolean current = DictatePrefsKt.get(sp, Pref.AudioFocus.INSTANCE);
            DictatePrefsKt.put(sp.edit(), Pref.AudioFocus.INSTANCE, !current).apply();
        }
    }

    @Override
    public void onEditAction(int actionId) {
        // Insertion unification — copy/paste/cut/undo/redo route through the
        // single InsertionService. It tries the host soft-API
        // (performContextMenuAction) first and, for the editors that ignore it
        // (WebViews, custom editors, some chat apps), runs the manual clipboard
        // fallback so cut/copy/paste work consistently across hosts.
        EditAction action = EditAction.fromAndroidId(actionId);
        if (action == null) {
            // Unmapped context-menu id — soft-API attempt only (no fallback).
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                ic.performContextMenuAction(actionId);
            } else {
                Log.w("DictateIME",
                        "onEditAction(" + actionId + ") — no InputConnection, skipping");
            }
            return;
        }
        insertionService().editAction(action);
    }

    /**
     * Manual clipboard implementation of cut/copy/paste for host
     * editors that do not honour {@link InputConnection#performContextMenuAction(int)}.
     * Mirrors AOSP's default semantics:
     *
     * <ul>
     *   <li>{@code paste} — read primary clip, commit its first item's text at the cursor.</li>
     *   <li>{@code copy} — read selected text (or "" when nothing selected), put on the clipboard.</li>
     *   <li>{@code cut}  — copy + delete the selection.</li>
     * </ul>
     *
     * Each step is null-guarded; missing clipboard / empty selection
     * degrades to a no-op silently (same outcome as the system
     * implementation).
     */
    private void performClipboardFallback(InputConnection ic, int actionId) {
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) return;

        if (actionId == android.R.id.paste) {
            android.content.ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            CharSequence text = clip.getItemAt(0).coerceToText(this);
            if (text != null && text.length() > 0) {
                ic.commitText(text, 1);
            }
        } else if (actionId == android.R.id.copy || actionId == android.R.id.cut) {
            // W4 hardening — safeReadSelectedText wraps getSelectedText in a
            // try-catch (stale IC implementations are documented to throw on
            // read), so a copy/cut on a half-dead host degrades to a no-op
            // instead of crashing the IME.
            String selected = safeReadSelectedText(ic);
            if (selected == null || selected.isEmpty()) return;
            clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("dictate", selected));
            if (actionId == android.R.id.cut) {
                // Replace the selection with empty text (which deletes it).
                ic.commitText("", 1);
            }
        }
    }

}
