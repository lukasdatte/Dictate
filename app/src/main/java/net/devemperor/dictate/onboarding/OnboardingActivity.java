package net.devemperor.dictate.onboarding;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import net.devemperor.dictate.R;

import java.util.List;

public class OnboardingActivity extends AppCompatActivity {

    OnboardingAdapter onboardingAdapter;
    ViewPager2 onboardingVp;
    TabLayout onboardingTl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_onboarding);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.activity_onboarding), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        onboardingAdapter = new OnboardingAdapter(this, new int[]{
                R.layout.viewpager_welcome,
                R.layout.viewpager_permissions,
                R.layout.viewpager_api_key
        });

        onboardingVp = findViewById(R.id.onboarding_viewpager);
        onboardingTl = findViewById(R.id.onboarding_tablayout);
        onboardingVp.setAdapter(onboardingAdapter);
        TabLayoutMediator tabLayoutMediator = new TabLayoutMediator(onboardingTl, onboardingVp, true,
                (tab, position) -> { }
        );
        tabLayoutMediator.attach();
    }

    // Refreshes the permissions page after the user comes back from a system
    // settings screen. Unconditional rebind: none of the settings this page
    // mirrors (keyboard, accessibility) report back via a result callback, so
    // re-reading on resume is the only way to learn the outcome. This used to
    // rebind only when the keyboard had been enabled, which meant a user who
    // returned having enabled screen context (or nothing) kept looking at a
    // stale status line.
    @Override
    public void onResume() {
        super.onResume();
        onboardingAdapter.notifyItemChanged(1);
    }

    // refresh the permissions page after a microphone (1337) or
    // notifications (1338, F-092) grant so the status text + button
    // enablement reflect the new state
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1337 || requestCode == 1338) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                onboardingAdapter.notifyItemChanged(1);
            }
        }
    }
}