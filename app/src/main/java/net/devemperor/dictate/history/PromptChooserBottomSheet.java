package net.devemperor.dictate.history;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.textfield.TextInputEditText;

import net.devemperor.dictate.R;
import net.devemperor.dictate.database.DictateDatabase;
import net.devemperor.dictate.database.entity.PromptEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Bottom sheet for choosing a prompt for regeneration.
 * Supports free-text input and selecting from saved prompts.
 *
 * The host Activity must implement {@link OnPromptChosenListener} so the listener
 * survives configuration changes (rotation). Use {@link #newInstance(String)} to
 * pass context via arguments, then retrieve results via the interface.
 */
public class PromptChooserBottomSheet extends BottomSheetDialogFragment {

    private static final String ARG_TAG = "prompt_chooser_tag";

    private OnPromptChosenListener listener;

    public interface OnPromptChosenListener {
        void onPromptChosen(String tag, String promptText, @Nullable Integer promptEntityId);
    }

    /**
     * Creates a new instance with a tag to identify the callback context.
     * @param tag identifies which action triggered the chooser (e.g. "regenerate:0" or "post:sessionId")
     */
    public static PromptChooserBottomSheet newInstance(String tag) {
        PromptChooserBottomSheet sheet = new PromptChooserBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_TAG, tag);
        sheet.setArguments(args);
        return sheet;
    }

    @Override
    public void onAttach(@NonNull android.content.Context context) {
        super.onAttach(context);
        if (context instanceof OnPromptChosenListener) {
            listener = (OnPromptChosenListener) context;
        } else {
            throw new IllegalStateException(context.getClass().getSimpleName()
                    + " must implement PromptChooserBottomSheet.OnPromptChosenListener");
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        listener = null;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_prompt_chooser, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        String tag = getArguments() != null ? getArguments().getString(ARG_TAG, "") : "";

        TextInputEditText freetextEt = view.findViewById(R.id.prompt_chooser_freetext_et);
        RecyclerView promptsRv = view.findViewById(R.id.prompt_chooser_rv);

        // Load saved prompts
        List<PromptEntity> prompts = new ArrayList<>(
                DictateDatabase.getInstance(requireContext()).promptDao().getAll()
        );

        promptsRv.setLayoutManager(new LinearLayoutManager(requireContext()));
        promptsRv.setAdapter(new PromptChooserAdapter(prompts, prompt -> {
            if (listener != null) {
                listener.onPromptChosen(tag, prompt.getPrompt(), prompt.getId());
            }
            dismiss();
        }));

        // Handle free-text submission via button or IME action
        View.OnClickListener submitFreetext = v -> {
            String text = freetextEt.getText() != null ? freetextEt.getText().toString().trim() : "";
            if (!text.isEmpty() && listener != null) {
                listener.onPromptChosen(tag, text, null);
                dismiss();
            }
        };

        view.findViewById(R.id.prompt_chooser_send_btn).setOnClickListener(submitFreetext);

        freetextEt.setOnEditorActionListener((v, actionId, event) -> {
            String text = freetextEt.getText() != null ? freetextEt.getText().toString().trim() : "";
            if (!text.isEmpty() && listener != null) {
                listener.onPromptChosen(tag, text, null);
                dismiss();
                return true;
            }
            return false;
        });
    }
}
