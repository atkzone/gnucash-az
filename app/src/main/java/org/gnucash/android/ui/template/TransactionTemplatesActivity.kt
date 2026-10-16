package org.gnucash.android.ui.template

import android.content.Context
import android.content.Intent
import android.os.Bundle
import org.gnucash.android.R
import org.gnucash.android.databinding.ActivityTransactionTemplatesBinding
import org.gnucash.android.ui.common.BaseDrawerActivity

/**
 * Host for the saved-transaction-template list (feature ①d).
 *
 * Mirrors the other drawer-hosting list screens (e.g. `ScheduledActionsActivity`):
 * a single fragment inside [R.id.fragment_container], with the navigation drawer
 * and toolbar supplied by [BaseDrawerActivity].
 */
class TransactionTemplatesActivity : BaseDrawerActivity() {

    private lateinit var binding: ActivityTransactionTemplatesBinding

    override val titleRes: Int = R.string.title_transaction_templates

    override fun inflateView() {
        binding = ActivityTransactionTemplatesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        drawerLayout = binding.drawerLayout
        navigationView = binding.navView
        toolbar = binding.toolbarLayout.toolbar
        toolbarProgress = binding.toolbarLayout.toolbarProgress.progress
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, TransactionTemplatesFragment())
                .commit()
        }
    }

    companion object {
        @JvmStatic
        fun show(context: Context) {
            context.startActivity(Intent(context, TransactionTemplatesActivity::class.java))
        }
    }
}
