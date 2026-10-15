package mx.coberturapunto;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

/** One-time, native start-page setup; never opens a provider before the user saves a link. */
public final class BookmarkActivity extends Activity {
    private EditText link;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String saved = MapBookmark.get(this);
        if (!saved.isEmpty() && !getIntent().getBooleanExtra("edit", false)) {
            openMap();
            return;
        }
        boolean dark = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int background = dark ? 0xff11171c : 0xfff7f9fc;
        int ink = dark ? 0xffe1e9ef : 0xff17232c;
        int surface = dark ? 0xff242e36 : 0xffe8eef4;
        int accent = dark ? 0xff9acdf1 : 0xff005f88;
        int onAccent = dark ? 0xff00344d : Color.WHITE;
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            accent = getColor(dark ? android.R.color.system_accent1_200 : android.R.color.system_accent1_700);
            onAccent = getColor(dark ? android.R.color.system_accent1_900 : android.R.color.system_accent1_0);
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(24) + insets.getSystemWindowInsetLeft(), dp(24) + insets.getSystemWindowInsetTop(),
                    dp(24) + insets.getSystemWindowInsetRight(), dp(24) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        TextView brand = new TextView(this);
        brand.setText("GeoPin Pro"); brand.setTextColor(accent); brand.setTextSize(16);
        root.addView(brand);
        TextView title = new TextView(this);
        title.setText(saved.isEmpty() ? "Añade tu mapa" : "Enlace del mapa");
        title.setTextColor(ink); title.setTextSize(30); title.setPadding(0, dp(20), 0, dp(12));
        root.addView(title);
        TextView description = new TextView(this);
        description.setText("Pega el enlace del mapa que quieres usar. Se guardará y se abrirá automáticamente cada vez que entres.");
        description.setTextColor(ink); description.setTextSize(16);
        root.addView(description);
        link = new EditText(this);
        link.setId(R.id.bookmark_link); link.setTextColor(ink); link.setTextSize(16);
        link.setHint("https://…"); link.setHintTextColor(dark ? 0xffa9b5be : 0xff596873);
        link.setContentDescription("Enlace del mapa"); link.setSingleLine(true);
        link.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        link.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        link.setBackground(NativeUi.rounded(surface, dp(16)));
        link.setPadding(dp(16), dp(14), dp(16), dp(14));
        link.setText(saved);
        LinearLayout.LayoutParams field = new LinearLayout.LayoutParams(-1, -2);
        field.topMargin = dp(28); field.bottomMargin = dp(20);
        root.addView(link, field);
        Button save = new Button(this);
        save.setText("Guardar y abrir");
        NativeUi.button(save, accent, onAccent, 0, getResources().getDisplayMetrics().density);
        save.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null);
        save.setTextSize(16);
        root.addView(save, new LinearLayout.LayoutParams(-1, dp(54)));
        save.setOnClickListener(v -> save());
        link.setOnEditorActionListener((v, action, event) -> {
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { save(); return true; }
            return false;
        });
        TextView note = new TextView(this);
        note.setText("Puedes cambiarlo después en el menú del mapa. El sitio que añadas pertenece a su proveedor.");
        note.setTextColor(ink); note.setTextSize(13); note.setPadding(0, dp(20), 0, 0);
        root.addView(note);
        root.setFocusableInTouchMode(true); root.requestFocus();
    }

    private void save() {
        try {
            String value = MapBookmark.normalize(link.getText().toString());
            if (!MapBookmark.save(this, value)) {
                link.setError("No se pudo guardar. Inténtalo otra vez.");
                return;
            }
            openMap();
        } catch (IllegalArgumentException e) { link.setError(e.getMessage()); link.requestFocus(); }
    }

    private void openMap() {
        Intent next = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Intent original = getIntent().getParcelableExtra("pending_intent");
        if (original != null) {
            next.setAction(original.getAction()); next.setData(original.getData());
            if (Intent.ACTION_SEND.equals(original.getAction())) {
                next.setType("text/plain"); next.putExtra(Intent.EXTRA_TEXT, original.getStringExtra(Intent.EXTRA_TEXT));
            }
        }
        startActivity(next); finish();
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
