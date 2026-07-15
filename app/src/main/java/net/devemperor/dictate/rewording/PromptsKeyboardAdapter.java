package net.devemperor.dictate.rewording;

import android.animation.TimeInterpolator;
import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
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
    // §6.2: when PC-mode is active, selection-requiring prompt pills are greyed and gated (their
    // selection lives on the invisible PC). Driven by PromptChipsBusyObserver.
    private boolean pcModeActive = false;
    private MaterialButton selectAllButton;
    private boolean selectAllActive = false;

    private boolean showLanguageChip = false;
    private String currentLanguageLabel = null;
    private LanguageChipClickListener languageChipListener = null;
    /**
     * Quality-Gate W-6: chip enabled-state is tracked separately from
     * visibility so the pipeline-running disable can be applied without
     * touching {@link #setLanguageChipVisible(boolean, String)}'s 2-arg
     * signature. Defaults to {@code true} (clickable) on init.
     */
    private boolean chipEnabled = true;

    public interface AdapterCallback {
        void onItemClicked(Integer position);
        void onItemLongClicked(Integer position);

        /**
         * Long-press on a greyed-out text-only pill (a non-selection prompt
         * disabled while recording/pipeline is busy). The pill is applied
         * exactly as an idle short-press would apply it — see
         * {@link PromptPillPressPolicy} and {@link PromptPillAction#APPLY_DISABLED}.
         */
        void onTextOnlyItemApplyRequested(Integer position);

        /**
         * Any press on a selection-requiring pill while PC-mode is active (§6.2). The PC selection
         * cannot be read in v1, so the pill is greyed and the press shows a hint instead of running
         * the prompt. See {@link PromptPillPressPolicy} and
         * {@link PromptPillAction#SELECTION_UNAVAILABLE_HINT}.
         */
        void onSelectionUnavailableInPcMode();
    }

    public interface LanguageChipClickListener {
        // The anchor view is passed so the handler can attach a PopupMenu
        // (IME-window-safe, unlike TYPE_APPLICATION_ATTACHED_DIALOG which
        // throws BadTokenException on some OEM skins).
        void onLanguageChipClicked(View anchor);
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

    /** §6.2: grey + gate selection-requiring pills while PC-mode is active. */
    public void setPcModeActive(boolean active) {
        if (pcModeActive == active) return;
        pcModeActive = active;
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
     * Toggles the chip's clickable state without affecting its visibility.
     * Called by the Service when the pipeline transitions through Running /
     * Preparing — the chip stays visible but greys out and no longer
     * responds to clicks while a transcription is in flight.
     *
     * <p>Quality-Gate W-6: kept separate from
     * {@link #setLanguageChipVisible(boolean, String)} so the existing
     * 2-arg signature does not break and visibility/enabled stay
     * semantically distinct.</p>
     */
    public void setLanguageChipEnabled(boolean enabled) {
        if (this.chipEnabled != enabled) {
            this.chipEnabled = enabled;
            notifyItemChanged(0);  // Position 0 = Chip when visible
        }
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
        // Captured once from the freshly inflated (filled) button so the filled
        // path can restore the exact default label colour after a recycled view
        // was used for an outlined text pill.
        final ColorStateList defaultTextColors;

        public RecyclerViewHolder(View itemView) {
            super(itemView);
            promptBtn = itemView.findViewById(R.id.prompts_keyboard_btn);
            defaultTextColors = promptBtn.getTextColors();
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
        // The language chip is now a regular grid cell (same wrap_content sizing
        // as every other prompt pill). Earlier drafts spanned it across the full
        // row via `setFullSpan(true)` — that produced a header-style strip with
        // a different visible height. We explicitly clear that flag here so a
        // recycled ViewHolder cannot inherit it from a prior assignment.
        ViewGroup.LayoutParams lp = rawHolder.itemView.getLayoutParams();
        if (lp instanceof StaggeredGridLayoutManager.LayoutParams) {
            ((StaggeredGridLayoutManager.LayoutParams) lp).setFullSpan(false);
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
        // A "text-only pill" = a saved prompt (id >= 0) that does not require a
        // selection; these are greyed while recording/pipeline is busy. We keep
        // the view ENABLED even when greyed — an isEnabled=false View receives no
        // MotionEvents, so a long-press could never fire on it. The disabled look
        // is rendered via alpha only; the short/long press is gated through
        // PromptPillPressPolicy in the listeners below so a long-press still
        // applies the pill while a short-press stays inert.
        // Text pills (PromptType.TEXT) are literal snippets that insert 1:1 with
        // no AI call — they are safe in every state, so they are NEVER greyed out.
        // Only AI prompt pills that don't require a selection get the busy-state
        // greying (short-press inert, long-press applies).
        final boolean isTextPill =
                model.getTypeEnum() == net.devemperor.dictate.database.entity.PromptType.TEXT;
        final boolean textOnlyDisabled =
                disableNonSelectionPrompts && model.getId() >= 0
                        && !model.getRequiresSelection() && !isTextPill;
        // §6.2: a selection-requiring saved prompt is gated in PC-mode (no readable PC selection).
        final boolean selectionUnavailable =
                pcModeActive && model.getId() >= 0 && model.getRequiresSelection();
        holder.promptBtn.setEnabled(true);
        holder.promptBtn.setAlpha((textOnlyDisabled || selectionUnavailable) ? 0.5f : 1f);
        if (model.getId() >= 0) {
            holder.promptBtn.setIcon(queuedPromptOrder.contains(model.getId())
                    ? AppCompatResources.getDrawable(holder.promptBtn.getContext(), R.drawable.ic_baseline_check_circle_outline_24)
                    : null);
            holder.promptBtn.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_END);
        } else {
            holder.promptBtn.setIcon(null);
        }
        final int dataPos = toDataIndex(position);
        holder.promptBtn.setOnClickListener(v -> {
            PromptPillAction action = PromptPillPressPolicy.decide(
                    PromptPillPress.SHORT, textOnlyDisabled, model.getTypeEnum(), selectionUnavailable);
            if (action == PromptPillAction.ACTIVATE) {
                callback.onItemClicked(dataPos);
            } else if (action == PromptPillAction.SELECTION_UNAVAILABLE_HINT) {
                callback.onSelectionUnavailableInPcMode();
            }
            // IGNORE (greyed text-only pill) → short press stays inert.
        });
        holder.promptBtn.setOnLongClickListener(v -> {
            PromptPillAction action = PromptPillPressPolicy.decide(
                    PromptPillPress.LONG, textOnlyDisabled, model.getTypeEnum(), selectionUnavailable);
            if (action == PromptPillAction.APPLY_DISABLED) {
                callback.onTextOnlyItemApplyRequested(dataPos);
            } else if (action == PromptPillAction.SELECTION_UNAVAILABLE_HINT) {
                callback.onSelectionUnavailableInPcMode();
            } else {
                callback.onItemLongClicked(dataPos);
            }
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
        if (isTextPill) {
            applyTextPillColors(holder.promptBtn, accentColor);
        } else {
            applyFilledPromptButtonColors(holder.promptBtn, backgroundColor, holder.defaultTextColors);
        }
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
        holder.chipBtn.setEnabled(chipEnabled);
        holder.chipBtn.setAlpha(chipEnabled ? 1f : 0.5f);
        // Match the visual treatment of normal prompt pills: filled background
        // tinted with the medium-darkness accent shade (same value as a regular
        // PromptEntity row in onBindViewHolder). Keeps the chip indistinguishable
        // from its neighbours apart from its position-zero anchor.
        int accentColor = DictatePrefsKt.get(sp, Pref.AccentColor.INSTANCE);
        int accentColorMedium = DictateUtils.darkenColor(accentColor, 0.18f);
        applyPromptButtonColors(holder.chipBtn, accentColorMedium);
        holder.chipBtn.setOnClickListener(v -> {
            if (languageChipListener != null) languageChipListener.onLanguageChipClicked(v);
        });
    }

    private void applyPromptButtonColors(MaterialButton button, int backgroundColor) {
        if (button == null) return;
        button.setBackgroundColor(backgroundColor);
    }

    /**
     * Filled treatment for AI prompt pills (and sentinels): solid accent-tinted
     * background, default label colour. Also RESETS the stroke + label colour so
     * a view recycled from an outlined text pill returns to the filled look.
     */
    private void applyFilledPromptButtonColors(MaterialButton button, int backgroundColor, ColorStateList defaultTextColors) {
        if (button == null) return;
        button.setStrokeWidth(0);
        button.setBackgroundColor(backgroundColor);
        if (defaultTextColors != null) {
            button.setTextColor(defaultTextColors);
        }
    }

    /**
     * Outlined/tonal treatment for text pills (F1 Option 1): transparent fill +
     * accent stroke + accent label. Reads differently from a filled AI prompt
     * pill with ANY accent (colour-blind-safe — distinguished by form + tone, not
     * hue) and never collides with the reserved PC-mode purple.
     */
    private void applyTextPillColors(MaterialButton button, int accentColor) {
        if (button == null) return;
        int strokePx = Math.round(button.getResources().getDisplayMetrics().density * 1.5f);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setStrokeColor(ColorStateList.valueOf(accentColor));
        button.setStrokeWidth(strokePx);
        button.setTextColor(accentColor);
    }

    private void updateSelectAllButtonIcon() {
        if (selectAllButton == null) return;
        selectAllButton.setForeground(AppCompatResources.getDrawable(
                selectAllButton.getContext(),
                selectAllActive ? R.drawable.ic_baseline_deselect_24 : R.drawable.ic_baseline_select_all_24));
    }

}
