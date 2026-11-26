package com.example.wallettransactions.Activities.Fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.fragment.app.Fragment
import com.example.wallettransactions.Activities.CurrencyPairDetailActivity
import com.example.wallettransactions.Activities.CurrencyPairListActivity
import com.example.wallettransactions.DataItems.CurrencyConversion.CurrencyPair
import com.example.wallettransactions.R
import com.google.android.material.appbar.CollapsingToolbarLayout

/**
 * A fragment representing a single CurrencyPair detail screen.
 * This fragment is either contained in a [CurrencyPairListActivity]
 * in two-pane mode (on tablets) or a [CurrencyPairDetailActivity]
 * on handsets.
 */
class CurrencyPairDetailFragment : Fragment() {
    /**
     * The fragment argument representing the item ID that this fragment
     * represents.
     */
    private var lst: ListView? = null

    /**
     * The dummy content this fragment is presenting.
     */
    private var mItem: CurrencyPair? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (arguments!!.containsKey(ARG_ITEM_ID)) {
            // Load the dummy content specified by the fragment
            // arguments. In a real-world scenario, use a Loader
            // to load content from a content provider.
            //mItem =   DummyContent.ITEM_MAP.get(getArguments().getInt(ARG_ITEM_ID));

            val activity = this.activity
            val appBarLayout = activity!!.findViewById<CollapsingToolbarLayout?>(R.id.toolbar_layout)
            if (appBarLayout != null) {
                appBarLayout.title = "Dummy stuff" //mItem.getSymbol());
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val rootView = inflater.inflate(R.layout.currencypair_detail, container, false)

        // Show the dummy content as text in a TextView.
        /*   if (mItem != null) {
               ((ListView) rootView.findViewById(R.id.currencypair_rates)).setText("Dummy stuff details");// mItem.details);
           }*/

        val list: ListView = rootView.findViewById(R.id.currencypair_rates)
        lst = list
        val matrix = ArrayList<String>()
        matrix.add("213123")
        matrix.add("213123dsfa")
        matrix.add("dsaf213123")

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, matrix)

        list.adapter = adapter

        return rootView
    }

    companion object {
        const val ARG_ITEM_ID = "item_id"
    }
}
