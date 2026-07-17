package net.devemperor.dictate.rewording;

import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import net.devemperor.dictate.R;
import net.devemperor.dictate.database.entity.PromptEntity;
import net.devemperor.dictate.database.entity.PromptType;

import java.util.List;

/**
 * Renders the prompt cards on the overview screen. Pure presentation: every
 * mutation (edit, delete, duplicate, reorder) is delegated to the Activity via
 * {@link AdapterCallback} so database writes stay out of the view layer.
 */
public class PromptsOverviewAdapter extends RecyclerView.Adapter<PromptsOverviewAdapter.RecyclerViewHolder> {

    private final List<PromptEntity> data;
    private final AdapterCallback callback;

    public interface AdapterCallback {
        void onItemClicked(int position);
        void onDeleteClicked(int position);
        void onDuplicateClicked(int position);
        void onStartDrag(RecyclerView.ViewHolder holder);
    }

    public PromptsOverviewAdapter(List<PromptEntity> data, AdapterCallback callback) {
        this.data = data;
        this.callback = callback;
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
        final ImageView typeIv;
        final ImageView dragHandleIv;
        final MaterialButton duplicateBtn;
        final MaterialButton deleteBtn;

        public RecyclerViewHolder(View itemView) {
            super(itemView);
            itemNameTv = itemView.findViewById(R.id.item_prompts_overview_name_tv);
            itemPromptTv = itemView.findViewById(R.id.item_prompts_overview_prompt_tv);
            requiresSelectionIv = itemView.findViewById(R.id.item_prompts_overview_requires_selection_iv);
            autoApplyIv = itemView.findViewById(R.id.item_prompts_overview_auto_apply_iv);
            typeIv = itemView.findViewById(R.id.item_prompts_overview_type_iv);
            dragHandleIv = itemView.findViewById(R.id.item_prompts_overview_drag_handle);
            duplicateBtn = itemView.findViewById(R.id.item_prompts_overview_duplicate_btn);
            deleteBtn = itemView.findViewById(R.id.item_prompts_overview_delete_btn);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onBindViewHolder(@NonNull RecyclerViewHolder holder, final int position) {
        int currentPosition = holder.getBindingAdapterPosition();
        if (currentPosition == RecyclerView.NO_POSITION) return;

        PromptEntity entity = data.get(currentPosition);
        holder.itemNameTv.setText(entity.getName());
        holder.itemPromptTv.setText(entity.getPrompt());
        holder.itemView.setOnClickListener(v -> dispatch(holder, callback::onItemClicked));

        int enabledColor = ContextCompat.getColor(holder.itemView.getContext(), R.color.dictate_blue);
        int disabledColor = ContextCompat.getColor(holder.itemView.getContext(), R.color.dictate_grey);
        // A text pill inserts literally — its requiresSelection/autoApply flags are
        // meaningless, so show a single "text" indicator instead of those icons.
        boolean isText = entity.getTypeEnum() == PromptType.TEXT;
        holder.typeIv.setVisibility(isText ? View.VISIBLE : View.GONE);
        holder.typeIv.setImageTintList(ColorStateList.valueOf(enabledColor));
        holder.requiresSelectionIv.setVisibility(isText ? View.GONE : View.VISIBLE);
        holder.autoApplyIv.setVisibility(isText ? View.GONE : View.VISIBLE);
        holder.requiresSelectionIv.setImageTintList(ColorStateList.valueOf(
                entity.getRequiresSelection() ? enabledColor : disabledColor));
        holder.autoApplyIv.setImageTintList(ColorStateList.valueOf(
                entity.getAutoApply() ? enabledColor : disabledColor));

        holder.duplicateBtn.setOnClickListener(v -> dispatch(holder, callback::onDuplicateClicked));
        holder.deleteBtn.setOnClickListener(v -> dispatch(holder, callback::onDeleteClicked));
        holder.dragHandleIv.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                callback.onStartDrag(holder);
            }
            return false;
        });
    }

    private interface PositionAction {
        void run(int position);
    }

    /** Resolves the holder's live position at click time (it may have moved since bind). */
    private void dispatch(RecyclerViewHolder holder, PositionAction action) {
        int pos = holder.getBindingAdapterPosition();
        if (pos != RecyclerView.NO_POSITION) action.run(pos);
    }

    @Override
    public int getItemCount() {
        return data.size();
    }
}
