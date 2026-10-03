package app.phoneledger.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A separate-package form used only for physical-device autofill verification. */
public final class FixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("Phone Ledger autofill test");
        title.setTextSize(24);
        root.addView(title);

        EditText fullPhone = new EditText(this);
        fullPhone.setHint("Phone number");
        fullPhone.setInputType(InputType.TYPE_CLASS_PHONE);
        fullPhone.setAutofillHints(View.AUTOFILL_HINT_PHONE);
        root.addView(fullPhone, matchWrap());

        EditText mobile = new EditText(this);
        mobile.setHint("Mobile contact number");
        mobile.setInputType(InputType.TYPE_CLASS_TEXT);
        root.addView(mobile, matchWrap());

        EditText otp = new EditText(this);
        otp.setHint("SMS verification code (must not trigger phone autofill)");
        otp.setInputType(InputType.TYPE_CLASS_PHONE);
        otp.setAutofillHints("smsOTPCode");
        root.addView(otp, matchWrap());

        setContentView(root);
        fullPhone.requestFocus();
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
