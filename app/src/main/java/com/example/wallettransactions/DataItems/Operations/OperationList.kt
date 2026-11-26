package com.example.wallettransactions.DataItems.Operations

class OperationList : ArrayList<OperationItem>() {

    @JvmField
    var onListChanged: MutableList<ListChanged> = ArrayList()

    fun notifyObservers() {
        for (itm in onListChanged) {
            itm.notifyDataSetChanged()
        }
    }


}
