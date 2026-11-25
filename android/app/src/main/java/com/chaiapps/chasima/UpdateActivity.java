package com.chaiapps.chasima;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

/** נפתח מהתראת "יש גרסה חדשה": מוריד ומתקין מיד. */
public class UpdateActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout l = Ui.screen(this);
        Ui.text(this, l, "🆕 עדכון גרסה", 26, Color.WHITE, true);
        Ui.text(this, l, "גרסה נוכחית: " + BuildConfig.VERSION_NAME, 14, 0xCCFFFFFF, false);
        final TextView st = Ui.text(this, l, "⏳ מתחיל…", 18, 0xFFFDE68A, true);
        Ui.text(this, l, "בסוף ההורדה יקפוץ חלון – לוחצים \"התקן\".", 15, 0xCCFFFFFF, false);
        Ui.button(this, l, "🔄 נסה שוב", Ui.BLUE, v -> Updater.run(this, st::setText));
        Ui.button(this, l, "סגור", 0x55FFFFFF, v -> finish());
        Updater.run(this, st::setText);
    }
}
