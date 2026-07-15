package net.devemperor.dictate.rewording;

import net.devemperor.dictate.ai.prompt.PromptTypeClassifier;
import net.devemperor.dictate.database.entity.PromptEntity;
import net.devemperor.dictate.database.entity.PromptType;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Serialises prompt pills to / from the JSON export format, isolated from
 * {@link PromptsOverviewActivity} so version handling and type classification
 * are unit-testable without an Activity.
 *
 * <p>Export is always the current version ({@link #EXPORT_VERSION}, with an
 * explicit {@code type}). Import accepts both:</p>
 * <ul>
 *   <li>v2 files — {@code type} is read verbatim (normalised to a known enum
 *       value so a malformed value can never violate the {@code type} CHECK);</li>
 *   <li>v1 files (no {@code type}) — each prompt is classified with the shared
 *       {@link PromptTypeClassifier} rule, i.e. the same trim+strip the schema-v11
 *       migration applies to legacy rows, so an imported {@code [literal]} becomes
 *       a stripped TEXT pill and a fully-bracketed name loses its brackets.</li>
 * </ul>
 */
public final class PromptImportExport {

    public static final int EXPORT_VERSION = 2;

    private PromptImportExport() {
    }

    public static JSONObject buildExport(List<PromptEntity> prompts) throws JSONException {
        JSONArray promptsArray = new JSONArray();
        for (PromptEntity entity : prompts) {
            JSONObject o = new JSONObject();
            o.put("name", entity.getName());
            o.put("prompt", entity.getPrompt());
            o.put("requiresSelection", entity.getRequiresSelection());
            o.put("autoApply", entity.getAutoApply());
            o.put("type", entity.getTypeEnum().name());
            promptsArray.put(o);
        }
        JSONObject root = new JSONObject();
        root.put("version", EXPORT_VERSION);
        root.put("prompts", promptsArray);
        return root;
    }

    public static List<PromptEntity> parse(String json) throws JSONException {
        JSONArray promptsArray = null;
        try {
            JSONObject root = new JSONObject(json);
            promptsArray = root.optJSONArray("prompts");
        } catch (JSONException ignored) {
        }
        if (promptsArray == null) {
            promptsArray = new JSONArray(json);
        }

        List<PromptEntity> prompts = new ArrayList<>();
        for (int i = 0; i < promptsArray.length(); i++) {
            JSONObject o = promptsArray.optJSONObject(i);
            if (o == null) continue;

            String rawName = o.optString("name", "");
            String rawPrompt = o.optString("prompt", "");
            if (rawName.isEmpty() || rawPrompt.isEmpty()) continue;

            boolean requiresSelection = o.optBoolean("requiresSelection", false);
            boolean autoApply = o.optBoolean("autoApply", false);

            String name;
            String prompt;
            String type;
            if (o.has("type")) {
                // v2: the pill type is explicit; take name/prompt verbatim.
                name = rawName;
                prompt = rawPrompt;
                type = normalizeType(o.optString("type", PromptType.PROMPT.name()));
            } else {
                // v1: no type field — classify with the shared migration rule.
                kotlin.Pair<PromptType, String> classified = PromptTypeClassifier.classify(rawPrompt);
                type = classified.getFirst().name();
                prompt = classified.getSecond();
                name = PromptTypeClassifier.stripName(rawName);
            }
            prompts.add(new PromptEntity(0, prompts.size(), name, prompt, requiresSelection, autoApply, type));
        }
        return prompts;
    }

    /**
     * Maps an imported type string to a valid {@link PromptType} name, falling
     * back to {@code PROMPT} for anything unknown — an unrecognised value would
     * otherwise fail the {@code type} CHECK constraint on insert.
     */
    private static String normalizeType(String raw) {
        try {
            return PromptType.valueOf(raw).name();
        } catch (IllegalArgumentException e) {
            return PromptType.PROMPT.name();
        }
    }
}
