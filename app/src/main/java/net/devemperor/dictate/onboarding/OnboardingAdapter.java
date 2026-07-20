package net.devemperor.dictate.onboarding;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import net.devemperor.dictate.accessibility.A11yEnablementGate;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import net.devemperor.dictate.DictateUtils;
import net.devemperor.dictate.R;
import net.devemperor.dictate.core.NotificationPermissionPolicy;
import net.devemperor.dictate.config.ConfigEntitySetup;
import net.devemperor.dictate.preferences.DictatePrefsKt;
import net.devemperor.dictate.preferences.Pref;
import net.devemperor.dictate.settings.DictateSettingsActivity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;


public class OnboardingAdapter extends RecyclerView.Adapter<OnboardingAdapter.ViewHolder> {

    Activity activity;
    int[] layoutIds;

    public OnboardingAdapter(Activity activity, int[] layoutIds) {
        this.activity = activity;
        this.layoutIds = layoutIds;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(layoutIds[viewType], parent, false);

        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        if (position == 1) {
            TextView microphoneStatusTv = holder.itemView.findViewById(R.id.onboarding_permissions_microphone_status_tv);
            TextView keyboardStatusTv = holder.itemView.findViewById(R.id.onboarding_permissions_keyboard_status_tv);
            Button microphoneBtn = holder.itemView.findViewById(R.id.onboarding_permissions_microphone_btn);
            Button keyboardBtn = holder.itemView.findViewById(R.id.onboarding_permissions_keyboard_btn);

            if (activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                microphoneStatusTv.setText(activity.getString(R.string.dictate_microphone_permission_granted));
                microphoneBtn.setEnabled(false);
            }

            microphoneBtn.setOnClickListener(v -> activity.requestPermissions(new String[]{ android.Manifest.permission.RECORD_AUDIO }, 1337));

            // F-092: POST_NOTIFICATIONS runtime prompt (API 33+). The
            // recording foreground-service notification carries the
            // Pause/Stop/Send/Cancel controls; on Android 13+ it is
            // silently suppressed until this grant is obtained. On
            // pre-33 devices the permission is implicit, so the row
            // shows as already granted and the button is disabled.
            TextView notificationsStatusTv = holder.itemView.findViewById(R.id.onboarding_permissions_notifications_status_tv);
            Button notificationsBtn = holder.itemView.findViewById(R.id.onboarding_permissions_notifications_btn);

            boolean notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                    || activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            if (notificationsGranted) {
                notificationsStatusTv.setText(activity.getString(R.string.dictate_notifications_permission_granted));
                notificationsBtn.setEnabled(false);
            }

            notificationsBtn.setOnClickListener(v -> {
                if (NotificationPermissionPolicy.INSTANCE.shouldRequestOnboarding(
                        Build.VERSION.SDK_INT,
                        activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)) {
                    activity.requestPermissions(new String[]{ android.Manifest.permission.POST_NOTIFICATIONS }, 1338);
                }
            });

            List<InputMethodInfo> inputMethodsList = ((InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE)).getEnabledInputMethodList();
            for (InputMethodInfo inputMethod : inputMethodsList) {
                if (inputMethod.getPackageName().equals(activity.getPackageName())) {
                    keyboardStatusTv.setText(activity.getString(R.string.dictate_keyboard_enabled));
                    keyboardBtn.setEnabled(false);
                }
            }

            keyboardBtn.setOnClickListener(v -> activity.startActivity(new Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS)));

            // Screen context — optional, so it sits below the divider and never
            // blocks onboarding. It routes through its own explainer Activity
            // rather than straight to the system switch: this is the one
            // permission here that sends other apps' content off-device, so it
            // needs a rationale the user can actually read, plus (on sideloaded
            // builds) the "Allow restricted settings" detour.
            TextView a11yStatusTv = holder.itemView.findViewById(R.id.onboarding_permissions_a11y_status_tv);
            Button a11yBtn = holder.itemView.findViewById(R.id.onboarding_permissions_a11y_btn);
            boolean a11yEnabled = A11yEnablementGate.isServiceEnabled(activity);
            a11yStatusTv.setText(activity.getString(a11yEnabled
                    ? R.string.dictate_a11y_state_enabled
                    : R.string.dictate_a11y_state_disabled));
            // Deliberately NOT disabled when enabled (unlike the buttons above):
            // this screen is also the way to review what is shared and turn it
            // back off, which stays useful after it is on.
            a11yBtn.setOnClickListener(v ->
                    activity.startActivity(new Intent(activity, A11yContextOnboardingActivity.class)));
        } else if (position == 2) {
            TextView requestApiKeyTv = holder.itemView.findViewById(R.id.onboarding_api_key_request_tv);
            EditText apiKeyEt = holder.itemView.findViewById(R.id.onboarding_api_key_et);
            Button finishBtn = holder.itemView.findViewById(R.id.onboarding_api_key_finish_btn);

            StringBuilder stringBuilder = new StringBuilder();
            BufferedReader reader = null;
            try {
                String suffix = DictateUtils.getAssetLanguageSuffix();
                reader = new BufferedReader(new InputStreamReader(
                        activity.getAssets().open("dictate_api_key_info_" + suffix + ".html")));
            } catch (IOException e) {
                try {
                    reader = new BufferedReader(new InputStreamReader(
                            activity.getAssets().open("dictate_api_key_info_en.html")));
                } catch (IOException fallbackException) {
                    e.printStackTrace();
                }
            }
            if (reader != null) {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stringBuilder.append(line);
                        stringBuilder.append("\n");
                    }
                    reader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            requestApiKeyTv.setMovementMethod(LinkMovementMethod.getInstance());
            requestApiKeyTv.setText(Html.fromHtml(stringBuilder.toString(), Html.FROM_HTML_MODE_LEGACY));

            apiKeyEt.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    finishBtn.setEnabled(s.toString().trim().length() >= 10);
                }

                @Override
                public void afterTextChanged(Editable s) { }
            });

            finishBtn.setOnClickListener(v -> new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.dictate_onboarding_complete_dialog_title)
                    .setMessage(R.string.dictate_onboarding_complete_dialog_message)
                    .setPositiveButton(R.string.dictate_okay, (dialog, which) -> {
                        SharedPreferences sp = activity.getSharedPreferences("net.devemperor.dictate", Context.MODE_PRIVATE);
                        String apiKey = apiKeyEt.getText().toString().trim();
                        // C3: the key becomes a credential + provider/model entities on the Default
                        // profile (SecretStore, F12) — a "gsk_" prefix selects Groq, else OpenAI.
                        ConfigEntitySetup.applyOnboardingKey(activity, sp, apiKey);
                        DictatePrefsKt.put(sp.edit(), Pref.OnboardingComplete.INSTANCE, true).apply();
                        activity.startActivity(new Intent(activity, DictateSettingsActivity.class));
                        activity.finish();
                    })
                    .show());
        }
    }

    @Override
    public int getItemCount() {
        return layoutIds.length;
    }

    @Override
    public int getItemViewType(int position) {
        return position;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public ViewHolder(@NonNull View itemView) {
            super(itemView);
        }
    }

}
