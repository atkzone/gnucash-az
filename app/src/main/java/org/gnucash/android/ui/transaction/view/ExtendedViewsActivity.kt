package org.gnucash.android.ui.transaction.view

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import org.gnucash.android.R
import org.gnucash.android.databinding.ActivityExtendedViewsBinding
import org.gnucash.android.ui.passcode.PasscodeLockActivity

/**
 * Host for "设置扩展显示" — the screens where the named extended views are defined.
 *
 * A plain tool-bar screen rather than a drawer one: it is reached from a first-level
 * item of the transaction list's overflow menu and is a sub-screen of that list, so
 * putting a second navigation drawer under it would offer a second way to the
 * same places. It does extend [PasscodeLockActivity] so the passcode lock and the
 * theme helper still apply here.
 */
class ExtendedViewsActivity : PasscodeLockActivity() {

    private lateinit var binding: ActivityExtendedViewsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExtendedViewsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.apply {
            setHomeButtonEnabled(true)
            setDisplayHomeAsUpEnabled(true)
            // Same wording as the menu entry that opens it, so the screen the user
            // lands on is recognisably the one they tapped.
            setTitle(R.string.menu_extended_view_settings)
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, ExtendedViewsFragment())
                .commit()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        @JvmStatic
        fun show(context: Context) {
            context.startActivity(Intent(context, ExtendedViewsActivity::class.java))
        }
    }
}
