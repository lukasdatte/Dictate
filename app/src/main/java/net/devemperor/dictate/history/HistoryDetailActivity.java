package net.devemperor.dictate.history;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import net.devemperor.dictate.R;
import net.devemperor.dictate.ai.AIOrchestrator;
import net.devemperor.dictate.ai.runner.CompletionResult;
import net.devemperor.dictate.ai.AIFunction;
import net.devemperor.dictate.core.ActiveJobRegistry;
import net.devemperor.dictate.core.ActiveJobRegistryObserver;
import net.devemperor.dictate.core.JobExecutor;
import net.devemperor.dictate.core.JobRequest;
import net.devemperor.dictate.core.RecordingRepository;
import net.devemperor.dictate.core.SessionManager;
import net.devemperor.dictate.database.DictateDatabase;
import net.devemperor.dictate.database.dao.ProcessingStepDao;
import net.devemperor.dictate.database.dao.TranscriptionDao;
import net.devemperor.dictate.database.entity.ProcessingStepEntity;
import net.devemperor.dictate.database.entity.SessionEntity;
import net.devemperor.dictate.database.entity.SessionOrigin;
import net.devemperor.dictate.database.entity.SessionStatus;
import net.devemperor.dictate.database.entity.SessionType;
import net.devemperor.dictate.database.entity.StepStatus;
import net.devemperor.dictate.database.entity.StepType;
import net.devemperor.dictate.database.entity.TranscriptionEntity;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HistoryDetailActivity extends AppCompatActivity
        implements PromptChooserBottomSheet.OnPromptChosenListener {

    private static final String TAG_REGENERATE = "regenerate";
    private static final String TAG_POST_PROCESS = "post_process";
    private static final String TAG_REPROCESS_EDIT_PREFIX = "history_reprocess_edit:";

    private enum UiState { IDLE, LOADING, ERROR }

    private DictateDatabase db;
    private SessionManager sessionManager;
    private AIOrchestrator orchestrator;
    private ProcessingStepDao stepDao;
    private TranscriptionDao transcriptionDao;
    private RecordingRepository recordingRepository;

    private String sessionId;
    private SessionEntity session;

    // Pending prompt chooser context (survives only within same Activity instance,
    // but that's fine — the BottomSheet listener survives config changes via onAttach)
    private ProcessingStepEntity pendingStep;
    private int pendingChainIndex;
    private String pendingPostProcessOutputText;
    private String pendingPostProcessNewSessionId;

    private List<PipelineStepAdapter.PipelineStep> pipelineSteps;
    private PipelineStepAdapter pipelineAdapter;
    private ProgressBar progressBar;

    private MediaPlayer mediaPlayer;
    private final ExecutorService regenerateExecutor = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_history_detail);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.activity_history_detail), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.dictate_history_detail);
        }

        sessionId = getIntent().getStringExtra(HistoryActivity.EXTRA_SESSION_ID);
        if (sessionId == null) {
            finish();
            return;
        }

        db = DictateDatabase.getInstance(this);
        sessionManager = new SessionManager(db);
        stepDao = db.processingStepDao();
        transcriptionDao = db.transcriptionDao();
        recordingRepository = new RecordingRepository(this);

        SharedPreferences sp = getSharedPreferences("net.devemperor.dictate", MODE_PRIVATE);
        orchestrator = new AIOrchestrator(sp, db.usageDao());

        progressBar = findViewById(R.id.history_detail_progress_bar);

        pipelineSteps = new ArrayList<>();
        pipelineAdapter = new PipelineStepAdapter(pipelineSteps, new PipelineStepAdapter.StepActionCallback() {
            @Override
            public void onPlayAudio(String audioFilePath) {
                playAudio(audioFilePath);
            }

            @Override
            public void onRegenerate(ProcessingStepEntity step, int chainIndex) {
                regenerateStep(step, chainIndex, step.getPromptUsed(), step.getPromptEntityId());
            }

            @Override
            public void onOtherPrompt(ProcessingStepEntity step, int chainIndex) {
                showPromptChooser(step, chainIndex);
            }

            @Override
            public void onPostProcess(ProcessingStepEntity step) {
                createPostProcessingSession(step);
            }

            @Override
            public void onVersionSelected(int chainIndex, ProcessingStepEntity selectedVersion) {
                switchVersion(chainIndex, selectedVersion);
            }

            @Override
            public void onOpenSourceSession(String sourceSessionId) {
                Intent intent = new Intent(HistoryDetailActivity.this, HistoryDetailActivity.class);
                intent.putExtra(HistoryActivity.EXTRA_SESSION_ID, sourceSessionId);
                startActivity(intent);
            }

            @Override
            public void onDirectReprocess(String sessionId) {
                startHistoryReprocess(sessionId, /* editedQueue */ null);
            }

            @Override
            public void onReprocessWithEdit(String sessionId) {
                // Plan 10.6 calls for a full drag-to-reorder queue-editor
                // (PromptChooserBottomSheetV2). That UI is tracked as a
                // follow-up. Until it ships, we reuse the existing V1 chooser
                // as a minimal queue-editor: the user picks a single prompt
                // and that prompt becomes the one-element edited queue.
                //
                // This differs from onDirectReprocess (above) which reuses the
                // session's historical queue unchanged — so the two buttons
                // are no longer identical.
                PromptChooserBottomSheet
                        .newInstance(TAG_REPROCESS_EDIT_PREFIX + sessionId)
                        .show(getSupportFragmentManager(), "prompt_chooser_reprocess");
            }

            @Override
            public void onDeleteAudio(String sessionId) {
                confirmDeleteAudio(sessionId);
            }
        });

        RecyclerView pipelineRv = findViewById(R.id.history_detail_pipeline_rv);
        pipelineRv.setLayoutManager(new LinearLayoutManager(this));
        pipelineRv.setAdapter(pipelineAdapter);

        // Copy button
        MaterialButton copyBtn = findViewById(R.id.history_detail_copy_btn);
        copyBtn.setOnClickListener(v -> copyFinalOutput());

        // Share button
        MaterialButton shareBtn = findViewById(R.id.history_detail_share_btn);
        shareBtn.setOnClickListener(v -> shareFinalOutput());

        loadSession();

        // K3: reactive UI. When a job starts/stops for this session, reload
        // so the running-badge + disabled-reprocess-button logic picks it up
        // immediately — no more `onResume`-only refresh. The observer is
        // lifecycle-scoped, so it idles while the Activity is stopped and
        // resumes on return with the latest snapshot.
        ActiveJobRegistryObserver.observe(this, snapshot -> {
            if (sessionId != null) loadSession();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-load in case the DB changed while we were paused (registry
        // observer handles live updates; this catches external changes).
        loadSession();
    }

    @SuppressLint("NotifyDataSetChanged")
    private void loadSession() {
        session = db.sessionDao().getById(sessionId);
        if (session == null) {
            finish();
            return;
        }

        // Header
        TextView headerTv = findViewById(R.id.history_detail_header_tv);
        String typeName;
        try {
            SessionType type = SessionType.valueOf(session.getType());
            switch (type) {
                case REWORDING: typeName = getString(R.string.dictate_history_rewording); break;
                case POST_PROCESSING: typeName = getString(R.string.dictate_history_queued_prompt); break;
                default: typeName = getString(R.string.dictate_history_audio); break;
            }
        } catch (IllegalArgumentException e) {
            typeName = session.getType();
        }
        headerTv.setText(typeName + " \u2014 " + dateFormat.format(new Date(session.getCreatedAt())));

        buildPipeline();
        pipelineAdapter.notifyDataSetChanged();
    }

    private void buildPipeline() {
        pipelineSteps.clear();
        SessionType type;
        try {
            type = SessionType.valueOf(session.getType());
        } catch (IllegalArgumentException e) {
            return;
        }

        switch (type) {
            case RECORDING:
                buildRecordingPipeline();
                break;
            case REWORDING:
                buildRewordingPipeline();
                break;
            case POST_PROCESSING:
                buildPostProcessingPipeline();
                break;
        }

        // Final output step
        String finalOutput = sessionManager.getFinalOutput(sessionId);
        if (finalOutput != null && !finalOutput.isEmpty()) {
            pipelineSteps.add(new PipelineStepAdapter.PipelineStep.Builder(PipelineStepAdapter.PipelineStep.Type.FINAL_OUTPUT)
                    .icon("\uD83D\uDCCB") // clipboard
                    .title(getString(R.string.dictate_history_final_output))
                    .outputText(finalOutput)
                    .build());
        }
    }

    private void buildRecordingPipeline() {
        // Audio step — with reprocess actions (Phase 10.4)
        long dur = session.getAudioDurationSeconds();
        String durationStr = getString(R.string.dictate_history_duration, dur / 60, dur % 60);
        boolean audioAvailable = resolveAudioAvailability();
        boolean jobActive = ActiveJobRegistry.INSTANCE.isActive(sessionId);

        SessionStatus status;
        try {
            status = SessionStatus.valueOf(session.getStatus());
        } catch (IllegalArgumentException e) {
            status = SessionStatus.RECORDED;
        }
        boolean canReprocess = audioAvailable && !jobActive && (
                status == SessionStatus.RECORDED
                || status == SessionStatus.FAILED
                || status == SessionStatus.CANCELLED
                || status == SessionStatus.COMPLETED
        );
        boolean showDirect = canReprocess && status != SessionStatus.COMPLETED;
        boolean showEdit = canReprocess;
        boolean showDelete = audioAvailable && !jobActive;

        pipelineSteps.add(new PipelineStepAdapter.PipelineStep.Builder(PipelineStepAdapter.PipelineStep.Type.AUDIO)
                .icon("\uD83C\uDFA4") // mic
                .title(getString(R.string.dictate_history_audio) + " (" + durationStr + ")")
                .audioFilePath(audioAvailable ? session.getAudioFilePath() : null)
                .sessionId(sessionId)
                .showDirectReprocess(showDirect)
                .showReprocessWithEdit(showEdit)
                .showDeleteAudio(showDelete)
                .build());

        // Transcription step
        TranscriptionEntity currentTranscription = transcriptionDao.getCurrent(sessionId);
        if (currentTranscription != null) {
            String meta = currentTranscription.getModelUsed() + " \u2022 " +
                    String.format(Locale.getDefault(), "%.1fs", currentTranscription.getDurationMs() / 1000.0);
            String title = getString(R.string.dictate_history_transcription) +
                    " v" + currentTranscription.getVersion();
            pipelineSteps.add(new PipelineStepAdapter.PipelineStep.Builder(PipelineStepAdapter.PipelineStep.Type.TRANSCRIPTION)
                    .icon("\uD83D\uDCDD") // memo
                    .title(title)
                    .outputText(currentTranscription.getText())
                    .metaText(meta)
                    .build());
        }

        // Processing steps
        addProcessingSteps();
    }

    private boolean resolveAudioAvailability() {
        String path = session.getAudioFilePath();
        if (path == null) return false;
        return new File(path).exists();
    }

    private void buildRewordingPipeline() {
        // Input step
        String inputText = session.getInputText();
        pipelineSteps.add(new PipelineStepAdapter.PipelineStep.Builder(PipelineStepAdapter.PipelineStep.Type.INPUT)
                .icon("\uD83D\uDCE5") // inbox tray
                .title(getString(R.string.dictate_history_input))
                .outputText(inputText != null ? inputText : "")
                .build());

        // Processing steps
        addProcessingSteps();
    }

    private void buildPostProcessingPipeline() {
        // Source session link
        if (session.getParentSessionId() != null) {
            SessionEntity parentSession = db.sessionDao().getById(session.getParentSessionId());
            String parentInfo = getString(R.string.dictate_history_source_session);
            if (parentSession != null) {
                parentInfo += " (" + dateFormat.format(new Date(parentSession.getCreatedAt())) + ")";
            }
            pipelineSteps.add(new PipelineStepAdapter.PipelineStep.Builder(PipelineStepAdapter.PipelineStep.Type.SOURCE_SESSION)
                    .icon("\uD83D\uDD17") // link
                    .title(parentInfo)
                    .sourceSessionId(session.getParentSessionId())
                    .build());
        }

        // Processing steps
        addProcessingSteps();
    }

    private void addProcessingSteps() {
        List<ProcessingStepEntity> currentChain = stepDao.getCurrentChain(sessionId);
        for (int i = 0; i < currentChain.size(); i++) {
            ProcessingStepEntity step = currentChain.get(i);
            List<ProcessingStepEntity> versions = stepDao.getVersionsAtIndex(sessionId, step.getChainIndex());

            String icon;
            String typeName;
            try {
                StepType stepType = StepType.valueOf(step.getStepType());
                switch (stepType) {
                    case AUTO_FORMAT:
                        icon = "\u2728"; // sparkles
                        typeName = getString(R.string.dictate_history_auto_format);
                        break;
                    case REWORDING:
                        icon = "\u270F\uFE0F"; // pencil
                        typeName = getString(R.string.dictate_history_rewording);
                        break;
                    case QUEUED_PROMPT:
                        icon = "\uD83D\uDD04"; // arrows
                        typeName = getString(R.string.dictate_history_queued_prompt);
                        break;
                    default:
                        icon = "\u2753";
                        typeName = step.getStepType();
                        break;
                }
            } catch (IllegalArgumentException e) {
                icon = "\u2753";
                typeName = step.getStepType();
            }

            String title = typeName + " v" + step.getVersion();
            String meta = step.getModelUsed() + " \u2022 " +
                    String.format(Locale.getDefault(), "%.1fs", step.getDurationMs() / 1000.0);

            String errorText = null;
            if (StepStatus.ERROR.name().equals(step.getStatus()) && step.getErrorMessage() != null) {
                errorText = getString(R.string.dictate_history_error, step.getErrorMessage());
            }

            boolean isLastStep = (i == currentChain.size() - 1);

            pipelineSteps.add(new PipelineStepAdapter.PipelineStep.Builder(PipelineStepAdapter.PipelineStep.Type.PROCESSING)
                    .icon(icon)
                    .title(title)
                    .outputText(step.getOutputText())
                    .errorText(errorText)
                    .metaText(meta)
                    .stepEntity(step)
                    .versions(versions)
                    .chainIndex(step.getChainIndex())
                    .showRegenerate(true)
                    .showOtherPrompt(true)
                    .showPostProcess(isLastStep)
                    .build());
        }
    }

    // region Audio playback

    private void playAudio(String audioFilePath) {
        try {
            if (mediaPlayer != null) {
                mediaPlayer.release();
            }
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(audioFilePath);
            mediaPlayer.prepare();
            mediaPlayer.start();
            mediaPlayer.setOnCompletionListener(mp -> {
                // Reset play button state if needed
            });
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.dictate_history_error, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    // endregion

    // region Reprocess actions (Phase 10.3)

    private void startHistoryReprocess(String targetSessionId, List<Integer> editedQueue) {
        if (ActiveJobRegistry.INSTANCE.isAnyActive()) {
            Toast.makeText(this, R.string.dictate_job_already_active, Toast.LENGTH_SHORT).show();
            return;
        }

        SessionEntity target = db.sessionDao().getById(targetSessionId);
        if (target == null) return;

        String audioPath = target.getAudioFilePath();
        if (audioPath == null || !new File(audioPath).exists()) {
            Toast.makeText(this, R.string.dictate_audio_file_missing, Toast.LENGTH_SHORT).show();
            return;
        }

        List<Integer> queue = editedQueue != null
                ? editedQueue
                : sessionManager.getHistoricalQueuedPromptIds(targetSessionId);

        int totalSteps = 1; // transcription
        totalSteps += queue.size();

        JobRequest.TranscriptionPipeline request = new JobRequest.TranscriptionPipeline(
                targetSessionId,
                totalSteps,
                JobRequest.TranscriptionKind.HISTORY_REPROCESS,
                /* audioFilePath */ audioPath,
                /* language */ target.getLanguage(),
                /* modelOverride */ null,
                /* queuedPromptIds */ queue,
                /* targetAppPackage */ target.getTargetAppPackage(),
                /* recordingsDir */ new File(audioPath).getParentFile() != null
                        ? new File(audioPath).getParentFile()
                        : getFilesDir(),
                /* reuseSessionId */ targetSessionId,
                /* stylePrompt */ null,
                /* origin */ SessionOrigin.HISTORY_REPROCESS
        );

        boolean started = JobExecutor.INSTANCE.start(this, request);
        if (!started) {
            Toast.makeText(this, R.string.dictate_job_already_active, Toast.LENGTH_SHORT).show();
            return;
        }
        setUiState(UiState.LOADING);
    }

    private void confirmDeleteAudio(String targetSessionId) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dictate_delete_audio_title)
                .setMessage(R.string.dictate_delete_audio_message)
                .setPositiveButton(R.string.dictate_delete_audio_confirm, (dialog, which) -> {
                    regenerateExecutor.execute(() -> {
                        recordingRepository.deleteBySessionId(targetSessionId);
                        if (!isFinishing()) runOnUiThread(this::loadSession);
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // endregion

    // region Regeneration

    private void regenerateStep(ProcessingStepEntity step, int chainIndex, String promptText, Integer promptEntityId) {
        setUiState(UiState.LOADING);
        regenerateExecutor.execute(() -> {
            try {
                long startTime = System.currentTimeMillis();
                CompletionResult result = orchestrator.complete(step.getInputText(), promptText);
                long durationMs = System.currentTimeMillis() - startTime;

                String provider = orchestrator.getProvider(AIFunction.COMPLETION).name();
                String model = result.getModelName();

                StepType stepType;
                try {
                    stepType = StepType.valueOf(step.getStepType());
                } catch (IllegalArgumentException e) {
                    stepType = StepType.QUEUED_PROMPT;
                }

                sessionManager.regenerateProcessingStep(
                        sessionId, chainIndex, stepType,
                        step.getInputText(), result.getText(),
                        model, provider,
                        promptText, promptEntityId,
                        step.getPreviousStepId(), step.getPreviousTranscriptionId(),
                        step.getSourceSessionId(),
                        result.getPromptTokens(), result.getCompletionTokens(),
                        durationMs,
                        StepStatus.SUCCESS, null
                );
                // Use getFinalOutput() to correctly resolve the chain's last current step,
                // which may differ from result.getText() when regenerating a mid-chain step.
                sessionManager.updateFinalOutputText(sessionId, sessionManager.getFinalOutput(sessionId));

                if (!isFinishing()) {
                    runOnUiThread(() -> {
                        setUiState(UiState.IDLE);
                        loadSession();
                    });
                }
            } catch (Exception e) {
                if (!isFinishing()) {
                    runOnUiThread(() -> {
                        setUiState(UiState.ERROR);
                        Toast.makeText(this, getString(R.string.dictate_history_regenerate_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                    });
                }
            }
        });
    }

    private void showPromptChooser(ProcessingStepEntity step, int chainIndex) {
        pendingStep = step;
        pendingChainIndex = chainIndex;
        PromptChooserBottomSheet.newInstance(TAG_REGENERATE)
                .show(getSupportFragmentManager(), "prompt_chooser");
    }

    private void createPostProcessingSession(ProcessingStepEntity step) {
        String outputText = step.getOutputText();
        if (outputText == null || outputText.isEmpty()) return;

        String newSessionId = UUID.randomUUID().toString();
        sessionManager.createSession(
                newSessionId,
                SessionType.POST_PROCESSING,
                session.getTargetAppPackage(),
                session.getLanguage(),
                /* audioFilePath */ null,
                /* audioDurationSeconds */ 0L,
                /* parentId */ sessionId,
                SessionOrigin.POST_PROCESSING,
                /* queuedPromptIds */ null,
                SessionStatus.RECORDED
        );
        sessionManager.updateInputText(newSessionId, outputText);

        pendingPostProcessOutputText = outputText;
        pendingPostProcessNewSessionId = newSessionId;
        PromptChooserBottomSheet.newInstance(TAG_POST_PROCESS)
                .show(getSupportFragmentManager(), "prompt_chooser_post");
    }

    @Override
    public void onPromptChosen(String tag, String promptText, Integer promptEntityId) {
        if (TAG_REGENERATE.equals(tag) && pendingStep != null) {
            regenerateStep(pendingStep, pendingChainIndex, promptText, promptEntityId);
        } else if (TAG_POST_PROCESS.equals(tag) && pendingPostProcessNewSessionId != null) {
            runPostProcessing(pendingPostProcessNewSessionId, pendingPostProcessOutputText, promptText, promptEntityId);
        } else if (tag != null && tag.startsWith(TAG_REPROCESS_EDIT_PREFIX)) {
            // K2 minimal-fallback: V1 chooser feeds a single-prompt queue into
            // the reprocess pipeline. Free-text prompts (promptEntityId == null)
            // are skipped because JobRequest's queuedPromptIds carries entity
            // IDs only — the V2 editor will supersede this path.
            String targetSessionId = tag.substring(TAG_REPROCESS_EDIT_PREFIX.length());
            if (promptEntityId != null) {
                startHistoryReprocess(targetSessionId,
                        java.util.Collections.singletonList(promptEntityId));
            } else {
                Toast.makeText(this,
                        getString(R.string.dictate_history_reprocess_edit_needs_saved_prompt),
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void runPostProcessing(String newSessionId, String outputText, String promptText, Integer promptEntityId) {
        setUiState(UiState.LOADING);
        regenerateExecutor.execute(() -> {
            try {
                long startTime = System.currentTimeMillis();
                CompletionResult result = orchestrator.complete(outputText, promptText);
                long durationMs = System.currentTimeMillis() - startTime;
                String provider = orchestrator.getProvider(AIFunction.COMPLETION).name();

                sessionManager.appendProcessingStep(
                        newSessionId, StepType.QUEUED_PROMPT,
                        outputText, result.getText(),
                        result.getModelName(), provider,
                        promptText, promptEntityId,
                        null, null, sessionId,
                        result.getPromptTokens(), result.getCompletionTokens(),
                        durationMs,
                        StepStatus.SUCCESS, null
                );
                sessionManager.updateFinalOutputText(newSessionId, result.getText());
                sessionManager.finalizeCompleted(newSessionId);

                if (!isFinishing()) {
                    runOnUiThread(() -> {
                        setUiState(UiState.IDLE);
                        Intent intent = new Intent(HistoryDetailActivity.this, HistoryDetailActivity.class);
                        intent.putExtra(HistoryActivity.EXTRA_SESSION_ID, newSessionId);
                        startActivity(intent);
                    });
                }
            } catch (Exception e) {
                if (!isFinishing()) {
                    runOnUiThread(() -> {
                        setUiState(UiState.ERROR);
                        Toast.makeText(this, getString(R.string.dictate_history_regenerate_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                    });
                }
            }
        });
    }

    @SuppressLint("NotifyDataSetChanged")
    private void switchVersion(int chainIndex, ProcessingStepEntity selectedVersion) {
        db.runInTransaction(() -> {
            stepDao.clearCurrentAtIndex(sessionId, chainIndex);
            stepDao.setCurrentById(selectedVersion.getId());
        });
        // Update final output text
        String finalOutput = sessionManager.getFinalOutput(sessionId);
        sessionManager.updateFinalOutputText(sessionId, finalOutput);
        loadSession();
    }

    // endregion

    // region Copy & Share

    private void copyFinalOutput() {
        SessionManager.FinalOutputInfo outputInfo = sessionManager.getFinalOutputSource(sessionId);
        if (outputInfo == null) return;

        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Dictate", outputInfo.getText()));
        Toast.makeText(this, R.string.dictate_copied_to_clipboard, Toast.LENGTH_SHORT).show();

        // Log clipboard copy
        sessionManager.logPasteFromHistory(
                sessionId,
                outputInfo.getStepId(),
                outputInfo.getTranscriptionId(),
                outputInfo.getText()
        );
    }

    private void shareFinalOutput() {
        String outputText = sessionManager.getFinalOutput(sessionId);
        if (outputText == null || outputText.isEmpty()) return;

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, outputText);
        startActivity(Intent.createChooser(shareIntent, null));
    }

    // endregion

    // region UI State

    private void setUiState(UiState state) {
        switch (state) {
            case LOADING:
                progressBar.setVisibility(View.VISIBLE);
                break;
            case IDLE:
            case ERROR:
                progressBar.setVisibility(View.GONE);
                break;
        }
    }

    // endregion

    @Override
    protected void onDestroy() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        regenerateExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
