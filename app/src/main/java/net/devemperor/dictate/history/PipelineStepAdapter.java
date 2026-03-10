package net.devemperor.dictate.history;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import net.devemperor.dictate.R;
import net.devemperor.dictate.database.entity.ProcessingStepEntity;
import net.devemperor.dictate.database.entity.StepStatus;
import net.devemperor.dictate.database.entity.StepType;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Adapter for displaying pipeline steps in the detail view.
 * Each item represents one step in the processing pipeline.
 */
public class PipelineStepAdapter extends RecyclerView.Adapter<PipelineStepAdapter.ViewHolder> {

    private final List<PipelineStep> steps;
    private final StepActionCallback callback;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

    /**
     * Represents a displayable pipeline step, which can be:
     * - An audio step (first step for RECORDING sessions)
     * - A transcription step
     * - A processing step (auto-format, rewording, queued prompt)
     * - An input step (for REWORDING/POST_PROCESSING)
     * - A final output step
     */
    public static class PipelineStep {
        public enum Type { AUDIO, TRANSCRIPTION, PROCESSING, INPUT, FINAL_OUTPUT, SOURCE_SESSION }

        public final Type type;
        public final String icon;
        public final String title;
        public final String outputText;
        public final String errorText;
        public final String metaText;
        public final String audioFilePath;

        // For processing steps with versions
        public final ProcessingStepEntity stepEntity;
        public final List<ProcessingStepEntity> versions;
        public final int chainIndex;
        public final boolean showRegenerate;
        public final boolean showOtherPrompt;
        public final boolean showPostProcess;

        // For source session link
        public final String sourceSessionId;

        private PipelineStep(Builder builder) {
            this.type = builder.type;
            this.icon = builder.icon;
            this.title = builder.title;
            this.outputText = builder.outputText;
            this.errorText = builder.errorText;
            this.metaText = builder.metaText;
            this.audioFilePath = builder.audioFilePath;
            this.stepEntity = builder.stepEntity;
            this.versions = builder.versions;
            this.chainIndex = builder.chainIndex;
            this.showRegenerate = builder.showRegenerate;
            this.showOtherPrompt = builder.showOtherPrompt;
            this.showPostProcess = builder.showPostProcess;
            this.sourceSessionId = builder.sourceSessionId;
        }

        public static class Builder {
            private final Type type;
            private String icon = "";
            private String title = "";
            private String outputText;
            private String errorText;
            private String metaText;
            private String audioFilePath;
            private ProcessingStepEntity stepEntity;
            private List<ProcessingStepEntity> versions;
            private int chainIndex;
            private boolean showRegenerate;
            private boolean showOtherPrompt;
            private boolean showPostProcess;
            private String sourceSessionId;

            public Builder(Type type) {
                this.type = type;
            }

            public Builder icon(String icon) { this.icon = icon; return this; }
            public Builder title(String title) { this.title = title; return this; }
            public Builder outputText(String text) { this.outputText = text; return this; }
            public Builder errorText(String text) { this.errorText = text; return this; }
            public Builder metaText(String text) { this.metaText = text; return this; }
            public Builder audioFilePath(String path) { this.audioFilePath = path; return this; }
            public Builder stepEntity(ProcessingStepEntity entity) { this.stepEntity = entity; return this; }
            public Builder versions(List<ProcessingStepEntity> versions) { this.versions = versions; return this; }
            public Builder chainIndex(int index) { this.chainIndex = index; return this; }
            public Builder showRegenerate(boolean show) { this.showRegenerate = show; return this; }
            public Builder showOtherPrompt(boolean show) { this.showOtherPrompt = show; return this; }
            public Builder showPostProcess(boolean show) { this.showPostProcess = show; return this; }
            public Builder sourceSessionId(String id) { this.sourceSessionId = id; return this; }

            public PipelineStep build() { return new PipelineStep(this); }
        }
    }

    public interface StepActionCallback {
        void onPlayAudio(String audioFilePath);
        void onRegenerate(ProcessingStepEntity step, int chainIndex);
        void onOtherPrompt(ProcessingStepEntity step, int chainIndex);
        void onPostProcess(ProcessingStepEntity step);
        void onVersionSelected(int chainIndex, ProcessingStepEntity selectedVersion);
        void onOpenSourceSession(String sessionId);
    }

    public PipelineStepAdapter(List<PipelineStep> steps, StepActionCallback callback) {
        this.steps = steps;
        this.callback = callback;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_pipeline_step, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        PipelineStep step = steps.get(position);

        // Connector visibility — hide for first item
        holder.connector.setVisibility(position == 0 ? View.INVISIBLE : View.VISIBLE);

        // Icon and title
        holder.iconTv.setText(step.icon);
        holder.titleTv.setText(step.title);

        // Output text
        if (step.outputText != null && !step.outputText.isEmpty()) {
            holder.outputTv.setVisibility(View.VISIBLE);
            holder.outputTv.setText(step.outputText);
        } else {
            holder.outputTv.setVisibility(View.GONE);
        }

        // Error text
        if (step.errorText != null && !step.errorText.isEmpty()) {
            holder.errorTv.setVisibility(View.VISIBLE);
            holder.errorTv.setText(step.errorText);
        } else {
            holder.errorTv.setVisibility(View.GONE);
        }

        // Meta text
        if (step.metaText != null && !step.metaText.isEmpty()) {
            holder.metaTv.setVisibility(View.VISIBLE);
            holder.metaTv.setText(step.metaText);
        } else {
            holder.metaTv.setVisibility(View.GONE);
        }

        // Play button (audio only)
        if (step.type == PipelineStep.Type.AUDIO && step.audioFilePath != null) {
            holder.playBtn.setVisibility(View.VISIBLE);
            holder.playBtn.setOnClickListener(v -> callback.onPlayAudio(step.audioFilePath));
        } else {
            holder.playBtn.setVisibility(View.GONE);
        }

        // Regenerate button
        if (step.showRegenerate && step.stepEntity != null) {
            holder.regenerateBtn.setVisibility(View.VISIBLE);
            holder.regenerateBtn.setOnClickListener(v -> callback.onRegenerate(step.stepEntity, step.chainIndex));
        } else {
            holder.regenerateBtn.setVisibility(View.GONE);
        }

        // Other prompt button
        if (step.showOtherPrompt && step.stepEntity != null) {
            holder.otherPromptBtn.setVisibility(View.VISIBLE);
            holder.otherPromptBtn.setOnClickListener(v -> callback.onOtherPrompt(step.stepEntity, step.chainIndex));
        } else {
            holder.otherPromptBtn.setVisibility(View.GONE);
        }

        // Post-process button
        if (step.showPostProcess && step.stepEntity != null) {
            holder.postProcessBtn.setVisibility(View.VISIBLE);
            holder.postProcessBtn.setOnClickListener(v -> callback.onPostProcess(step.stepEntity));
        } else {
            holder.postProcessBtn.setVisibility(View.GONE);
        }

        // Source session link
        if (step.type == PipelineStep.Type.SOURCE_SESSION && step.sourceSessionId != null) {
            holder.itemView.setOnClickListener(v -> callback.onOpenSourceSession(step.sourceSessionId));
        }

        // Version chips — use tags + ChipGroup listener to avoid onClick/checkable conflicts
        holder.versionChipGroup.setOnCheckedStateChangeListener(null); // clear before rebuilding
        if (step.versions != null && step.versions.size() > 1) {
            holder.versionChipGroup.setVisibility(View.VISIBLE);
            holder.versionChipGroup.removeAllViews();

            for (ProcessingStepEntity version : step.versions) {
                Chip chip = new Chip(holder.versionChipGroup.getContext());
                chip.setId(View.generateViewId());
                String chipText = holder.itemView.getContext().getString(
                        R.string.dictate_history_version, version.getVersion());
                String timeStr = timeFormat.format(new Date(version.getCreatedAt()));
                chip.setText(chipText + " (" + timeStr + ")");
                chip.setCheckable(true);
                chip.setChecked(version.isCurrent());
                chip.setTag(version);
                holder.versionChipGroup.addView(chip);
            }

            holder.versionChipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
                if (checkedIds.isEmpty()) return;
                Chip selected = group.findViewById(checkedIds.get(0));
                if (selected != null && selected.getTag() instanceof ProcessingStepEntity) {
                    ProcessingStepEntity selectedVersion = (ProcessingStepEntity) selected.getTag();
                    if (!selectedVersion.isCurrent()) {
                        callback.onVersionSelected(step.chainIndex, selectedVersion);
                    }
                }
            });

            // Show warning if selected version differs from what downstream uses
            boolean currentMatchesLatest = step.versions.get(step.versions.size() - 1).isCurrent();
            holder.versionWarningTv.setVisibility(currentMatchesLatest ? View.GONE : View.VISIBLE);
        } else {
            holder.versionChipGroup.setVisibility(View.GONE);
            holder.versionWarningTv.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return steps.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        final View connector;
        final TextView iconTv;
        final TextView titleTv;
        final ImageButton playBtn;
        final ImageButton regenerateBtn;
        final ImageButton otherPromptBtn;
        final ImageButton postProcessBtn;
        final TextView outputTv;
        final TextView errorTv;
        final TextView metaTv;
        final ChipGroup versionChipGroup;
        final TextView versionWarningTv;

        public ViewHolder(View itemView) {
            super(itemView);
            connector = itemView.findViewById(R.id.item_pipeline_connector);
            iconTv = itemView.findViewById(R.id.item_pipeline_icon_tv);
            titleTv = itemView.findViewById(R.id.item_pipeline_title_tv);
            playBtn = itemView.findViewById(R.id.item_pipeline_play_btn);
            regenerateBtn = itemView.findViewById(R.id.item_pipeline_regenerate_btn);
            otherPromptBtn = itemView.findViewById(R.id.item_pipeline_other_prompt_btn);
            postProcessBtn = itemView.findViewById(R.id.item_pipeline_post_process_btn);
            outputTv = itemView.findViewById(R.id.item_pipeline_output_tv);
            errorTv = itemView.findViewById(R.id.item_pipeline_error_tv);
            metaTv = itemView.findViewById(R.id.item_pipeline_meta_tv);
            versionChipGroup = itemView.findViewById(R.id.item_pipeline_version_chip_group);
            versionWarningTv = itemView.findViewById(R.id.item_pipeline_version_warning_tv);
        }
    }
}
