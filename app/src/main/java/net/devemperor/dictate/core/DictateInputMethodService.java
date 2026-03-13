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
import android.media.MediaRecorder;
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
import net.devemperor.dictate.keyboard.QwertzKeyboardController;
import net.devemperor.dictate.keyboard.QwertzKeyboardLayout;
import net.devemperor.dictate.keyboard.QwertzKeyboardView;
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
        implements RecordingManager.RecordingCallback,
                   BluetoothScoManager.BluetoothScoCallback,
                   PromptQueueManager.PromptQueueCallback,
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

    // Bluetooth/SCO state kept in service (for startRecording coordination)
    private boolean isPreparingRecording = false; // true while we wait for SCO before starting recorder
    private boolean recordingPending = false;     // flag to start recording after SCO connected
    private boolean recordingUsesBluetooth = false; // current recording actually uses BT mic

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

    // ===== RecordingManager.RecordingCallback =====

    @Override
    public void onRecordingStarted() {
        recordingUsesBluetooth = bluetoothScoManager.isScoStarted();
        isPreparingRecording = false;
        recordingPending = false;
        updatePromptButtonsEnabledState();

        mainHandler.post(() -> {
            recordButton.setEnabled(true);
            recordButton.setText(R.string.dictate_send);
            mainButtonsController.applyRecordingIconState(true);
            mainButtonsController.updateRecordButtonIconWhileRecording(true, recordingUsesBluetooth);
            stateManager.refresh(); // updates pause/trash visibility + keep-screen-awake
            resendButton.setVisibility(View.GONE);
            // Show recording indicator in prompt bar when QWERTZ keyboard is visible
            if (stateManager.getContentArea() == ContentArea.QWERTZ) {
                uiController.showRecordingIndicator();
            }
        });
    }

    @Override
    public void onRecordingStopped(File audioFile) {
        // No-op: stop is always followed by explicit action (runTranscriptionViaOrchestrator or UI reset)
    }

    @Override
    public void onRecordingPaused() {
        mainHandler.post(() -> {
            pauseButton.setForeground(AppCompatResources.getDrawable(this, R.drawable.ic_baseline_mic_24));
            mainButtonsController.pausePulseAnimation();
        });
    }

    @Override
    public void onRecordingResumed() {
        mainHandler.post(() -> {
            pauseButton.setForeground(AppCompatResources.getDrawable(this, R.drawable.ic_baseline_pause_24));
            mainButtonsController.resumePulseAnimation();
        });
    }

    @Override
    public void onTimerTick(long elapsedMs) {
        mainHandler.post(() -> recordButton.setText(getString(R.string.dictate_send,
                String.format(Locale.getDefault(), "%02d:%02d", (int) (elapsedMs / 60000), (int) (elapsedMs / 1000) % 60))));
    }

    // ===== BluetoothScoManager.BluetoothScoCallback =====

    @Override
    public void onScoConnected() {
        // If we were waiting to start the recording until SCO connects, start now
        if (recordingPending) {
            proceedStartRecording(MediaRecorder.AudioSource.VOICE_COMMUNICATION, true);
        }

        // Update icon if we are recording and currently using BT
        if (recordingManager.isRecording()) {
            mainButtonsController.updateRecordButtonIconWhileRecording(true, recordingUsesBluetooth);
        }
    }

    @Override
    public void onScoDisconnected() {
        // If we were recording using BT and it got disconnected, keep recording and switch icon
        if (recordingManager.isRecording() && recordingUsesBluetooth) {
            recordingUsesBluetooth = false;
            mainButtonsController.updateRecordButtonIconWhileRecording(true, false);
        }
    }

    @Override
    public void onScoFailed() {
        // SCO timeout: fall back to MIC
        if (recordingPending) {
            proceedStartRecording(MediaRecorder.AudioSource.MIC, false);
        }
    }

    // ===== PromptQueueManager.PromptQueueCallback =====

    @Override
    public void onQueueChanged(List<Integer> queuedIds) {
        if (promptsAdapter == null || mainHandler == null) return;
        mainHandler.post(() -> promptsAdapter.setQueuedPromptOrder(queuedIds));
    }

    // start method that is called when user opens the keyboard
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public View onCreateInputView() {
        Context context = new ContextThemeWrapper(this, R.style.Theme_Dictate);

        // initialize some stuff
        mainHandler = new Handler(Looper.getMainLooper());
        deleteHandler = new Handler();

        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        sp = getSharedPreferences("net.devemperor.dictate", MODE_PRIVATE);
        DictateDatabase dictateDb = DictateDatabase.getInstance(this);
        promptDao = dictateDb.promptDao();
        usageDao = dictateDb.usageDao();

        // Migrate old int-based provider prefs (0/1/2) to String-based ("OPENAI"/"GROQ"/"CUSTOM")
        PrefsMigration.migrateProviderPrefs(sp);
        aiOrchestrator = new AIOrchestrator(sp, dictateDb.usageDao());
        promptService = PromptService.create(sp);
        autoFormattingService = AutoFormattingService.create(sp, aiOrchestrator);
        sessionManager = new SessionManager(DictateDatabase.getInstance(this));
        sessionTracker = new SessionTracker(sessionManager);
        // Restore lastSessionId synchronously (SharedPrefs only, no DB), then load lastOutput async
        sessionTracker.restoreLastSessionIdFromPrefs(sp);
        dbExecutor.execute(() -> sessionTracker.restoreLastOutputFromDb());

        // Initialize managers
        recordingManager = new RecordingManager(this);
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        bluetoothScoManager = new BluetoothScoManager(this, am, this);
        promptQueueManager = new PromptQueueManager(
                promptDao::getAutoApplyIds,
                sp, this);

        vibrationEnabled = sp.getBoolean("net.devemperor.dictate.vibration", true);
        currentInputLanguagePos = sp.getInt("net.devemperor.dictate.input_language_pos", 0);

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
            () -> { hideQwertzKeyboard(); return kotlin.Unit.INSTANCE; }
        );

        overlayCharactersLl = dictateKeyboardView.findViewById(R.id.overlay_characters_ll);

        // Pipeline cancel button
        pipelineCancelBtn = dictateKeyboardView.findViewById(R.id.pipeline_cancel_btn);

        // KeyboardUiController (wraps pipeline progress views)
        uiController = new KeyboardUiController(new KeyboardUiController.PipelineViews(
            promptsRv,
            dictateKeyboardView.findViewById(R.id.pipeline_progress_ll),
            dictateKeyboardView.findViewById(R.id.pipeline_steps_container),
            dictateKeyboardView.findViewById(R.id.pipeline_scroll_view),
            recordButton,
            infoCl,
            LayoutInflater.from(context),
            mainHandler
        ));

        // InfoBarController
        infoBarController = new InfoBarController(
            infoCl, infoTv, infoYesButton, infoNoButton,
            () -> { openSettingsActivity(); return kotlin.Unit.INSTANCE; },
            intent -> { startActivity(intent); return kotlin.Unit.INSTANCE; },
            sp, getResources(), () -> getTheme()
        );

        // KeyboardStateManager (deterministic visibility calculator)
        stateManager = new KeyboardStateManager(
            new KeyboardViews(mainButtonsCl, editButtonsKeyboardLl, promptsCl, emojiPickerCl,
                qwertzContainer, overlayCharactersLl, pauseButton, trashButton),
            () -> recordingManager.isRecording(),
            () -> recordingManager.isPaused(),
            () -> pipelineOrchestrator.isRunning(),
            () -> sp.getBoolean(Pref.RewordingEnabled.INSTANCE.getKey(), true),
            keepAwake -> { updateKeepScreenAwake(keepAwake); return kotlin.Unit.INSTANCE; },
            infoBarController
        );

        // PipelineOrchestrator
        pipelineOrchestrator = new PipelineOrchestrator(
            aiOrchestrator, autoFormattingService, promptQueueManager,
            promptService, sessionManager, sessionTracker, promptDao, this);

        // History button
        editHistoryButton = dictateKeyboardView.findViewById(R.id.edit_history_btn);



        StaggeredGridLayoutManager promptsLayoutManager =
                new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.HORIZONTAL);
        promptsLayoutManager.setGapStrategy(StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS);
        promptsRv.setLayoutManager(promptsLayoutManager);

        // if user id is not set, set a random number as user id
        if (sp.getString("net.devemperor.dictate.user_id", "null").equals("null")) {
            sp.edit().putString("net.devemperor.dictate.user_id", String.valueOf((int) (Math.random() * 1000000))).apply();
        }

        // initialize audio manager to stop and start background audio
        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(focusChange -> {
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                        if (recordingManager.isRecording()) pauseButton.performClick();
                    }
                })
                .build();
        bluetoothScoManager.registerReceiver();

        stateManager.setSmallMode(sp.getBoolean(Pref.SmallMode.INSTANCE.getKey(), false));

        // MainButtonsController: handles all button registration, overlay init, recording visuals, and theming
        mainButtonsController = new MainButtonsController(
            new MainButtonViews(
                recordButton, resendButton, backspaceButton, trashButton,
                spaceButton, pauseButton, enterButton, editSettingsButton,
                editUndoButton, editRedoButton, editCutButton, editCopyButton,
                editPasteButton, editEmojiButton, editNumbersButton, editKeyboardButton,
                editHistoryButton, emojiPickerCloseButton, emojiPickerView,
                overlayCharactersLl, pipelineCancelBtn, infoYesButton, infoNoButton
            ),
            sp, stateManager, this,
            () -> getCurrentInputConnection(),
            qwertzKeyboardView.getKeyPressAnimator()
        );
        mainButtonsController.registerAllListeners();
        mainButtonsController.initializeKeyPressAnimations();

        return dictateKeyboardView;
    }

    // Auto-stop timeout when recording is paused due to keyboard minimization
    private final Runnable pauseTimeoutRunnable = () -> {
        if (recordingManager.isRecording() && recordingManager.isPaused()) {
            // Auto-stop after timeout: discard recording and reset UI
            recordingManager.release();
            mainButtonsController.cancelPulseAnimation();
            livePrompt = false;
            recordingUsesBluetooth = false;
            updatePromptButtonsEnabledState();
            mainHandler.post(() -> {
                recordButton.setText(getDictateButtonText());
                mainButtonsController.applyRecordingIconState(false);
                recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_mic_20, 0, R.drawable.ic_baseline_folder_open_20, 0);
                recordButton.setEnabled(true);
                stateManager.refresh(); // updates pause/trash visibility + keep-screen-awake
            });
        }
    };

    // method is called if the user closed the keyboard
    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);

        // Hide QWERTZ keyboard when the input view is finishing (app switch, background, etc.)
        hideQwertzKeyboard();

        // State (A): Recording is active (running or paused) -> pause and set timeout
        if (recordingManager.isRecording()) {
            cancelScoWaitIfAny();

            if (!recordingManager.isPaused()) {
                // Pause the recorder (delegates to RecordingManager which fires onRecordingPaused callback)
                recordingManager.pause();

                // Release BT SCO (will be rebuilt on resume)
                boolean useBluetoothMic = sp.getBoolean("net.devemperor.dictate.use_bluetooth_mic", false);
                if (useBluetoothMic && bluetoothScoManager.isScoStarted()) {
                    bluetoothScoManager.release();
                }

                // Release audio focus while paused
                if (audioFocusEnabled) am.abandonAudioFocusRequest(audioFocusRequest);
            }

            // Auto-stop after 60s inactivity (reset if already pending)
            mainHandler.removeCallbacks(pauseTimeoutRunnable);
            mainHandler.postDelayed(pauseTimeoutRunnable, 60_000);

            // Hide content panels but keep recording state (not a state change, just panel cleanup)
            stateManager.setContentArea(ContentArea.MAIN_BUTTONS);
            return;
        }

        // State (B): API request is running -> let it continue, just hide content panels
        if (pipelineOrchestrator.isRunning()) {
            stateManager.setContentArea(ContentArea.MAIN_BUTTONS);
            return;
        }

        // State (C): Idle -> full cleanup (original behavior)
        cancelScoWaitIfAny();
        recordingManager.release();

        pipelineOrchestrator.cancel();
        pendingLivePromptChain = false;

        bluetoothScoManager.unregisterReceiver();

        pauseButton.setForeground(AppCompatResources.getDrawable(this, R.drawable.ic_baseline_pause_24));
        resendButton.setVisibility(View.GONE);
        infoBarController.dismiss();
        stateManager.setContentArea(ContentArea.MAIN_BUTTONS);
        stateManager.refresh(); // updates pause/trash/prompts/keep-screen-awake
        uiController.setMode(KeyboardUiController.PromptAreaMode.PROMPT_BUTTONS);
        livePrompt = false;
        recordingUsesBluetooth = false;
        updatePromptButtonsEnabledState();
        if (audioFocusEnabled) am.abandonAudioFocusRequest(audioFocusRequest);
        recordButton.setText(R.string.dictate_record);
        mainButtonsController.applyRecordingIconState(false);
        recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_mic_20, 0, R.drawable.ic_baseline_folder_open_20, 0);
        recordButton.setEnabled(true);
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(pauseTimeoutRunnable);
        bluetoothScoManager.unregisterReceiver();
        recordingManager.release();
        super.onDestroy();
    }

    // method is called if the keyboard appears again
    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        updateEnterButtonIcon(info);
        bluetoothScoManager.registerReceiver();

        // If recording was paused (by onFinishInputView), restore paused UI
        if (recordingManager.isRecording() && recordingManager.isPaused()) {
            mainHandler.removeCallbacks(pauseTimeoutRunnable);
            pauseButton.setForeground(AppCompatResources.getDrawable(this, R.drawable.ic_baseline_mic_24));
            // pause/trash visibility handled by stateManager.refresh() below
            long elapsedMs = recordingManager.getElapsedTimeMs();
            recordButton.setText(getString(R.string.dictate_send,
                    String.format(Locale.getDefault(), "%02d:%02d", (int) (elapsedMs / 60000), (int) (elapsedMs / 1000) % 60)));
            recordButton.setEnabled(true);
        }

        if (sp.getBoolean("net.devemperor.dictate.rewording_enabled", true)) {
            // collect all prompts from database
            final List<PromptEntity> data = getPromptsForKeyboard();
            InputConnection inputConnection = getCurrentInputConnection();
            boolean hasSelection = inputConnection != null && inputConnection.getSelectedText(0) != null;

            promptsAdapter = new PromptsKeyboardAdapter(sp, data, new PromptsKeyboardAdapter.AdapterCallback() {
                @Override
                public void onItemClicked(Integer position) {
                    vibrate();
                    PromptEntity model = data.get(position);

                    if (model.getId() == -1) {  // instant prompt clicked
                        livePrompt = true;
                        if (ContextCompat.checkSelfPermission(DictateInputMethodService.this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            openSettingsActivity();
                        } else if (!recordingManager.isRecording() && !isPreparingRecording) {
                            startRecording();
                        } else if (recordingManager.isRecording()) {
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
                        if ((recordingManager.isRecording() || isPreparingRecording) && !livePrompt) {
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
                                return;  // nothing to edit
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
                        runStandalonePromptViaOrchestrator(model);  // another normal prompt clicked
                    }
                }

                @Override
                public void onItemLongClicked(Integer position) {
                    PromptEntity longClickModel = data.get(position);
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
            promptsAdapter.setDisableNonSelectionPrompts(disableNonSelectionPrompts);
            promptsAdapter.setSelectAllActive(hasSelection);

            // Restore persisted queue selections (filter out deleted prompts)
            Set<Integer> validIds = new HashSet<>();
            for (PromptEntity p : data) {
                if (p.getId() >= 0) validIds.add(p.getId());
            }
            promptQueueManager.restoreQueue(validIds);

            onQueueChanged(promptQueueManager.getQueuedIds());
            updateSelectAllPromptState();
        }
        // promptsCl visibility is handled by stateManager.refresh() via applySmallMode below

        if (shouldAutomaticallyShowQwertzNumbers(info)) {
            qwertzController.setLayout(QwertzKeyboardLayout.NUMBERS);
            showQwertzKeyboard();
        } else {
            hideQwertzKeyboard();
        }

        // enable resend button if previous audio file still exists in cache
        if (new File(getCacheDir(), sp.getString("net.devemperor.dictate.last_file_name", "audio.m4a")).exists()
                && sp.getBoolean("net.devemperor.dictate.resend_button", false)) {
            resendButton.setVisibility(View.VISIBLE);
        } else {
            resendButton.setVisibility(View.GONE);
        }

        // get the currently selected input language
        recordButton.setText(getDictateButtonText());

        // check if user enabled audio focus
        audioFocusEnabled = sp.getBoolean("net.devemperor.dictate.audio_focus", true);

        // fill all overlay characters
        int accentColor = sp.getInt("net.devemperor.dictate.accent_color", -14700810);
        String charactersString = sp.getString("net.devemperor.dictate.overlay_characters", "()-:!?,.");
        mainButtonsController.updateOverlayCharacters(charactersString, accentColor);

        // update theme
        String theme = sp.getString("net.devemperor.dictate.theme", "system");
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
        qwertzController.applyColors(accentColor, DictateUtils.darkenColor(accentColor, 0.18f), DictateUtils.darkenColor(accentColor, 0.35f));

        // show infos for updates, ratings or donations
        Long totalAudioTimeOrNull = usageDao.getTotalAudioTime();
        long totalAudioTime = totalAudioTimeOrNull != null ? totalAudioTimeOrNull : 0;
        if (sp.getInt("net.devemperor.dictate.last_version_code", 0) < BuildConfig.VERSION_CODE) {
            showInfo("update");
        } else if (totalAudioTime > 180 && totalAudioTime <= 600 && !sp.getBoolean("net.devemperor.dictate.flag_has_rated_in_playstore", false)) {
            showInfo("rate");  // in case someone had Dictate installed before, he shouldn't get both messages
        } else if (totalAudioTime > 600 && !sp.getBoolean("net.devemperor.dictate.flag_has_donated", false)) {
            showInfo("donate");
        }

        // Sync animations preference to QWERTZ keyboard
        qwertzKeyboardView.getKeyPressAnimator().setAnimationsEnabled(
                sp.getBoolean("net.devemperor.dictate.animations", true));

        // Sync small mode from prefs and apply visibility + animation
        stateManager.setSmallMode(sp.getBoolean(Pref.SmallMode.INSTANCE.getKey(), false));
        mainButtonsController.animateSmallModeToggle(false);

        // start audio file transcription if user selected an audio file
        if (!sp.getString("net.devemperor.dictate.transcription_audio_file", "").isEmpty()) {
            audioFile = new File(getCacheDir(), sp.getString("net.devemperor.dictate.transcription_audio_file", ""));
            sp.edit().putString("net.devemperor.dictate.last_file_name", audioFile.getName()).apply();

            sp.edit().remove("net.devemperor.dictate.transcription_audio_file").apply();
            runTranscriptionViaOrchestrator();

        } else if (sp.getBoolean("net.devemperor.dictate.instant_recording", false)) {
            recordButton.performClick();
        }
    }

    // method is called if user changed text selection
    @Override
    public void onUpdateSelection (int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd, int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);

        // refill all prompts
        if (sp != null && sp.getBoolean("net.devemperor.dictate.rewording_enabled", true)) {
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
        // Restore recording indicator to normal prompt buttons if it was showing
        if (uiController != null && uiController.getCurrentMode() == KeyboardUiController.PromptAreaMode.RECORDING_INDICATOR) {
            uiController.setMode(KeyboardUiController.PromptAreaMode.PROMPT_BUTTONS);
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
        if (recordingManager.isRecording() || isPreparingRecording) return;  // prevent re-entrance

        promptQueueManager.prepareAutoApplyQueue();

        audioFile = new File(getCacheDir(), "audio.m4a");
        sp.edit().putString("net.devemperor.dictate.last_file_name", audioFile.getName()).apply();

        boolean useBluetoothMic = sp.getBoolean("net.devemperor.dictate.use_bluetooth_mic", false);
        boolean btAvailable = bluetoothScoManager.isBluetoothAvailable(useBluetoothMic);

        if (btAvailable) {
            // Prepare to wait for SCO connection before starting the recorder
            isPreparingRecording = true;
            recordingPending = true;
            updatePromptButtonsEnabledState();
            mainHandler.post(() -> recordButton.setEnabled(false));

            // startSco will call onScoConnected (immediate) or onScoFailed (timeout)
            // onScoConnected/onScoFailed then call proceedStartRecording
            bluetoothScoManager.startSco(2500);
        } else {
            proceedStartRecording(MediaRecorder.AudioSource.MIC, false);  // Start immediately with local MIC
        }
    }

    private void proceedStartRecording(int audioSource, boolean useBtForThisRecording) {
        recordingUsesBluetooth = useBtForThisRecording;

        if (audioFocusEnabled) am.requestAudioFocus(audioFocusRequest);

        boolean started = recordingManager.start(audioFile, audioSource);
        if (!started) {
            // reset UI/state on failure
            isPreparingRecording = false;
            recordingPending = false;
            recordingUsesBluetooth = false;
            updatePromptButtonsEnabledState();
            if (audioFocusEnabled) am.abandonAudioFocusRequest(audioFocusRequest);
            mainHandler.post(() -> {
                recordButton.setText(getDictateButtonText());
                mainButtonsController.applyRecordingIconState(false);
                recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_mic_20, 0, R.drawable.ic_baseline_folder_open_20, 0);
                recordButton.setEnabled(true);
            });
        }
        // On success, RecordingManager fires onRecordingStarted callback which updates UI
    }

    private void stopRecording() {
        cancelScoWaitIfAny();
        recordingManager.stop();
        updateKeepScreenAwake(false);
        bluetoothScoManager.release();
        runTranscriptionViaOrchestrator();
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
        mainButtonsController.applyRecordingIconState(false);  // recording finished -> stop pulsing

        recordButton.setText(R.string.dictate_sending);
        recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_send_20, 0, 0, 0);
        recordButton.setEnabled(false);
        pauseButton.setForeground(AppCompatResources.getDrawable(this, R.drawable.ic_baseline_pause_24));
        resendButton.setVisibility(View.GONE);
        infoBarController.dismiss();
        recordingUsesBluetooth = false;
        updatePromptButtonsEnabledState();
        stateManager.refresh(); // updates pause/trash/prompts visibility

        // Show pipeline progress
        int totalSteps = 1; // transcription always
        if (autoFormattingService.isEnabled()) totalSteps++;
        totalSteps += promptQueueManager.getQueuedIds().size();
        uiController.showPipelineProgress(totalSteps);

        if (audioFocusEnabled) am.abandonAudioFocusRequest(audioFocusRequest);

        String language = currentInputLanguageValue != null && !currentInputLanguageValue.equals("detect")
                ? currentInputLanguageValue : null;
        String stylePrompt = promptService.resolveWhisperStylePrompt(currentInputLanguageValue);

        EditorInfo info = getCurrentInputEditorInfo();
        boolean showResend = new File(getCacheDir(), sp.getString("net.devemperor.dictate.last_file_name", "audio.m4a")).exists()
                && sp.getBoolean("net.devemperor.dictate.resend_button", false);

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

        // Set UI mode BEFORE calling orchestrator
        String displayName = model.getId() == -1 ? getString(R.string.dictate_live_prompt) : model.getName();
        if (uiController.getCurrentMode() != KeyboardUiController.PromptAreaMode.PIPELINE_PROGRESS) {
            uiController.showPipelineProgress(1);
        }

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
            if (uiController.getCurrentMode() == KeyboardUiController.PromptAreaMode.PIPELINE_PROGRESS) {
                uiController.addRunningStep(stepName);
            }
        });
    }

    @Override
    public void onStepCompleted(@androidx.annotation.NonNull String stepName, long durationMs) {
        mainHandler.post(() -> {
            if (uiController.getCurrentMode() == KeyboardUiController.PromptAreaMode.PIPELINE_PROGRESS) {
                uiController.completeStep(stepName, durationMs);
            }
        });
    }

    @Override
    public void onStepFailed(@androidx.annotation.NonNull String stepName) {
        mainHandler.post(() -> {
            if (uiController.getCurrentMode() == KeyboardUiController.PromptAreaMode.PIPELINE_PROGRESS) {
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
                PromptEntity liveEntity = new PromptEntity(-1, Integer.MIN_VALUE, "", text, true, false);
                runStandalonePromptViaOrchestrator(liveEntity);
            } else {
                commitTextToInputConnection(text, source);
            }
        });
    }

    @Override
    public void onPipelineError(@androidx.annotation.NonNull String errorInfoKey, boolean vibrate) {
        mainHandler.post(() -> showInfo(errorInfoKey));
        if (vibrate && vibrationEnabled) {
            vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    @Override
    public void onShowResend() {
        mainHandler.post(() -> resendButton.setVisibility(View.VISIBLE));
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

        dbExecutor.execute(() -> {
            sessionTracker.resetSession();
            sessionTracker.persistToPrefs(sp);
            mainHandler.post(() -> {
                uiController.setMode(KeyboardUiController.PromptAreaMode.PROMPT_BUTTONS);
                mainButtonsController.applyRecordingIconState(false);
                uiController.restoreRecordButtonIdle(
                    getDictateButtonText(),
                    R.drawable.ic_baseline_mic_20,
                    R.drawable.ic_baseline_folder_open_20);
            });
        });
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
        if (sp.getBoolean("net.devemperor.dictate.instant_output", true)) {
            inputConnection.commitText(output, 1);
            if (sp.getBoolean("net.devemperor.dictate.auto_enter", false)) {
                performEnterAction();
            }
        } else if (mainHandler != null) {
            int speed = sp.getInt("net.devemperor.dictate.output_speed", 5);
            for (int i = 0; i < output.length(); i++) {
                char character = output.charAt(i);
                String characterString = String.valueOf(character);
                long delay = (long) (i * (20L / (speed / 5f)));
                boolean isLastChar = i == output.length() - 1;
                mainHandler.postDelayed(() -> {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) {
                        ic.commitText(characterString, 1);
                        if (isLastChar && sp.getBoolean("net.devemperor.dictate.auto_enter", false)) {
                            performEnterAction();
                        }
                    }
                }, delay);
            }
        } else {
            inputConnection.commitText(output, 1);
            if (sp.getBoolean("net.devemperor.dictate.auto_enter", false)) {
                performEnterAction();
            }
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
    private List<PromptEntity> getPromptsForKeyboard() {
        List<PromptEntity> dbPrompts = promptDao.getAll();
        List<PromptEntity> result = new ArrayList<>(dbPrompts.size() + 4);
        result.add(new PromptEntity(-1, Integer.MIN_VALUE, null, null, false, false));      // instant prompt
        result.add(new PromptEntity(-3, Integer.MIN_VALUE + 1, null, null, false, false));  // select all
        result.add(new PromptEntity(-4, Integer.MIN_VALUE + 2, null, null, false, false));  // clear queue
        result.addAll(dbPrompts);
        result.add(new PromptEntity(-2, Integer.MAX_VALUE, null, null, false, false));       // add button
        return result;
    }

    private void updatePromptButtonsEnabledState() {
        disableNonSelectionPrompts = recordingManager.isRecording() || isPreparingRecording;
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

    private String getDictateButtonText() {
        List<String> allLanguagesValues = Arrays.asList(getResources().getStringArray(R.array.dictate_input_languages_values));
        List<String> recordDifferentLanguages = Arrays.asList(getResources().getStringArray(R.array.dictate_record_different_languages));

        LinkedHashSet<String> defaultLanguages = new LinkedHashSet<>(Arrays.asList(getResources().getStringArray(R.array.dictate_default_input_languages)));
        Set<String> storedLanguages = sp.getStringSet("net.devemperor.dictate.input_languages", defaultLanguages);
        LinkedHashSet<String> sanitizedLanguages = new LinkedHashSet<>();
        for (String language : storedLanguages) {
            if (allLanguagesValues.contains(language)) sanitizedLanguages.add(language);
        }
        if (sanitizedLanguages.isEmpty()) sanitizedLanguages.addAll(defaultLanguages);
        if (!sanitizedLanguages.equals(storedLanguages)) {
            sp.edit().putStringSet("net.devemperor.dictate.input_languages", sanitizedLanguages).apply();
        }

        List<String> languagesList = new ArrayList<>(sanitizedLanguages);
        if (currentInputLanguagePos >= languagesList.size()) currentInputLanguagePos = 0;
        sp.edit().putInt("net.devemperor.dictate.input_language_pos", currentInputLanguagePos).apply();

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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            openSettingsActivity();
        } else if (!recordingManager.isRecording() && !isPreparingRecording) {
            startRecording();
        } else if (recordingManager.isRecording()) {
            stopRecording();
        }
    }

    @Override
    public void onRecordLongClicked() {
        if (!recordingManager.isRecording() && !isPreparingRecording) {
            Intent intent = new Intent(this, DictateSettingsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra("net.devemperor.dictate.open_file_picker", true);
            startActivity(intent);
        } else if (!livePrompt && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
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
        if (audioFile == null) audioFile = new File(getCacheDir(), sp.getString("net.devemperor.dictate.last_file_name", "audio.m4a"));
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
        cancelScoWaitIfAny();
        recordingManager.release();
        recordingUsesBluetooth = false;
        bluetoothScoManager.release();

        if (audioFocusEnabled) am.abandonAudioFocusRequest(audioFocusRequest);

        livePrompt = false;
        updatePromptButtonsEnabledState();
        recordButton.setText(getDictateButtonText());
        mainButtonsController.applyRecordingIconState(false);
        recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_mic_20, 0, R.drawable.ic_baseline_folder_open_20, 0);
        recordButton.setEnabled(true);
        pauseButton.setForeground(AppCompatResources.getDrawable(this, R.drawable.ic_baseline_pause_24));
        stateManager.refresh();

        if (new File(getCacheDir(), sp.getString("net.devemperor.dictate.last_file_name", "audio.m4a")).exists()
                && sp.getBoolean("net.devemperor.dictate.resend_button", false)) {
            resendButton.setVisibility(View.VISIBLE);
        }

        if (uiController.getCurrentMode() == KeyboardUiController.PromptAreaMode.RECORDING_INDICATOR) {
            uiController.setMode(KeyboardUiController.PromptAreaMode.PROMPT_BUTTONS);
        }
    }

    @Override
    public void onPauseClicked() {
        if (recordingManager.isPaused()) {
            if (audioFocusEnabled) am.requestAudioFocus(audioFocusRequest);
            recordingManager.resume();
        } else {
            if (audioFocusEnabled) am.abandonAudioFocusRequest(audioFocusRequest);
            recordingManager.pause();
        }
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
        if (recordingManager.isRecording()) onTrashClicked();
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

        uiController.setMode(KeyboardUiController.PromptAreaMode.PROMPT_BUTTONS);
        mainButtonsController.applyRecordingIconState(false);
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
        sp.edit().putBoolean(Pref.SmallMode.INSTANCE.getKey(), newSmallMode).apply();
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

    private void cancelScoWaitIfAny() {
        recordingPending = false;
        isPreparingRecording = false;
        // BluetoothScoManager handles its own waiting state via release()
        updatePromptButtonsEnabledState();
    }
}
