package io.github.yy7511.locallutbox;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;

import io.github.yy7511.locallutbox.core.Store;
import io.github.yy7511.locallutbox.ui.Ui;

/**
 * Applies the stored light/dark preference: forces uiMode so the night-qualified
 * colour resources match, picks the matching platform theme and syncs Ui's palette.
 */
public abstract class BaseActivity extends Activity {

    protected boolean darkMode;

    @Override
    protected void attachBaseContext(Context base) {
        darkMode = Store.darkMode(base);
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (darkMode ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        super.attachBaseContext(base.createConfigurationContext(cfg));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(darkMode ? R.style.AppTheme_Dark : R.style.AppTheme);
        Ui.applyPalette(darkMode);
        super.onCreate(savedInstanceState);
    }
}
