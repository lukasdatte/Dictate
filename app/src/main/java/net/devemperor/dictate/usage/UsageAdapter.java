package net.devemperor.dictate.usage;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import net.devemperor.dictate.R;
import net.devemperor.dictate.ai.AIProvider;
import net.devemperor.dictate.database.entity.UsageEntity;

import java.util.List;

public class UsageAdapter extends RecyclerView.Adapter<UsageAdapter.RecyclerViewHolder> {

    private final AppCompatActivity activity;
    private final List<UsageEntity> data;

    public UsageAdapter(AppCompatActivity activity, List<UsageEntity> data) {
        this.activity = activity;
        this.data = data;
    }

    @NonNull
    @Override
    public RecyclerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_usage, parent, false);
        return new RecyclerViewHolder(view);
    }

    public static class RecyclerViewHolder extends RecyclerView.ViewHolder {
        final TextView itemModelNameTv;
        final TableRow itemInputTokensTr;
        final TableRow itemOutputTokensTr;
        final TableRow itemAudioTimeTr;
        final TextView itemInputTokensValueTv;
        final TextView itemOutputTokensValueTv;
        final TextView itemAudioTimeValueTv;

        public RecyclerViewHolder(View itemView) {
            super(itemView);
            itemModelNameTv = itemView.findViewById(R.id.item_usage_model_name);
            itemInputTokensTr = itemView.findViewById(R.id.item_usage_input_tokens);
            itemOutputTokensTr = itemView.findViewById(R.id.item_usage_output_tokens);
            itemAudioTimeTr = itemView.findViewById(R.id.item_usage_audio_time);
            itemInputTokensValueTv = itemView.findViewById(R.id.item_usage_input_tokens_value);
            itemOutputTokensValueTv = itemView.findViewById(R.id.item_usage_output_tokens_value);
            itemAudioTimeValueTv = itemView.findViewById(R.id.item_usage_audio_time_value);
        }
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onBindViewHolder(@NonNull RecyclerViewHolder holder, final int position) {
        UsageEntity entity = data.get(position);

        // Resolve provider display name from AIProvider enum
        String providerDisplay;
        try {
            AIProvider provider = AIProvider.valueOf(entity.getModelProvider());
            providerDisplay = provider.getDisplayName();
        } catch (IllegalArgumentException e) {
            providerDisplay = entity.getModelProvider();
        }

        holder.itemModelNameTv.setText(entity.getModelName() + (providerDisplay.isEmpty() ? "" : " (" + providerDisplay + ")"));
        if (entity.getInputTokens() == 0) {
            holder.itemInputTokensTr.setVisibility(View.GONE);
            holder.itemOutputTokensTr.setVisibility(View.GONE);

            holder.itemAudioTimeValueTv.setText(activity.getString(R.string.dictate_usage_audio_time, entity.getAudioTime() / 60, entity.getAudioTime() % 60));
        } else {
            holder.itemAudioTimeTr.setVisibility(View.GONE);

            holder.itemInputTokensValueTv.setText(String.valueOf(entity.getInputTokens()));
            holder.itemOutputTokensValueTv.setText(String.valueOf(entity.getOutputTokens()));
        }
    }

    @Override
    public int getItemCount() {
        return data.size();
    }
}
