package com.joz.tvrelay;

import android.app.Activity;
import android.content.Intent;
import android.widget.RadioButton;
import android.widget.RadioGroup;

/**
 * Advanced mode's "TV mode / Client mode" choice, for devices Android guesses wrong. Shown on the
 * TV screen and the phone's Listen tab; picking the other mode switches to that screen right away.
 */
final class ModeSwitch {
    static RadioGroup build(Activity activity, float textSp) {
        RadioGroup group = new RadioGroup(activity);
        group.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton tv = new RadioButton(activity);
        tv.setId(R.id.modeTv);
        tv.setText("TV mode");
        tv.setTextSize(textSp);
        RadioButton client = new RadioButton(activity);
        client.setId(R.id.modeClient);
        client.setText("Client mode");
        client.setTextSize(textSp);
        group.addView(tv);
        group.addView(client);
        boolean onTv = activity instanceof TvActivity;
        group.check(onTv ? R.id.modeTv : R.id.modeClient);
        group.setOnCheckedChangeListener((g, id) -> {
            boolean wantTv = id == R.id.modeTv;
            if (wantTv == onTv) return;
            Settings s = Settings.load(activity);
            s.screenMode = wantTv ? Settings.SCREEN_TV : Settings.SCREEN_CLIENT;
            s.save(activity);
            activity.startActivity(new Intent(activity, wantTv ? TvActivity.class : MainActivity.class));
            activity.finish();
        });
        return group;
    }

    private ModeSwitch() {}
}
