package com.example.wallettransactions.ui.account;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;
import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProviders;
import com.example.wallettransactions.Activities.AccountItemActivity;
import com.example.wallettransactions.Activities.TransactionActivity;
import com.example.wallettransactions.Adapters.AccountsAdapter;
import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.Accounts.AccountList;
import com.example.wallettransactions.DataItems.CurrencyConversion.Currency;
import com.example.wallettransactions.R;
import com.example.wallettransactions.Singleton;

import static android.app.Activity.RESULT_OK;

/**
 * A placeholder fragment containing a simple view.
 */

public class PlaceholderFragment extends Fragment implements Observer<CharSequence> {


    public enum AccountChooseMode {chooseAll, chooseOperation, chooseCharge, view}
    private AccountChooseMode accountChooseMode;

    public final static String cAccountId = "AccountActivity.AccountId";
    private static final String ARG_SECTION_NUMBER = "section_number";

    public static final int requestAccountItem = 1;

    private ListView lst;

    private AccountsAdapter curAdapter = null;
    private AccountsAdapter accountExpanseAdapter = null;
    private AccountsAdapter accountAdapter = null;
    private AccountsAdapter accountIncomeAdapter = null;

    public void notifyDataSetChanged(){
        curAdapter.notifyDataSetChanged();
    }

    private PageViewModel pageViewModel;

    public static PlaceholderFragment newInstance(int index,  AccountChooseMode accountChooseMode) {
        PlaceholderFragment fragment = new PlaceholderFragment();
        fragment.accountChooseMode = accountChooseMode;
        Bundle bundle = new Bundle();
        bundle.putInt(ARG_SECTION_NUMBER, index);
        fragment.setArguments(bundle);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pageViewModel = ViewModelProviders.of(this).get(PageViewModel.class);
        int index = 1;
        if (getArguments() != null) {
            index = getArguments().getInt(ARG_SECTION_NUMBER);
        }
        pageViewModel.setIndex(index);


    }

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_account, container, false);


        lst = root.findViewById(R.id.listView);
        lst.setOnItemClickListener(new AdapterView.OnItemClickListener()

        {
            @Override
            public void onItemClick (AdapterView < ? > parent, View itemClicked,int position, long id){
                AccountItem item = (AccountItem) parent.getItemAtPosition(position);

                if (accountChooseMode == AccountChooseMode.view) {
                    Intent intent = new Intent(getContext(), AccountItemActivity.class);
                    intent.putExtra(AccountItemActivity.ARG_ACCOUNT_ID, item.getId());
                    getActivity().startActivityForResult(intent, requestAccountItem);

                } else {

                    Intent answerIntent = new Intent();
                    answerIntent.putExtra(cAccountId, item.getId());

                    getActivity().setResult(RESULT_OK, answerIntent);
                    getActivity().finish();
                }

            }
        });

        AccountList accountList = Singleton.instance.getAccounts(getContext());
        accountList.onListChanged.add(this::notifyDataSetChanged);

        // Get currency equivalent for display (default to first currency if available)
        Currency currencyEq = null;
        try {
            currencyEq = Singleton.instance.getCurrencyList(getContext()).get(0);
        } catch (Exception e) {
            // No currency available
        }

        accountAdapter = new AccountsAdapter(root.getContext(),
                                             accountList.getFilteredOperationAccounts(),
                                             Singleton.instance.getIconList(getContext()),
                                             currencyEq);
        accountExpanseAdapter = new AccountsAdapter(root.getContext(),
                                                    accountList.getExpanseAccounts(),
                                                    Singleton.instance.getIconList(getContext()),
                                                    currencyEq);
        accountIncomeAdapter = new AccountsAdapter(root.getContext(),
                                                   accountList.getIncomeAccounts(),
                                                   Singleton.instance.getIconList(getContext()),
                                                   currencyEq);
        curAdapter = accountExpanseAdapter;
        assignCurAdapter();

        pageViewModel.changePage().observe(this, new Observer<PageViewModel.page_id>() {
            @Override
            public void onChanged(@Nullable PageViewModel.page_id s) {
                switch (s)
                {
                    case expanse:
                        curAdapter = accountExpanseAdapter;
                        break;
                    case operating:
                        curAdapter = accountAdapter;
                        break;
                    case income:
                        curAdapter = accountIncomeAdapter;
                        break;
                }
                assignCurAdapter();
            }
        });



        return root;
    }

    private void assignCurAdapter()
    {
        lst.setAdapter(curAdapter);
    }

    @Override
    public void onChanged(@Nullable CharSequence f) {
        curAdapter.getFilter().filter(f);

    }
}