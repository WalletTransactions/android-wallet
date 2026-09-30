package com.example.wallettransactions.ui.account;

import androidx.arch.core.util.Function;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;

public class PageViewModel extends ViewModel {

    public enum page_id {expanse, operating, income}

    private MutableLiveData<Integer> mIndex = new MutableLiveData<>();
    private LiveData<page_id> mChangePage = Transformations.map(mIndex, new Function<Integer, page_id>() {
        @Override
        public page_id apply(Integer input) {
            switch (input) {
                case 1: return page_id.expanse;
                case 2: return page_id.operating;
                default: return page_id.income;
            }
        };
    });

    public void setIndex(int index) {
        mIndex.setValue(index);
    }

    public LiveData<page_id> changePage() {
        return mChangePage;
    }
}