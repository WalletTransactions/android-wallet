package com.example.wallettransactions.ui.account;

import androidx.lifecycle.LiveData;
import android.content.Context;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import com.example.wallettransactions.R;

/**
 * A [FragmentPagerAdapter] that returns a fragment corresponding to
 * one of the sections/tabs/pages.
 */
public class SectionsPagerAdapter extends FragmentPagerAdapter {

    private PlaceholderFragment.AccountChooseMode accountChooseMode;

    @StringRes
    private static final int[] TAB_TITLES = new int[] {R.string.tab_account_expanse, R.string.tab_account_operating, R.string.tab_account_income};
    private final Context mContext;

    private LiveData<CharSequence> mFilterChange;

    public SectionsPagerAdapter(Context context, FragmentManager fm, LiveData<CharSequence> filterChange, PlaceholderFragment.AccountChooseMode accountChooseMode) {
        super(fm);
        mFilterChange = filterChange;
        mContext = context;
        this.accountChooseMode = accountChooseMode;
    }

    @Override
    public Fragment getItem(int position) {
        PlaceholderFragment placeholderFragment = PlaceholderFragment.newInstance(position + 1, accountChooseMode);
        mFilterChange.observe(placeholderFragment, placeholderFragment);
        return placeholderFragment;
    }

    @Nullable
    @Override
    public CharSequence getPageTitle(int position) {
        return mContext.getResources().getString(TAB_TITLES[position]);
    }

    @Override
    public int getCount() {
        return 3;
    }

}