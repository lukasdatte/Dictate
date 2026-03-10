package net.devemperor.dictate.history;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import net.devemperor.dictate.R;
import net.devemperor.dictate.database.entity.PromptEntity;

import java.util.List;

public class PromptChooserAdapter extends RecyclerView.Adapter<PromptChooserAdapter.ViewHolder> {

    private final List<PromptEntity> prompts;
    private final OnPromptSelectedListener listener;

    public interface OnPromptSelectedListener {
        void onPromptSelected(PromptEntity prompt);
    }

    public PromptChooserAdapter(List<PromptEntity> prompts, OnPromptSelectedListener listener) {
        this.prompts = prompts;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_prompt_chooser, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        PromptEntity prompt = prompts.get(position);
        holder.nameTv.setText(prompt.getName() != null ? prompt.getName() : "");
        holder.previewTv.setText(prompt.getPrompt() != null ? prompt.getPrompt() : "");
        holder.itemView.setOnClickListener(v -> listener.onPromptSelected(prompt));
    }

    @Override
    public int getItemCount() {
        return prompts.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView nameTv;
        final TextView previewTv;

        public ViewHolder(View itemView) {
            super(itemView);
            nameTv = itemView.findViewById(R.id.item_prompt_chooser_name_tv);
            previewTv = itemView.findViewById(R.id.item_prompt_chooser_preview_tv);
        }
    }
}
