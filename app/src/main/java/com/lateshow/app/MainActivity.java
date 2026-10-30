package com.lateshow.app;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import com.lateshow.app.store.PrefStore;
import com.lateshow.app.ui.FavFragment;
import com.lateshow.app.ui.HomeFragment;
import com.lateshow.app.ui.SettingsFragment;

public class MainActivity extends AppCompatActivity {

    private HomeFragment homeFragment;
    private FavFragment favFragment;
    private SettingsFragment settingsFragment;
    private Fragment active;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        PrefStore.init(this);
        applyTheme();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        homeFragment = new HomeFragment();
        favFragment = new FavFragment();
        settingsFragment = new SettingsFragment();

        findViewById(R.id.tab_home).setOnClickListener(v -> switchTab(homeFragment, 0));
        findViewById(R.id.tab_fav).setOnClickListener(v -> switchTab(favFragment, 1));
        findViewById(R.id.tab_settings).setOnClickListener(v -> switchTab(settingsFragment, 2));

        switchTab(homeFragment, 0);
    }

    public void applyTheme() {
        int t = PrefStore.get().theme();
        AppCompatDelegate.setDefaultNightMode(
                t == PrefStore.THEME_LIGHT ? AppCompatDelegate.MODE_NIGHT_NO
                : t == PrefStore.THEME_AUTO ? AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                : AppCompatDelegate.MODE_NIGHT_YES);
    }

    private void switchTab(Fragment target, int tab) {
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction tx = fm.beginTransaction();
        for (Fragment f : fm.getFragments()) tx.hide(f);
        if (target.isAdded()) tx.show(target);
        else tx.add(R.id.container, target);
        tx.commit();
        active = target;
        tintTabs(tab);
    }

    private void tintTabs(int active) {
        int on = getColor(R.color.acc), off = getColor(R.color.t2);
        setTab(R.id.tab_home_icon, R.id.tab_home_label, active == 0, on, off);
        setTab(R.id.tab_fav_icon, R.id.tab_fav_label, active == 1, on, off);
        setTab(R.id.tab_settings_icon, R.id.tab_settings_label, active == 2, on, off);
    }

    private void setTab(int iconId, int labelId, boolean sel, int on, int off) {
        androidx.appcompat.widget.AppCompatImageView icon = findViewById(iconId);
        android.widget.TextView label = findViewById(labelId);
        icon.setColorFilter(sel ? on : off);
        label.setTextColor(sel ? on : off);
    }

    public void goHome() {
        switchTab(homeFragment, 0);
    }
}
