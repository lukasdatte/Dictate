package net.devemperor.dictate.rewording;

import android.animation.TimeInterpolator;
import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.google.android.material.button.MaterialButton;

import net.devemperor.dictate.DictateUtils;
import net.devemperor.dictate.R;
import net.devemperor.dictate.preferences.DictatePrefsKt;
import net.devemperor.dictate.preferences.Pref;
import net.devemperor.dictate.database.entity.PromptEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PromptsKeyboardAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final float PRESSED_SCALE = 0.92f;
    private static final long PRESS_ANIM_DURATION = 80L;
    private static final TimeInterpolator PRESS_INTERPOLATOR = new DecelerateInterpolator();

    // Finding SEC-7-5 (Phase 8): header-at-0 + prompts from index 1 onward.
    // When showLanguageChip is true, all data.get() calls in onBindViewHolder()
    // MUST go through toDataIndex() — otherwise every prompt is off-by-one.
    private static final int VIEW_TYPE_LANGUAGE_CHIP = 0;
    private static final int VIEW_TYPE_PROMPT = 1;

    private final SharedPreferences sp;
    private List<PromptEntity> data;
    private final AdapterCallback callback;
    private final List<Integer> queuedPromptOrder = new ArrayList<>();
    private boolean disableNonSelectionPrompts = false;
    private MaterialButton selectAllButton;
    private boolean selectAllActive = false;

    private boolean showLanguageChip = false;
    private String currentLanguageLabel = null;
    private LanguageChipClickListener languageChipListener = null;

    public interface AdapterCallback {
        void onItemClicked(Integer position);
        void onItemLongClicked(Integer position);
    }

    public interface LanguageChipClickListener {
        void onLanguageChipClicked();
    }

    public PromptsKeyboardAdapter(SharedPreferences sp, List<PromptEntity> data, AdapterCallback callback) {
        this.sp = sp;
        this.data = data;
        this.callback = callback;
    }

    public void updateData(List<PromptEntity> newData) {
        this.data = newData;
        notifyDataSetChanged();
    }

    public PromptEntity getItem(int position) {
        return data.get(position);
    }

    public void setQueuedPromptOrder(List<Integer> queuedPromptIds) {
        queuedPromptOrder.clear();
        queuedPromptOrder.addAll(queuedPromptIds);
        notifyDataSetChanged();
    }

    public void setDisableNonSelectionPrompts(boolean disable) {
        if (disableNonSelectionPrompts == disable) return;
        disableNonSelectionPrompts = disable;
        notifyDataSetChanged();
    }

    public void setSelectAllActive(boolean active) {
        if (selectAllActive == active) return;
        selectAllActive = active;
        if (selectAllButton != null) {
            updateSelectAllButtonIcon();
        }
    }

    /**
     * Enables/disables the language chip at the start of the list. When visible,
     * item positions shift by one (chip at 0, prompts from 1). All data lookups
     * use {@link #toDataIndex(int)}.
     *
     * @param visible       whether the chip should be shown
     * @param languageLabel display label for the currently-selected language
     *                      (e.g. "Deutsch"); null renders the generic label
     */
    public void setLanguageChipVisible(boolean visible, String languageLabel) {
        boolean changed = (this.showLanguageChip != visible)
                || (visible && !java.util.Objects.equals(this.currentLanguageLabel, languageLabel));
        this.showLanguageChip = visible;
        this.currentLanguageLabel = languageLabel;
        if (changed) notifyDataSetChanged();
    }

    public void setLanguageChipListener(LanguageChipClickListener listener) {
        this.languageChipListener = listener;
    }

    /**
     * Finding SEC-7-5: Translates an adapter position into a data-list index,
     * accounting for the optional language chip at position 0.
     */
    private int toDataIndex(int adapterPosition) {
        return adapterPosition - (showLanguageChip ? 1 : 0);
    }

    @Override
    public int getItemViewType(int position) {
        if (showLanguageChip && position == 0) return VIEW_TYPE_LANGUAGE_CHIP;
        return VIEW_TYPE_PROMPT;
    }

    @Override
    public int getItemCount() {
        return (showLanguageChip ? 1 : 0) + data.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_LANGUAGE_CHIP) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_prompts_keyboard_language_chip, parent, false);
            return new LanguageChipViewHolder(view);
        }
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_prompts_keyboard, parent, false);
        return new RecyclerViewHolder(view);
    }

    public static class RecyclerViewHolder extends RecyclerView.ViewHolder {
        final MaterialButton promptBtn;

        public RecyclerViewHolder(View itemView) {
            super(itemView);
            promptBtn = itemView.findViewById(R.id.prompts_keyboard_btn);
        }
    }

    public static class LanguageChipViewHolder extends RecyclerView.ViewHolder {
        final MaterialButton chipBtn;

        public LanguageChipViewHolder(View itemView) {
            super(itemView);
            chipBtn = itemView.findViewById(R.id.prompts_keyboard_language_chip_btn);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder rawHolder, final int position) {
        // StaggeredGridLayout: the language chip spans the full width so it doesn't
        // collapse into a single grid column (SEC-7-5).
        ViewGroup.LayoutParams lp = rawHolder.itemView.getLayoutParams();
        if (lp instanceof StaggeredGridLayoutManager.LayoutParams) {
            ((StaggeredGridLayoutManager.LayoutParams) lp)
                    .setFullSpan(getItemViewType(position) == VIEW_TYPE_LANGUAGE_CHIP);
        }

        if (rawHolder instanceof LanguageChipViewHolder) {
            bindLanguageChip((LanguageChipViewHolder) rawHolder);
            return;
        }

        RecyclerViewHolder holder = (RecyclerViewHolder) rawHolder;
        holder.promptBtn.animate().cancel();
        holder.promptBtn.setScaleX(1f);
        holder.promptBtn.setScaleY(1f);
        PromptEntity model = data.get(toDataIndex(position));
        if (holder.promptBtn == selectAllButton && model.getId() != -3) {
            selectAllButton = null;
        }
        if (model.getId() == -1) {
            holder.promptBtn.setText("");
            holder.promptBtn.setForeground(AppCompatResources.getDrawable(holder.promptBtn.getContext(), R.drawable.ic_baseline_auto_awesome_18));
            holder.promptBtn.setIcon(null);
            holder.promptBtn.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        } else if (model.getId() == -3) {
            holder.promptBtn.setText("");
            holder.promptBtn.setIcon(null);
            holder.promptBtn.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
            selectAllButton = holder.promptBtn;
            updateSelectAllButtonIcon();
        } else if (model.getId() == -4) {
            holder.promptBtn.setText("");
            holder.promptBtn.setForeground(AppCompatResources.getDrawable(holder.promptBtn.getContext(), R.drawable.ic_baseline_cleaning_services_24));
            holder.promptBtn.setIcon(null);
            holder.promptBtn.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
            // Gray out when queue is empty
            boolean hasQueue = !queuedPromptOrder.isEmpty();
            holder.promptBtn.setEnabled(hasQueue);
            holder.promptBtn.setAlpha(hasQueue ? 1f : 0.35f);
        } else if (model.getId() == -2) {
            holder.promptBtn.setText("");
            holder.promptBtn.setForeground(AppCompatResources.getDrawable(holder.promptBtn.getContext(), R.drawable.ic_baseline_add_24));
            holder.promptBtn.setIcon(null);
            holder.promptBtn.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        } else {
            int queueIndex = queuedPromptOrder.indexOf(model.getId());
            if (queueIndex >= 0) {
                holder.promptBtn.setText(String.format(Locale.getDefault(), "%s (%d)", model.getName(), queueIndex + 1));
            } else {
                holder.promptBtn.setText(model.getName());
            }
            holder.promptBtn.setForeground(null);
        }
        boolean shouldDisable = disableNonSelectionPrompts && model.getId() >= 0 && !model.getRequiresSelection();
        holder.promptBtn.setEnabled(!shouldDisable);
        holder.promptBtn.setAlpha(shouldDisable ? 0.5f : 1f);
        if (model.getId() >= 0) {
            holder.promptBtn.setIcon(queuedPromptOrder.contains(model.getId())
                    ? AppCompatResources.getDrawable(holder.promptBtn.getContext(), R.drawable.ic_baseline_check_circle_outline_24)
                    : null);
            holder.promptBtn.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_END);
        } else {
            holder.promptBtn.setIcon(null);
        }
        final int dataPos = toDataIndex(position);
        holder.promptBtn.setOnClickListener(v -> callback.onItemClicked(dataPos));
        holder.promptBtn.setOnLongClickListener(v -> {
            callback.onItemLongClicked(dataPos);
            return true;
        });
        int accentColor = DictatePrefsKt.get(sp, Pref.AccentColor.INSTANCE);
        int accentColorMedium = DictateUtils.darkenColor(accentColor, 0.18f);
        int accentColorDark = DictateUtils.darkenColor(accentColor, 0.35f);
        int backgroundColor;
        if (model.getId() == -1 || model.getId() == -3 || model.getId() == -4) {
            backgroundColor = accentColor;
        } else if (model.getId() == -2) {
            backgroundColor = accentColorDark;
        } else {
            backgroundColor = accentColorMedium;
        }
        applyPromptButtonColors(holder.promptBtn, backgroundColor);
        if (DictatePrefsKt.get(sp, Pref.Animations.INSTANCE)) {
            holder.promptBtn.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.animate()
                                .scaleX(PRESSED_SCALE)
                                .scaleY(PRESSED_SCALE)
                                .setDuration(PRESS_ANIM_DURATION)
                                .setInterpolator(PRESS_INTERPOLATOR)
                                .start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(PRESS_ANIM_DURATION)
                                .setInterpolator(PRESS_INTERPOLATOR)
                                .start();
                        break;
                }
                return false;
            });
        } else {
            holder.promptBtn.setOnTouchListener(null);
            holder.promptBtn.setScaleX(1f);
            holder.promptBtn.setScaleY(1f);
        }
    }

    private void bindLanguageChip(LanguageChipViewHolder holder) {
        String label = currentLanguageLabel != null
                ? currentLanguageLabel
                : holder.itemView.getContext().getString(R.string.dictate_reprocess_language);
        holder.chipBtn.setText(label);
        holder.chipBtn.setOnClickListener(v -> {
            if (languageChipListener != null) languageChipListener.onLanguageChipClicked();
        });
    }

    private void applyPromptButtonColors(MaterialButton button, int backgroundColor) {
        if (button == null) return;
        button.setBackgroundColor(backgroundColor);
    }

    private void updateSelectAllButtonIcon() {
        if (selectAllButton == null) return;
        selectAllButton.setForeground(AppCompatResources.getDrawable(
                selectAllButton.getContext(),
                selectAllActive ? R.drawable.ic_baseline_deselect_24 : R.drawable.ic_baseline_select_all_24));
    }

}
