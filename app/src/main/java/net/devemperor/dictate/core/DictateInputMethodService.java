package net.devemperor.dictate.core;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
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
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.util.Log;
import android.widget.Toast;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
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
import net.devemperor.dictate.widget.PulseLayout;

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
    private boolean audioFocusEnabled = true;
    // Language state moved into LanguageController (Phase 2 Quality-Gate W-7).
    // Read effective language via languageController.getEffectiveLanguage();
    // mutate via languageController.setLanguage(code).
    private boolean autoSwitchKeyboard = false;

    /**
     * True during the short synchronous window between {@link #uiController}'s
     * {@code preparePipeline()} and {@code startPipeline(...)} calls in
     * {@link #runTranscriptionViaOrchestrator()}. Under the current architecture this window
     * is a main-thread microwindow (no async work between prepare and start), so a rotation
     * in this window is practically impossible and the flag will never be observed true by
     * {@link #restoreUiState()}. The flag is retained as a defensive preparation for a
     * future refactor that makes the upload phase genuinely asynchronous — see the refactor
     * plan §R-6 discussion.
     */
    private volatile boolean isPreparing = false;

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
     * Service-Layer language controller (Phase 1 of language-chip-curation).
     *
     * Instantiated in {@link #onCreateInputView()} once {@link #uiController}
     * exists; disposed in {@link #cleanupOldControllers()} so the
     * {@link KeyboardUiController}'s callback list does not accumulate stale
     * entries across view re-creates.
     *
     * <p>Phase 1 only wires the controller — Phase 2 will hook
     * {@link LanguageController.Callback} into the chip-refresh path. Until
     * then the controller runs silently in the background; its only visible
     * effect is the SharedPreferences migration that already ran in
     * {@link DictateApplication#onCreate()}.</p>
     */
    private LanguageController languageController;

    /**
     * Phase 3 cross-instance bridge: invalidates the IME's per-view
     * {@link LanguageController}'s {@code lastEffective} cache when an
     * external writer (the Settings activity's Application-singleton
     * controller) mutates the {@code input_languages} or
     * {@code input_language_pos} keys.
     *
     * <p>Without this listener, returning from Settings to the IME shows a
     * stale chip until the next pipeline-state transition retriggers the
     * controller's {@code notifyIfChanged()} path.</p>
     *
     * <p>Registered in {@link #onCreateInputView()} after
     * {@link #languageController} construction; deregistered in
     * {@link #cleanupOldControllers()} so the listener does not survive
     * view-recreate alongside the discarded controller.</p>
     */
    private SharedPreferences.OnSharedPreferenceChangeListener inputLanguagesListener;

    /**
     * Service-side pipeline observer. Held as a field so it can be detached
     * via {@link KeyboardUiController#removeCallback(PipelineUiCallback)} on
     * view recreate. Phase 1 cross-phase refactor (Quality-Gate K-2): the
     * Service registers via {@code addCallback}, not the deprecated
     * single-slot {@code setCallback}, so multiple consumers (Service +
     * {@link LanguageController}) coexist without a Composite-Wrapper.
     */
    private PipelineUiCallback servicePipelineCallback;
    private File audioFile;
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

    // Recording controllers (extracted from God-Class)
    private RecordingStateController recordingStateController;
    private RecordingUiController recordingUiController;

    // Prompt data flow: InvalidationTracker auto-reloads prompts when DB changes
    private DictateDatabase dictateDb;
    private InvalidationTracker.Observer promptsInvalidationObserver;
    private final Runnable reloadPromptsRunnable = () -> reloadPrompts();

    // define views
    private ConstraintLayout dictateKeyboardView;
    private View mainButtonsCl;
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

        // 2. Services
        PrefsMigration.migrateProviderPrefs(sp);
        aiOrchestrator = new AIOrchestrator(sp, dictateDb.usageDao());
        promptService = PromptService.create(sp);
        autoFormattingService = AutoFormattingService.create(sp, aiOrchestrator);
        sessionManager = new SessionManager(DictateDatabase.getInstance(this));
        sessionTracker = new SessionTracker(DictateDatabase.getInstance(this).sessionDao());
        recordingRepository = new RecordingRepository(this);

        // 3. Managers
        promptQueueManager = new PromptQueueManager(promptDao::getAutoApplyIds, sp, this);

        // 4. Audio Focus (Lambda captures this.recordingStateController — safe: lazy eval)
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
        recordingStateController = new RecordingStateController(
            am, audioFocusRequest, new AmplitudeProcessor(), mainHandler);
        recordingManager = new RecordingManager(recordingStateController);
        bluetoothScoManager = new BluetoothScoManager(this, am, recordingStateController);
        recordingStateController.setManagers(recordingManager, bluetoothScoManager);

        // 6. Pipeline (this = PipelineCallback, survives rotation)
        pipelineOrchestrator = new PipelineOrchestrator(
            aiOrchestrator, autoFormattingService, promptQueueManager,
            promptService, sessionManager, sessionTracker, promptDao, this,
            recordingRepository,
            dictateDb.transcriptionDao(),
            dictateDb.processingStepDao(),
            dictateDb);

        // 6b. Initialise JobExecutor with the orchestrator so any caller
        // (IME + HistoryDetailActivity) can start jobs (Finding SEC-10-2).
        JobExecutor.INSTANCE.initialize(pipelineOrchestrator);

        // 7. User ID (one-time)
        if (DictatePrefsKt.get(sp, Pref.UserId.INSTANCE).equals("null")) {
            DictatePrefsKt.put(sp.edit(), Pref.UserId.INSTANCE,
                String.valueOf((int) (Math.random() * 1000000))).apply();
        }
    }

    // start method that is called when user opens the keyboard (also on view recreation / rotation)
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public View onCreateInputView() {
        Context context = new ContextThemeWrapper(this, R.style.Theme_Dictate);

        // ── 1. Clean up old controllers (on view recreation, not first call) ──
        cleanupOldControllers();

        // ── 2. Preferences that may change between rotations ──
        vibrationEnabled = DictatePrefsKt.get(sp, Pref.Vibration.INSTANCE);
        // Phase 2 Quality-Gate W-7: language state lives in LanguageController.
        // Pos preference is managed exclusively through the controller's
        // persistInputLanguagesAndPos pathway.

        // ── 3. View inflation + findViewByIds ──
        dictateKeyboardView = (ConstraintLayout) LayoutInflater.from(context).inflate(R.layout.activity_dictate_keyboard_view, null);
        dictateKeyboardView.setKeepScreenOn(false);
        keepScreenAwakeApplied = false;
        ViewCompat.setOnApplyWindowInsetsListener(dictateKeyboardView, (v, insets) -> {
            v.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom);
            return insets;  // fix for overlapping with navigation bar on Android 15+
        });

        mainButtonsCl = dictateKeyboardView.findViewById(R.id.main_buttons_cl);
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

        // History button
        editHistoryButton = dictateKeyboardView.findViewById(R.id.edit_history_btn);

        View pipelineProgressLl = dictateKeyboardView.findViewById(R.id.pipeline_progress_ll);

        // KeyboardStateManager (deterministic visibility calculator)
        // Note: recordingStateController and uiController are initialized after stateManager,
        // but lambdas are evaluated lazily, so this is safe
        stateManager = new KeyboardStateManager(
            new KeyboardViews(mainButtonsCl, editButtonsKeyboardLl, promptsCl, emojiPickerCl,
                qwertzContainer, overlayCharactersLl, pauseButton, trashButton,
                promptRecordingControlsLl, promptTrashBtn,
                promptsRv, pipelineProgressLl),
            () -> recordingStateController != null && recordingStateController.getState() instanceof RecordingState.Active,
            () -> recordingStateController != null && recordingStateController.getState() instanceof RecordingState.Paused,
            () -> pipelineOrchestrator.isRunning(),
            () -> DictatePrefsKt.get(sp, Pref.RewordingEnabled.INSTANCE),
            keepAwake -> { updateKeepScreenAwake(keepAwake); return kotlin.Unit.INSTANCE; },
            infoBarController,
            () -> uiController != null && uiController.getState() instanceof PipelineUiState.Running,
            /* isReprocessStaging */ () -> uiController != null
                    && uiController.getState() instanceof PipelineUiState.ReprocessStaging
        );

        // KeyboardUiController (wraps pipeline progress views, delegates visibility to stateManager)
        uiController = new KeyboardUiController(new KeyboardUiController.PipelineViews(
            dictateKeyboardView.findViewById(R.id.pipeline_steps_container),
            dictateKeyboardView.findViewById(R.id.pipeline_scroll_view),
            recordButton,
            infoCl,
            LayoutInflater.from(context),
            mainHandler
        ), stateManager);

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
                recordPulseLayout
            ),
            sp, stateManager, this,
            () -> getCurrentInputConnection(),
            qwertzKeyboardView.getKeyPressAnimator()
        );
        mainButtonsController.registerAllListeners();
        mainButtonsController.initializeKeyPressAnimations();

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
        recordingUiController = new RecordingUiController(
            recordButton, pauseButton, resendButton,
            recordingAnimation, stateManager, this,
            () -> getDictateButtonText(),
            () -> DictatePrefsKt.get(sp, Pref.Animations.INSTANCE),
            () -> new File(getCacheDir(), DictatePrefsKt.get(sp, Pref.LastFileName.INSTANCE)).exists()
                    && DictatePrefsKt.get(sp, Pref.ResendButton.INSTANCE),
            () -> qwertzKeyboardView != null ? qwertzKeyboardView.findButtonForAction(KeyAction.RECORD) : null,
            promptRecIndicatorBtn,
            promptPauseBtn,
            () -> { vibrate(); onPauseClicked(); return kotlin.Unit.INSTANCE; },
            () -> { vibrate(); stopRecording(); return kotlin.Unit.INSTANCE; }
        );

        // ── 4a. LanguageController (Phase 1 + Phase 2 wiring) ──
        // Built AFTER uiController exists because it depends on the
        // PipelineUiStateReader implemented by KeyboardUiController. Self-
        // registers as a PipelineUiCallback inside its constructor; the
        // matching dispose() in cleanupOldControllers() removes it before
        // the next view-recreate so the callback list does not leak.
        //
        // Phase 2: the Callback drives the chip refresh + record-button
        // label update on every effective-language change (auto-curation
        // in idle-mode, transient override during ReprocessStaging).
        //
        // Quality-Gate W-12: languageController zuerst, damit lastEffective
        // vor servicePipelineCallback aktualisiert wird (callbacks-Index 0).
        languageController = new LanguageController(sp, uiController);
        languageController.setCallback((oldCode, newCode) -> {
            refreshLanguageChip();
            if (mainButtonsController != null) {
                mainButtonsController.updateRecordButtonText(getDictateButtonText());
            }
        });

        // Phase 3 cross-instance bridge: the Settings activity owns a separate
        // Application-singleton LanguageController. When the user edits the
        // curated list there, both controllers' SharedPreferences-backed reads
        // see the new value, but only the writing controller's lastEffective
        // cache is up-to-date. Without an explicit invalidation, returning
        // to the IME shows a stale chip until the next pipeline state change.
        // Listening on the two relevant keys plugs that gap with a single
        // refreshFromPrefs() call (idempotent through the lastEffective guard).
        inputLanguagesListener = (changedPrefs, key) -> {
            if (Pref.InputLanguages.INSTANCE.getKey().equals(key)
                    || Pref.InputLanguagePos.INSTANCE.getKey().equals(key)) {
                if (languageController != null) {
                    languageController.refreshFromPrefs();
                }
            }
        };
        sp.registerOnSharedPreferenceChangeListener(inputLanguagesListener);

        // Pipeline UI callbacks: QWERTZ button updates from pipeline state.
        // Phase 1 cross-phase refactor: Service now uses addCallback() rather than the
        // deprecated setCallback() so multiple consumers (Service + LanguageController)
        // coexist on the same KeyboardUiController without a Composite-Wrapper.
        // Registered AFTER languageController so it sits at callbacks-Index 1
        // (W-12: language state must update before any UI consumer reads it).
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

        return dictateKeyboardView;
    }

    // method is called if the user closed the keyboard
    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);

        // Hide QWERTZ keyboard when the input view is finishing (app switch, background, etc.)
        hideQwertzKeyboard();

        // State (A): Recording is active or paused -> delegate to controller (pause + timeout)
        if (recordingStateController.getState().isRecordingOrPaused()
                || recordingStateController.getState() instanceof RecordingState.Preparing) {
            recordingStateController.onKeyboardHidden();
            stateManager.setContentArea(ContentArea.MAIN_BUTTONS);
            return;
        }

        // State (B): API request is running -> let it continue, just hide content panels
        if (pipelineOrchestrator.isRunning()) {
            stateManager.setContentArea(ContentArea.MAIN_BUTTONS);
            return;
        }

        // State (C): Idle -> full cleanup
        pipelineOrchestrator.cancel();
        pendingLivePromptChain = false;
        isPreparing = false;
        // Note: PipelineConfig is owned by uiController; stopPipeline() nulls it below.

        bluetoothScoManager.unregisterReceiver();

        infoBarController.dismiss();
        stateManager.setContentArea(ContentArea.MAIN_BUTTONS);
        stateManager.refresh();
        uiController.stopPipeline();
        livePrompt = false;
        updatePromptButtonsEnabledState();
    }

    @Override
    public void onDestroy() {
        // Clean up long-lived objects
        if (mainHandler != null) {
            mainHandler.removeCallbacks(reloadPromptsRunnable);
        }
        if (recordingStateController != null) recordingStateController.onDestroy();
        if (pipelineOrchestrator != null) {
            pipelineOrchestrator.shutdown();
        }
        if (promptsInvalidationObserver != null && dictateDb != null) {
            dictateDb.getInvalidationTracker().removeObserver(promptsInvalidationObserver);
        }
        if (bluetoothScoManager != null) bluetoothScoManager.unregisterReceiver();
        // Phase 4 follow-up: dispose the language controller and its prefs listener.
        // The Service may be destroyed without a preceding view-recreate (the IME
        // process can be torn down by the OS while a view is still attached), in
        // which case cleanupOldControllers() is never called and the listener +
        // controller would leak through the SharedPreferences and the
        // PipelineUiStateReader callback list. Disposing here is idempotent with
        // cleanupOldControllers() because both null out the references afterwards.
        if (languageController != null) {
            languageController.dispose();
            languageController = null;
        }
        if (inputLanguagesListener != null && sp != null) {
            sp.unregisterOnSharedPreferenceChangeListener(inputLanguagesListener);
            inputLanguagesListener = null;
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
        // Phase 1 cross-phase: dispose the language controller bound to the old
        // uiController so its self-registration is reverted before the new
        // controller is constructed in onCreateInputView. Without this the old
        // controller would keep observing a discarded view's state.
        if (languageController != null) {
            languageController.dispose();
            languageController = null;
        }
        // Phase 3 cross-instance bridge: deregister the prefs listener that was
        // forwarding external Settings-writes into the (now disposed) language
        // controller. The fresh controller in the upcoming onCreateInputView
        // will register a new listener bound to the new instance.
        if (inputLanguagesListener != null) {
            sp.unregisterOnSharedPreferenceChangeListener(inputLanguagesListener);
            inputLanguagesListener = null;
        }
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
                mainHandler.post(() -> {
                    audioFile = file;
                    runTranscriptionViaOrchestrator();
                });
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
        } else if (isPreparing) {
            // Defensive: under the current synchronous Preparing window this branch is
            // practically unreachable (see isPreparing field docs). Kept as prep for a
            // future async-upload phase so the Sending... indicator can survive rotation.
            uiController.preparePipeline();
        } else if (pipelineOrchestrator.isRunning()) {
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
                    } else if (recordingStateController.getState() instanceof RecordingState.Idle) {
                        startRecording();
                    } else if (recordingStateController.getState().isRecordingOrPaused()) {
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
                    if ((recordingStateController.getState().isRecordingOrPaused()
                            || recordingStateController.getState() instanceof RecordingState.Preparing) && !livePrompt) {
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
        } else {
            promptsAdapter.setQueuedPromptOrder(promptQueueManager.getQueuedIds());
        }
    }

    /**
     * Phase 2 §2.1: refreshes the always-visible language chip's label
     * from the current effective language. Called on initial render, on
     * every {@link LanguageController.Callback#onEffectiveLanguageChanged}
     * fire, and any place the service explicitly wants the chip in lock-
     * step with the controller's view of "current language".
     */
    private void refreshLanguageChip() {
        if (promptsAdapter == null || languageController == null) return;
        String code = languageController.getEffectiveLanguage();
        String label = LanguageLabelResolver.INSTANCE.resolveLabel(code);
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
     * the idle and the ReprocessStaging modes; the {@link LanguageController}
     * decides whether the click results in a permanent write (with auto-
     * curation) or a transient ReprocessStaging override.
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
        if (languageController == null) return;

        // Quality-Gate N-6: getCuratedLanguages() returns the list already
        // label-sorted and free of duplicates / unknown codes (plugin
        // sanitize contract). Just compute "others" for the lower block.
        List<String> curatedOrdered = languageController.getCuratedLanguages();
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
                languageController.setLanguage(code);
                return true;
            }
            return false;
        });
        popup.show();
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

        // Determine if we are truly idle (no recording, no pipeline running).
        // When not idle, skip UI resets that would overwrite state restored by restoreUiState().
        boolean isIdle = recordingStateController.getState() instanceof RecordingState.Idle
                && !pipelineOrchestrator.isRunning();

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
            // enable resend button if previous audio file still exists in cache
            if (new File(getCacheDir(), DictatePrefsKt.get(sp, Pref.LastFileName.INSTANCE)).exists()
                    && DictatePrefsKt.get(sp, Pref.ResendButton.INSTANCE)) {
                resendButton.setVisibility(View.VISIBLE);
            } else {
                resendButton.setVisibility(View.GONE);
            }

            // get the currently selected input language
            recordButton.setText(getDictateButtonText());
        }

        // check if user enabled audio focus
        audioFocusEnabled = DictatePrefsKt.get(sp, Pref.AudioFocus.INSTANCE);

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
            audioFile = new File(getCacheDir(), DictatePrefsKt.get(sp, Pref.TranscriptionAudioFile.INSTANCE));
            DictatePrefsKt.put(sp.edit(), Pref.LastFileName.INSTANCE, audioFile.getName()).apply();

            sp.edit().remove(Pref.TranscriptionAudioFile.INSTANCE.getKey()).apply();
            runTranscriptionViaOrchestrator();

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

    private void startRecording() {
        promptQueueManager.prepareAutoApplyQueue();

        audioFile = new File(getCacheDir(), "audio.m4a");
        DictatePrefsKt.put(sp.edit(), Pref.LastFileName.INSTANCE, audioFile.getName()).apply();

        boolean useBt = DictatePrefsKt.get(sp, Pref.UseBluetoothMic.INSTANCE);
        audioFocusEnabled = DictatePrefsKt.get(sp, Pref.AudioFocus.INSTANCE);
        recordingStateController.startRecording(audioFile, useBt, audioFocusEnabled);
    }

    private void stopRecording() {
        recordingStateController.stopRecording();
        // onRecordingCompleted callback triggers runTranscriptionViaOrchestrator
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
     * Prepares UI and launches transcription pipeline via PipelineOrchestrator.
     * Replaces the old startWhisperApiRequest() method.
     */
    private void runTranscriptionViaOrchestrator() {
        // Preparing state: button disabled, shows "Sending..." (state-driven via PipelineUiState.Preparing)
        isPreparing = true;
        try {
            uiController.preparePipeline();
            resendButton.setVisibility(View.GONE);
            infoBarController.dismiss();
            updatePromptButtonsEnabledState();
            stateManager.refresh(); // updates pause/trash/prompts visibility

            // Show pipeline progress
            int totalSteps = 1; // transcription always
            if (autoFormattingService.isEnabled()) totalSteps++;
            totalSteps += promptQueueManager.getQueuedIds().size();

            boolean autoEnter = DictatePrefsKt.get(sp, Pref.AutoEnter.INSTANCE);
            uiController.startPipeline(totalSteps, new KeyboardUiController.AutoEnterConfig(autoEnter));

            // Phase 2 §2.6: language source is now the LanguageController
            // (Single-Source-of-Truth across normal mode + ReprocessStaging
            // override). "detect" remains the explicit "let Whisper detect"
            // sentinel — passed as null on the wire.
            String effectiveLanguage = languageController != null
                    ? languageController.getEffectiveLanguage()
                    : "detect";
            String language = !"detect".equals(effectiveLanguage) ? effectiveLanguage : null;
            String stylePrompt = promptService.resolveWhisperStylePrompt(effectiveLanguage);

            EditorInfo info = getCurrentInputEditorInfo();
            boolean showResend = new File(getCacheDir(), DictatePrefsKt.get(sp, Pref.LastFileName.INSTANCE)).exists()
                    && DictatePrefsKt.get(sp, Pref.ResendButton.INSTANCE);

            // W3: Route the initial recording pipeline through JobExecutor so
            // the lifecycle is tracked in ActiveJobRegistry, the single-job
            // lock applies, and cancel() goes through the cooperative token.
            // Pre-allocate the sessionId so JobExecutor.register() happens
            // BEFORE the orchestrator persists the session row.
            String preAllocatedId = java.util.UUID.randomUUID().toString();
            JobRequest.TranscriptionPipeline request = new JobRequest.TranscriptionPipeline(
                    preAllocatedId,
                    totalSteps,
                    JobRequest.TranscriptionKind.RECORDING,
                    /* audioFilePath */ audioFile.getAbsolutePath(),
                    /* language */ language,
                    /* modelOverride */ null,
                    /* queuedPromptIds */ promptQueueManager.getQueuedIds(),
                    /* targetAppPackage */ info != null ? info.packageName.toString() : null,
                    /* recordingsDir */ new File(getFilesDir(), "recordings"),
                    /* reuseSessionId */ null,
                    /* stylePrompt */ stylePrompt,
                    /* origin */ net.devemperor.dictate.database.entity.SessionOrigin.KEYBOARD,
                    /* livePrompt */ livePrompt,
                    /* autoSwitchKeyboard */ autoSwitchKeyboard,
                    /* showResendButton */ showResend
            );

            pendingLivePromptChain = livePrompt;
            livePrompt = false;
            autoSwitchKeyboard = false;

            boolean started = JobExecutor.INSTANCE.start(this, request);
            if (!started) {
                // Another job is active — the UI we just set up for "preparing"
                // is out of sync. Reset and inform the user.
                uiController.stopPipeline();
                showJobBusyToast();
            }
        } finally {
            // JobExecutor.start is non-blocking (submits onto its own executor),
            // so clearing the flag here is correct: from this point on the
            // pipeline is owned by JobExecutor and observed via the registry.
            isPreparing = false;
        }
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

        // isPreparing is cleared synchronously in runTranscriptionViaOrchestrator's finally
        // block; this is a safety net in case a future async Preparing phase forgets.
        isPreparing = false;

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
     * language. The full self-heal + StringSet sanitisation block has
     * moved into {@link InputLanguagesPlugin#sanitize} and the
     * {@link LanguageController} pos resync; this method is now a pure
     * lookup.
     */
    private String getDictateButtonText() {
        if (languageController == null) {
            // Defensive: only happens during the brief window before
            // onCreateInputView wires the controller. Fall back to the
            // first entry of InputLanguagesPlugin.defaultValue so there is
            // a single source of truth for the default code (avoids a
            // hard-coded "detect" drifting out of sync with the plugin).
            String defaultCode = InputLanguagesPlugin.INSTANCE.getDefaultValue().get(0);
            return LanguageLabelResolver.INSTANCE.recordLabelFor(defaultCode);
        }
        String code = languageController.getEffectiveLanguage();
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
        } else if (recordingStateController.getState() instanceof RecordingState.Idle) {
            startRecording();
        } else if (recordingStateController.getState().isRecordingOrPaused()) {
            stopRecording();
        }
    }

    @Override
    public void onRecordLongClicked() {
        RecordingState currentState = recordingStateController.getState();
        if (currentState instanceof RecordingState.Idle) {
            Intent intent = new Intent(this, DictateSettingsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra("net.devemperor.dictate.open_file_picker", true);
            startActivity(intent);
        } else if (currentState.isRecordingOrPaused() && !livePrompt && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
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

        recordingStateController.cancelRecording();
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

                JobRequest.TranscriptionPipeline request = new JobRequest.TranscriptionPipeline(
                        targetSessionId,
                        totalSteps,
                        JobRequest.TranscriptionKind.REPROCESS_STAGING,
                        /* audioFilePath */ audioPath,
                        /* language */ selectedLanguage,
                        /* modelOverride */ selectedModel,
                        /* queuedPromptIds */ editableQueue,
                        /* targetAppPackage */ targetAppPackage,
                        /* recordingsDir */ new File(getFilesDir(), "recordings"),
                        /* reuseSessionId */ targetSessionId,
                        /* stylePrompt */ null,
                        /* origin */ net.devemperor.dictate.database.entity.SessionOrigin.KEYBOARD
                );

                boolean started = JobExecutor.INSTANCE.start(this, request);
                if (!started) {
                    showJobBusyToast();
                    return;
                }

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
        recordingStateController.togglePause();
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
        if (recordingStateController.getState().isRecordingOrPaused()
                || recordingStateController.getState() instanceof RecordingState.Preparing) {
            recordingStateController.cancelRecording();
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
        } else {
            // Legacy standalone-prompt path (no Registry entry).
            cancelInfo = pipelineOrchestrator.cancel();
        }

        pendingLivePromptChain = false;
        isPreparing = false;

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
    public void onLanguageCycled() {
        // Phase 2 §2.8: rotate through the curated list (label-sorted by
        // the InputLanguagesPlugin contract). The setLanguage call routes
        // through the LanguageController; in idle mode that triggers a
        // permanent write + pos resync, in ReprocessStaging it would set
        // a transient override (cycle is wired only to the long-press on
        // the main record button, which is itself disabled during staging).
        if (languageController == null) return;
        // Validator-Fix: early-return during ReprocessStaging. setLanguage
        // routes as a transient override there and does NOT mutate
        // Pref.InputLanguagePos, so a second cycle click would jump to the
        // SAME follow-up language — UX-feindlich. In ReprocessStaging the
        // user must pick the override language explicitly via the chip
        // popup; cycling has no consistent meaning.
        if (uiController != null
                && uiController.getState() instanceof PipelineUiState.ReprocessStaging) {
            return;
        }
        List<String> curated = languageController.getCuratedLanguages();
        if (curated.isEmpty()) return;
        int pos = DictatePrefsKt.get(sp, Pref.InputLanguagePos.INSTANCE);
        int next = (pos + 1) % curated.size();
        languageController.setLanguage(curated.get(next));
        // The record-button label is refreshed via the LanguageController
        // callback wired in onCreateInputView — no manual setText here.
    }

    @Override
    public void onEditAction(int actionId) {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection != null) {
            inputConnection.performContextMenuAction(actionId);
        }
    }

}
