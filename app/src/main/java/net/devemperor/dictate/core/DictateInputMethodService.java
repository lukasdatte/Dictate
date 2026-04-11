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
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
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
import net.devemperor.dictate.keyboard.KeyAction;
import net.devemperor.dictate.keyboard.QwertzKeyboardController;
import net.devemperor.dictate.keyboard.QwertzKeyboardLayout;
import net.devemperor.dictate.keyboard.QwertzKeyboardView;
import net.devemperor.dictate.preferences.DictatePrefsKt;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
    private int currentInputLanguagePos;
    private String currentInputLanguageValue;
    private boolean autoSwitchKeyboard = false;

    /** Transient per-send override for auto-enter. null = no pipeline active (use global pref). */
    private Boolean autoEnterOverride = null;

    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private PipelineOrchestrator pipelineOrchestrator;
    private KeyboardUiController uiController;
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
        sessionTracker = new SessionTracker(sessionManager);
        sessionTracker.restoreLastSessionIdFromPrefs(sp);
        dbExecutor.execute(() -> sessionTracker.restoreLastOutputFromDb());

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
            promptService, sessionManager, sessionTracker, promptDao, this);

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
        currentInputLanguagePos = DictatePrefsKt.get(sp, Pref.InputLanguagePos.INSTANCE);

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
                        // Pipeline active — set current state, timer tick will keep updating
                        PipelineUiState.Running s = (PipelineUiState.Running) uiController.getState();
                        recordingUiController.updateQwertzRecButtonForPipeline(
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
            () -> uiController.getCurrentMode()
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

        // Pipeline UI callbacks: QWERTZ button updates from pipeline state
        uiController.setOnPipelineTimerTick((runningState, elapsedMs) -> {
            if (recordingUiController != null) {
                recordingUiController.updateQwertzRecButtonForPipeline(runningState, elapsedMs);
            }
            return kotlin.Unit.INSTANCE;
        });

        uiController.setOnPipelineUiStateChanged((oldState, newState) -> {
            if (recordingUiController != null) {
                if (newState instanceof PipelineUiState.Idle) {
                    recordingUiController.updateQwertzRecButton(false);  // QWERTZ → Mic-Icon
                } else if (newState instanceof PipelineUiState.Running) {
                    // One-shot initialization of the QWERTZ button for pipeline display.
                    // Subsequent per-tick updates arrive via onPipelineTimerTick.
                    // NOTE: The monolithic updateQwertzRecButtonForPipeline() is used here
                    // because the planned split into enterPipelineDisplay()/updatePipelineTimer()
                    // is a documented ground-truth deviation (see plan §3b).
                    recordingUiController.updateQwertzRecButtonForPipeline(
                        (PipelineUiState.Running) newState, uiController.getLatestPipelineElapsedMs());
                } else if (newState instanceof PipelineUiState.Preparing) {
                    // Upload phase: make sure the QWERTZ button shows the idle mic icon
                    // (clears any leftover recording-state rendering).
                    recordingUiController.updateQwertzRecButton(false);
                }
            }
            return kotlin.Unit.INSTANCE;
        });

        // ── 5. Rewire callbacks (connect long-lived objects to new UI controllers) ──
        // INVARIANT: Order is controllers (above) → rewireCallbacks() → restoreUiState()
        // restoreUiState() triggers state changes that need the callback set in rewireCallbacks().
        // Without prior re-wiring, state changes go nowhere.
        rewireCallbacks();

        // ── 6. Restore current state onto fresh UI ──
        restoreUiState();

        // ── 7. Prompts adapter + InvalidationTracker ──
        setupPromptsAdapter(context);

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
        autoEnterOverride = null;

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
            uiController.stopActiveTimer();
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
        if (pipelineOrchestrator.isRunning()) {
            int total = pipelineOrchestrator.getTotalSteps();
            int completedSoFar = pipelineOrchestrator.getCompletedSteps();
            String stepName = pipelineOrchestrator.getCurrentStepName();

            // startPipeline instead of showPipelineProgress — state is set correctly
            boolean autoEnter = autoEnterOverride != null ? autoEnterOverride
                : DictatePrefsKt.get(sp, Pref.AutoEnter.INSTANCE);
            uiController.startPipeline(total > 0 ? total : 1, autoEnter, completedSoFar);

            // Show the currently running step
            uiController.addRunningStep(stepName != null ? stepName : "\u2026");
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
        uiController.preparePipeline();
        resendButton.setVisibility(View.GONE);
        infoBarController.dismiss();
        updatePromptButtonsEnabledState();
        stateManager.refresh(); // updates pause/trash/prompts visibility

        // Show pipeline progress
        int totalSteps = 1; // transcription always
        if (autoFormattingService.isEnabled()) totalSteps++;
        totalSteps += promptQueueManager.getQueuedIds().size();

        autoEnterOverride = DictatePrefsKt.get(sp, Pref.AutoEnter.INSTANCE);
        uiController.startPipeline(totalSteps, (boolean) autoEnterOverride);

        String language = currentInputLanguageValue != null && !currentInputLanguageValue.equals("detect")
                ? currentInputLanguageValue : null;
        String stylePrompt = promptService.resolveWhisperStylePrompt(currentInputLanguageValue);

        EditorInfo info = getCurrentInputEditorInfo();
        boolean showResend = new File(getCacheDir(), DictatePrefsKt.get(sp, Pref.LastFileName.INSTANCE)).exists()
                && DictatePrefsKt.get(sp, Pref.ResendButton.INSTANCE);

        PipelineOrchestrator.PipelineConfig config = new PipelineOrchestrator.PipelineConfig(
            audioFile, language, stylePrompt, livePrompt, autoSwitchKeyboard,
            showResend, new File(getFilesDir(), "recordings"),
            info != null ? info.packageName : null);

        pendingLivePromptChain = livePrompt;
        livePrompt = false;
        autoSwitchKeyboard = false;

        pipelineOrchestrator.runTranscriptionPipeline(config);
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
        // autoEnterOverride is the service-side source of truth (see plan edge-case "Auto-Enter-Wahrheit").
        String displayName = model.getId() == -1 ? getString(R.string.dictate_live_prompt) : model.getName();
        // Direct-prompt-button callers never initialize autoEnterOverride — seed it from prefs
        // to avoid NPE on auto-unbox (the removed guard used to do this implicitly).
        if (autoEnterOverride == null) {
            autoEnterOverride = DictatePrefsKt.get(sp, Pref.AutoEnter.INSTANCE);
        }
        uiController.startPipeline(1, autoEnterOverride, 0);

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

        // Reset auto-enter override on main thread (AFTER commitTextToInputConnection which was
        // also posted to main thread by onPipelineCompleted — order is guaranteed by mainHandler queue)
        mainHandler.post(() -> autoEnterOverride = null);

        dbExecutor.execute(() -> {
            sessionTracker.resetSession();
            sessionTracker.persistToPrefs(sp);
            mainHandler.post(() -> {
                if (uiController == null) return;  // View recreation not yet complete
                uiController.stopPipeline();  // → updatePipelineState(Idle) → Callback → QWERTZ reset
                uiController.restoreRecordButtonIdle(
                    getDictateButtonText(),
                    R.drawable.ic_baseline_mic_20,
                    R.drawable.ic_baseline_folder_open_20);
                // QWERTZ-Reset happens automatically via onPipelineUiStateChanged callback
            });
        });
    }

    private boolean isAutoEnterActive() {
        if (autoEnterOverride != null) return autoEnterOverride;
        return DictatePrefsKt.get(sp, Pref.AutoEnter.INSTANCE);
    }

    private void toggleAutoEnterOverride() {
        // Only meaningful during Running — during Preparing the auto-enter chip is not visible,
        // and during Idle there is no pipeline to toggle against.
        if (!uiController.isPipelineRunning()) return;
        if (autoEnterOverride == null) return;
        autoEnterOverride = !autoEnterOverride;
        // Service is single source of truth for autoEnterOverride —
        // sets the concrete value (no sync risk between service and controller)
        uiController.setAutoEnter(autoEnterOverride);
    }

    private void commitTextToInputConnection(String text, InsertionSource source) {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection == null) return;

        // Capture replaced (selected) text before commit for undo-buffer / audit
        String replacedText = null;
        if (source != null) {
            CharSequence sel = inputConnection.getSelectedText(0);
            if (sel != null && sel.length() > 0) replacedText = sel.toString();
        }

        String output = text == null ? "" : text;
        if (DictatePrefsKt.get(sp, Pref.InstantOutput.INSTANCE)) {
            inputConnection.commitText(output, 1);
        } else if (mainHandler != null) {
            int speed = DictatePrefsKt.get(sp, Pref.OutputSpeed.INSTANCE);
            for (int i = 0; i < output.length(); i++) {
                char character = output.charAt(i);
                String characterString = String.valueOf(character);
                long delay = (long) (i * (20L / (speed / 5f)));
                mainHandler.postDelayed(() -> {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) {
                        ic.commitText(characterString, 1);
                    }
                }, delay);
            }
        } else {
            inputConnection.commitText(output, 1);
        }

        // Auto-enter: send as separate control character with delay so terminal emulators
        // (e.g. Termux/Claude Code) treat it as a distinct keystroke, not part of the paste block
        if (isAutoEnterActive()) {
            scheduleAutoEnter(output);
        }

        // Persist text insertion and update session's final output
        if (source != null && output.length() > 0) {
            final String fReplacedText = replacedText;
            final String fSessionId = sessionTracker.getCurrentSessionId();
            final String fStepId = sessionTracker.getCurrentStepId();
            final String fTranscriptionId = sessionTracker.getCurrentTranscriptionId();
            final String pkg = getCurrentInputEditorInfo() != null
                ? getCurrentInputEditorInfo().packageName : null;

            dbExecutor.execute(() -> {
                sessionManager.logTextInsertion(fSessionId, output, fReplacedText, pkg,
                    null, fStepId, fTranscriptionId, InsertionMethod.COMMIT);
                if (fSessionId != null) {
                    sessionManager.updateFinalOutputText(fSessionId, output);
                }
            });
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

    private String getDictateButtonText() {
        List<String> allLanguagesValues = Arrays.asList(getResources().getStringArray(R.array.dictate_input_languages_values));
        List<String> recordDifferentLanguages = Arrays.asList(getResources().getStringArray(R.array.dictate_record_different_languages));

        LinkedHashSet<String> defaultLanguages = new LinkedHashSet<>(Arrays.asList(getResources().getStringArray(R.array.dictate_default_input_languages)));
        Set<String> storedLanguages = DictatePrefsKt.getStringSet(sp, Pref.InputLanguages.INSTANCE, defaultLanguages);
        LinkedHashSet<String> sanitizedLanguages = new LinkedHashSet<>();
        for (String language : storedLanguages) {
            if (allLanguagesValues.contains(language)) sanitizedLanguages.add(language);
        }
        if (sanitizedLanguages.isEmpty()) sanitizedLanguages.addAll(defaultLanguages);
        if (!sanitizedLanguages.equals(storedLanguages)) {
            DictatePrefsKt.putStringSet(sp.edit(), Pref.InputLanguages.INSTANCE, sanitizedLanguages).apply();
        }

        List<String> languagesList = new ArrayList<>(sanitizedLanguages);
        if (currentInputLanguagePos >= languagesList.size()) currentInputLanguagePos = 0;
        DictatePrefsKt.put(sp.edit(), Pref.InputLanguagePos.INSTANCE, currentInputLanguagePos).apply();

        currentInputLanguageValue = languagesList.get(currentInputLanguagePos);
        int languageIndex = allLanguagesValues.indexOf(currentInputLanguageValue);
        if (languageIndex < 0) {
            currentInputLanguageValue = allLanguagesValues.get(0);
            languageIndex = 0;
        }
        return recordDifferentLanguages.get(languageIndex);
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
        String lastOutput = sessionTracker.getLastOutput();
        if (lastOutput != null) {
            commitTextToInputConnection(lastOutput, InsertionSource.TRANSCRIPTION);
        }
    }

    @Override
    public void onResendLongClicked() {
        if (audioFile == null) audioFile = new File(getCacheDir(), DictatePrefsKt.get(sp, Pref.LastFileName.INSTANCE));
        sessionTracker.reuseLastSession();
        runTranscriptionViaOrchestrator();
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
        recordingStateController.cancelRecording();
        livePrompt = false;
        updatePromptButtonsEnabledState();
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
        PipelineOrchestrator.CancelInfo cancelInfo = pipelineOrchestrator.cancel();
        pendingLivePromptChain = false;
        autoEnterOverride = null;

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

            sessionTracker.resetSession();
            sessionTracker.persistToPrefs(sp);

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
        currentInputLanguagePos++;
        recordButton.setText(getDictateButtonText());
    }

    @Override
    public void onEditAction(int actionId) {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection != null) {
            inputConnection.performContextMenuAction(actionId);
        }
    }

}
