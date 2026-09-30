package com.example.wallettransactions.Activities.Fragments;

import android.app.Activity;
import android.os.Bundle;
import com.google.android.material.appbar.CollapsingToolbarLayout;
import androidx.fragment.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import com.example.wallettransactions.Activities.CurrencyPairDetailActivity;
import com.example.wallettransactions.Activities.CurrencyPairListActivity;
import com.example.wallettransactions.Activities.MainActivity;
import com.example.wallettransactions.Adapters.ReportByAccountsAdapter;
import com.example.wallettransactions.DataItems.Accounts.AccountItem;
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPair;
import com.example.wallettransactions.DataItems.IconList;
import com.example.wallettransactions.R;

import java.util.ArrayList;

/**
 * A fragment representing a single CurrencyPair detail screen.
 * This fragment is either contained in a {@link CurrencyPairListActivity}
 * in two-pane mode (on tablets) or a {@link CurrencyPairDetailActivity}
 * on handsets.
 */
public class CurrencyPairDetailFragment extends Fragment {
    /**
     * The fragment argument representing the item ID that this fragment
     * represents.
     */
    private ListView lst;
    public static final String ARG_ITEM_ID = "item_id";

    /**
     * The dummy content this fragment is presenting.
     */
    private CurrencyPair mItem;


    /**
     * Mandatory empty constructor for the fragment manager to instantiate the
     * fragment (e.g. upon screen orientation changes).
     */
    public CurrencyPairDetailFragment() {

    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getArguments().containsKey(ARG_ITEM_ID)) {
            // Load the dummy content specified by the fragment
            // arguments. In a real-world scenario, use a Loader
            // to load content from a content provider.
            //mItem =   DummyContent.ITEM_MAP.get(getArguments().getInt(ARG_ITEM_ID));

            Activity activity = this.getActivity();
            CollapsingToolbarLayout appBarLayout = (CollapsingToolbarLayout) activity.findViewById(R.id.toolbar_layout);
            if (appBarLayout != null) {
                appBarLayout.setTitle("Dummy stuff"); //mItem.getSymbol());
            }
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.currencypair_detail, container, false);

        // Show the dummy content as text in a TextView.
     /*   if (mItem != null) {
            ((ListView) rootView.findViewById(R.id.currencypair_rates)).setText("Dummy stuff details");// mItem.details);
        }*/

        lst = rootView.findViewById(R.id.currencypair_rates);
        ArrayList<String> matrix  = new ArrayList<String> ();
        matrix.add("213123");
        matrix.add("213123dsfa");
        matrix.add("dsaf213123");


        ArrayAdapter<String> adapter = new ArrayAdapter<String>(getContext(), android.R.layout.simple_list_item_1, matrix);

        lst.setAdapter(adapter);

        return rootView;
    }
}