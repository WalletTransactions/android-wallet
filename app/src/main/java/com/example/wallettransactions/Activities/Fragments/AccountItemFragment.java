package com.example.wallettransactions.Activities.Fragments;

import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import com.example.wallettransactions.Activities.AccountPickerActivity;
import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;

import java.util.Date;

import static android.app.Activity.RESULT_OK;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link AccountItemFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class AccountItemFragment extends Fragment {


    public interface OnAccountChangedListener {
        void onAccountChanged(AccountItemFragment view, AccountItem accountItem);
    }

    private static final String ARG_ACCOUNT_ID = "AccountId";
    private static final String ARG_ACCOUNT_FRAME_ID = "AccountFrameId";

    private AccountItem mAccount;

    private OnAccountChangedListener mOnAccountChangedListener;


    private ImageView imgAccount;
    private EditText editAccount;
    private Button buttonAccount;

    private int accountFrameId;

    public AccountItemFragment() {
        // Required empty public constructor
    }

    public static AccountItemFragment newInstance(int accountFrameId, int accountId) {
        AccountItemFragment fragment = new AccountItemFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_ACCOUNT_ID, accountId);
        args.putInt(ARG_ACCOUNT_FRAME_ID, accountFrameId);
        fragment.setArguments(args);
        return fragment;
    }

    private void assignComponents(View rootView)
    {
        imgAccount = rootView.findViewById(R.id.imgAccount);
        editAccount = rootView.findViewById(R.id.editAccount);
        buttonAccount = rootView.findViewById(R.id.buttonAccount);

        imgAccount.setOnClickListener(this::buttonAccountClick);
        editAccount.setOnClickListener(this::buttonAccountClick);
        buttonAccount.setOnClickListener(this::buttonAccountClick);
        editAccount.setFocusable(false);
    }

    public void setOnAccountChangedListener(@Nullable OnAccountChangedListener l) {
        mOnAccountChangedListener = l;
    }

    public void doAccountChanged() {
        if (mOnAccountChangedListener != null)
            mOnAccountChangedListener.onAccountChanged(this, mAccount);
    }

    public void clearAccount(){
        mAccount = null;
        drawAccount();
        doAccountChanged();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            int accountId = getArguments().getInt(ARG_ACCOUNT_ID);

            mAccount = Singleton.instance.getAccounts(getContext()).getAccountById(accountId);
            accountFrameId = getArguments().getInt(ARG_ACCOUNT_FRAME_ID);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View rootView = inflater.inflate(R.layout.fragment_account_item, container, false);
        assignComponents(rootView);
        return  rootView;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == RESULT_OK) {
            int accountId = data.getIntExtra(AccountPickerActivity.cAccountId, -1);

            mAccount =  Singleton.instance.getAccounts(getContext()).getAccountById(accountId);
            drawAccount();
            doAccountChanged();
        }
    }

    protected void drawAccount(){
        if ( mAccount == null ) {
            imgAccount.setImageBitmap(Singleton.instance.getIconList(getContext()).getUnknownIcon().getBitmap());
            editAccount.setText("");
        }
        else
        {
            imgAccount.setImageBitmap(mAccount.getIcon().getBitmap());
            editAccount.setText(mAccount.getName());
        }
    }


    public void buttonAccountClick(View view) {
        startActivityForResult(AccountPickerActivity.buildIntent(getContext(), "Кошелёк",
                AccountPickerActivity.cSelectedTypeOperationAccounts,
                mAccount == null ? 0 : mAccount.getId()), accountFrameId);
    }
}