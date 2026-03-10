package net.devemperor.dictate.rewording;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import net.devemperor.dictate.R;
import net.devemperor.dictate.database.dao.PromptDao;
import net.devemperor.dictate.database.entity.PromptEntity;

import java.util.List;

public class PromptsOverviewAdapter extends RecyclerView.Adapter<PromptsOverviewAdapter.RecyclerViewHolder> {

    private final AppCompatActivity activity;
    private final List<PromptEntity> data;
    private final AdapterCallback callback;
    private final PromptDao promptDao;

    public interface AdapterCallback {
        void onItemClicked(Integer position);
    }

    public PromptsOverviewAdapter(AppCompatActivity activity, List<PromptEntity> data, PromptDao promptDao, AdapterCallback callback) {
        this.activity = activity;
        this.data = data;
        this.callback = callback;
        this.promptDao = promptDao;
    }

    @NonNull
    @Override
    public RecyclerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_prompts_overview, parent, false);
        return new RecyclerViewHolder(view);
    }

    public static class RecyclerViewHolder extends RecyclerView.ViewHolder {
        final TextView itemNameTv;
        final TextView itemPromptTv;
        final ImageView requiresSelectionIv;
        final ImageView autoApplyIv;
        final View nameContainer;
        final MaterialButton moveUpBtn;
        final MaterialButton moveDownBtn;
        final MaterialButton deleteBtn;

        public RecyclerViewHolder(View itemView) {
            super(itemView);
            itemNameTv = itemView.findViewById(R.id.item_prompts_overview_name_tv);
            itemPromptTv = itemView.findViewById(R.id.item_prompts_overview_prompt_tv);
            requiresSelectionIv = itemView.findViewById(R.id.item_prompts_overview_requires_selection_iv);
            autoApplyIv = itemView.findViewById(R.id.item_prompts_overview_auto_apply_iv);
            nameContainer = itemView.findViewById(R.id.item_prompts_overview_name_container);
            moveUpBtn = itemView.findViewById(R.id.item_prompts_overview_move_up_btn);
            moveDownBtn = itemView.findViewById(R.id.item_prompts_overview_move_down_btn);
            deleteBtn = itemView.findViewById(R.id.item_prompts_overview_delete_btn);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerViewHolder holder, final int position) {
        int currentPosition = holder.getAdapterPosition();
        if(currentPosition == RecyclerView.NO_POSITION) return;

        PromptEntity entity = data.get(currentPosition);
        holder.itemNameTv.setText(entity.getName());
        holder.itemNameTv.setOnClickListener(v -> callback.onItemClicked(currentPosition));
        holder.itemPromptTv.setText(entity.getPrompt());
        holder.itemPromptTv.setOnClickListener(v -> callback.onItemClicked(currentPosition));
        holder.nameContainer.setOnClickListener(v -> callback.onItemClicked(currentPosition));

        int enabledColor = ContextCompat.getColor(holder.itemView.getContext(), R.color.dictate_blue);
        int disabledColor = ContextCompat.getColor(holder.itemView.getContext(), R.color.dictate_grey);
        holder.requiresSelectionIv.setImageTintList(ColorStateList.valueOf(
                entity.getRequiresSelection() ? enabledColor : disabledColor));
        holder.autoApplyIv.setImageTintList(ColorStateList.valueOf(
                entity.getAutoApply() ? enabledColor : disabledColor));

        holder.moveUpBtn.setVisibility(currentPosition == 0 ? View.GONE : View.VISIBLE);
        holder.moveDownBtn.setVisibility(currentPosition == data.size() - 1 ? View.GONE : View.VISIBLE);

        holder.moveUpBtn.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos <= 0) return;

            PromptEntity currentEntity = data.get(pos);
            PromptEntity prevEntity = data.get(pos - 1);

            PromptEntity updatedCurrent = new PromptEntity(currentEntity.getId(), pos - 1, currentEntity.getName(), currentEntity.getPrompt(), currentEntity.getRequiresSelection(), currentEntity.getAutoApply());
            PromptEntity updatedPrev = new PromptEntity(prevEntity.getId(), pos, prevEntity.getName(), prevEntity.getPrompt(), prevEntity.getRequiresSelection(), prevEntity.getAutoApply());
            promptDao.update(updatedCurrent);
            promptDao.update(updatedPrev);
            data.set(pos, updatedPrev);
            data.set(pos - 1, updatedCurrent);

            notifyItemMoved(pos, pos - 1);
            notifyItemChanged(pos);
            notifyItemChanged(pos - 1);
        });

        holder.moveDownBtn.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos >= data.size() - 1) return;

            PromptEntity currentEntity = data.get(pos);
            PromptEntity nextEntity = data.get(pos + 1);

            PromptEntity updatedCurrent = new PromptEntity(currentEntity.getId(), pos + 1, currentEntity.getName(), currentEntity.getPrompt(), currentEntity.getRequiresSelection(), currentEntity.getAutoApply());
            PromptEntity updatedNext = new PromptEntity(nextEntity.getId(), pos, nextEntity.getName(), nextEntity.getPrompt(), nextEntity.getRequiresSelection(), nextEntity.getAutoApply());
            promptDao.update(updatedCurrent);
            promptDao.update(updatedNext);
            data.set(pos, updatedNext);
            data.set(pos + 1, updatedCurrent);

            notifyItemMoved(pos, pos + 1);
            notifyItemChanged(pos);
            notifyItemChanged(pos + 1);
        });

        holder.deleteBtn.setOnClickListener(v -> new MaterialAlertDialogBuilder(v.getContext())
                .setTitle(R.string.dictate_delete_prompt)
                .setMessage(R.string.dictate_delete_prompt_message)
                .setPositiveButton(R.string.dictate_yes, (di, i) -> {
                    int pos = holder.getAdapterPosition();
                    if (pos == RecyclerView.NO_POSITION) return;
                    promptDao.deleteById(entity.getId());
                    data.remove(pos);
                    notifyItemRemoved(pos);

                    activity.findViewById(R.id.prompts_overview_no_prompts_tv).setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
                })
                .setNegativeButton(R.string.dictate_no, null)
                .show());
    }

    @Override
    public int getItemCount() {
        return data.size();
    }
}
