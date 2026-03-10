package net.devemperor.dictate;

import android.app.Application;

import net.devemperor.dictate.preferences.PrefsMigration;

public class DictateApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        PrefsMigration.migrateProviderPrefs(getSharedPreferences("net.devemperor.dictate", MODE_PRIVATE));
        DictateUtils.applyApplicationLocale(this);
    }
}
