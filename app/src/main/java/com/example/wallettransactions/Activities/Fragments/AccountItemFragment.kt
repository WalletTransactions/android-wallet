package com.example.wallettransactions.Activities.Fragments

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import androidx.fragment.app.Fragment
import com.example.wallettransactions.Activities.AccountPickerActivity
import com.example.wallettransactions.DataItems.Accounts.AccountItem
import com.example.wallettransactions.R
import com.example.wallettransactions.Singleton

/**
 * A simple [Fragment] subclass.
 * Use the [AccountItemFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class AccountItemFragment : Fragment() {

    fun interface OnAccountChangedListener {
        fun onAccountChanged(view: AccountItemFragment, accountItem: AccountItem?)
    }

    private var mAccount: AccountItem? = null

    private var mOnAccountChangedListener: OnAccountChangedListener? = null

    private lateinit var imgAccount: ImageView
    private lateinit var editAccount: EditText
    private lateinit var buttonAccount: Button

    private var accountFrameId = 0

    private fun assignComponents(rootView: View) {
        imgAccount = rootView.findViewById(R.id.imgAccount)
        editAccount = rootView.findViewById(R.id.editAccount)
        buttonAccount = rootView.findViewById(R.id.buttonAccount)

        imgAccount.setOnClickListener { buttonAccountClick(it) }
        editAccount.setOnClickListener { buttonAccountClick(it) }
        buttonAccount.setOnClickListener { buttonAccountClick(it) }
        editAccount.isFocusable = false
    }

    fun setOnAccountChangedListener(l: OnAccountChangedListener?) {
        mOnAccountChangedListener = l
    }

    fun doAccountChanged() {
        mOnAccountChangedListener?.onAccountChanged(this, mAccount)
    }

    fun clearAccount() {
        mAccount = null
        drawAccount()
        doAccountChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = arguments
        if (args != null) {
            val accountId = args.getInt(ARG_ACCOUNT_ID)

            mAccount = Singleton.instance.getAccounts(requireContext()).getAccountById(accountId)
            accountFrameId = args.getInt(ARG_ACCOUNT_FRAME_ID)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate the layout for this fragment
        val rootView = inflater.inflate(R.layout.fragment_account_item, container, false)
        assignComponents(rootView)
        return rootView
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode == RESULT_OK) {
            val accountId = data!!.getIntExtra(AccountPickerActivity.cAccountId, -1)

            mAccount = Singleton.instance.getAccounts(requireContext()).getAccountById(accountId)
            drawAccount()
            doAccountChanged()
        }
    }

    protected fun drawAccount() {
        val account = mAccount
        if (account == null) {
            imgAccount.setImageBitmap(Singleton.instance.getIconList(requireContext()).unknownIcon!!.bitmap)
            editAccount.setText("")
        } else {
            imgAccount.setImageBitmap(account.icon!!.bitmap)
            editAccount.setText(account.name)
        }
    }

    fun buttonAccountClick(view: View) {
        startActivityForResult(
            AccountPickerActivity.buildIntent(
                requireContext(), getString(R.string.operation_slot_wallet),
                AccountPickerActivity.cSelectedTypeOperationAccounts,
                mAccount?.id ?: 0
            ), accountFrameId
        )
    }

    companion object {
        private const val ARG_ACCOUNT_ID = "AccountId"
        private const val ARG_ACCOUNT_FRAME_ID = "AccountFrameId"

        @JvmStatic
        fun newInstance(accountFrameId: Int, accountId: Int): AccountItemFragment {
            val fragment = AccountItemFragment()
            val args = Bundle()
            args.putInt(ARG_ACCOUNT_ID, accountId)
            args.putInt(ARG_ACCOUNT_FRAME_ID, accountFrameId)
            fragment.arguments = args
            return fragment
        }
    }
}
