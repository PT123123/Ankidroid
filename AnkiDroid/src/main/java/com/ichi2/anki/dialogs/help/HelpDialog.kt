// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.dialogs.help

import android.app.Dialog
import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.core.os.BundleCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.ichi2.anki.R
import com.ichi2.anki.analytics.AnalyticsDialogFragment
import com.ichi2.anki.ankiActivity
import com.ichi2.anki.common.analytics.Analytics
import com.ichi2.anki.common.analytics.AnalyticsEvent.LinkClicked
import com.ichi2.anki.common.analytics.LinkAction
import com.ichi2.anki.databinding.DialogHelpBinding
import com.ichi2.anki.databinding.FragmentHelpPageBinding
import com.ichi2.anki.databinding.ItemHelpEntryBinding
import com.ichi2.anki.dialogs.help.HelpItem.Action.OpenUrl
import com.ichi2.anki.dialogs.help.HelpItem.Action.OpenUrlResource
import com.ichi2.anki.dialogs.help.HelpItem.Action.Rate
import com.ichi2.anki.utils.ext.setCompoundDrawablesRelativeWithIntrinsicBoundsKt
import com.ichi2.utils.createAndApply
import com.ichi2.utils.customView
import com.ichi2.utils.dp
import com.ichi2.utils.title
import dev.androidbroadcast.vbpd.viewBinding

/** [DialogFragment] responsible for showing the help menu. */
class HelpDialog : AnalyticsDialogFragment() {
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    lateinit var actionsDispatcher: HelpItemActionsDispatcher

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogHelpBinding.inflate(requireActivity().layoutInflater)
        ankiActivity?.let { ankiActivity ->
            actionsDispatcher = AnkiActivityHelpActionsDispatcher(ankiActivity)
        }
        childFragmentManager.setFragmentResultListener(
            REQUEST_HELP_PAGE,
            this,
        ) { _, arguments -> handleItemSelection(arguments) }
        return AlertDialog
            .Builder(requireContext())
            .title(requireArguments().getInt(ARG_MENU_TITLE))
            .customView(binding.root)
            .createAndApply {
                // the dialog captures the BACK call so we manually pop the inner FragmentManager
                // if there's a second page
                onBackPressedDispatcher.addCallback(this@HelpDialog, true) {
                    if (childFragmentManager.backStackEntryCount > 1) {
                        childFragmentManager.popBackStack()
                    } else {
                        dismiss()
                    }
                }
                setOnShowListener {
                    // if there's no fragment added this is a fresh start so add the initial page from arguments
                    if (childFragmentManager.findFragmentByTag(PAGE_TAG) == null) {
                        newHelpPage(requireArgsHelpEntries())
                    }
                }
            }
    }

    private fun handleItemSelection(from: Bundle) {
        from.classLoader = javaClass.classLoader
        val selectedItem =
            BundleCompat.getParcelable(
                from,
                ARG_SELECTED_MENU_ITEM,
                HelpItem::class.java,
            ) ?: return
        when (selectedItem.action) {
            is OpenUrl -> actionsDispatcher.onOpenUrl(selectedItem.action.url)
            is OpenUrlResource -> actionsDispatcher.onOpenUrlResource(selectedItem.action.urlResourceId)
            Rate -> actionsDispatcher.onRate()
            null -> {
                // there's no action so check if the selected item has children to show
                val children = childHelpMenuItems.filter { it.parentId == selectedItem.id }
                if (children.isNotEmpty()) {
                    newHelpPage(children.toTypedArray())
                }
            }
        }
    }

    private fun newHelpPage(items: Array<HelpItem>) {
        val menuPage =
            HelpPageFragment().apply {
                arguments = Bundle().apply { putParcelableArray(ARG_MENU_ITEMS, items) }
            }
        childFragmentManager.commit {
            replace(R.id.fragment_container, menuPage, PAGE_TAG).addToBackStack(null)
        }
    }

    companion object {
        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        const val ARG_MENU_TITLE = "arg_menu_title"
        private const val PAGE_TAG = "HelpMenuPage"

        fun newHelpInstance(): HelpDialog {
            Analytics.send(LinkClicked(LinkAction.OPENED_HELP_DIALOG))
            return HelpDialog().apply {
                arguments =
                    Bundle().apply {
                        putInt(ARG_MENU_TITLE, R.string.help)
                        putParcelableArray(ARG_MENU_ITEMS, mainHelpMenuItems)
                    }
            }
        }

        fun newPrivacyPolicyInstance(): HelpDialog {
            Analytics.send(LinkClicked(LinkAction.OPENED_PRIVACY))
            val privacyId = mainHelpMenuItems.single { it.analyticsId == LinkAction.OPENED_PRIVACY }.id
            val privacyItems = childHelpMenuItems.filter { it.parentId == privacyId }
            return HelpDialog().apply {
                arguments =
                    Bundle().apply {
                        putInt(ARG_MENU_TITLE, R.string.help_title_privacy)
                        putParcelableArray(ARG_MENU_ITEMS, privacyItems.toTypedArray())
                    }
            }
        }
    }
}

@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal fun Fragment.requireArgsHelpEntries(): Array<HelpItem> {
    requireArguments().classLoader = javaClass.classLoader
    val retrievedItems =
        BundleCompat.getParcelableArray(
            requireArguments(),
            ARG_MENU_ITEMS,
            HelpItem::class.java,
        ) ?: error("Unable to retrieve current help menu items")
    return retrievedItems.map { it as HelpItem }.toTypedArray()
}

internal const val ARG_MENU_ITEMS = "arg_menu_items"
internal const val REQUEST_HELP_PAGE = "request_help_page"
internal const val ARG_SELECTED_MENU_ITEM = " selected_menu_item"

/**
 * This fragment is responsible for showing a list of menu items in the application's [HelpDialog].
 */
class HelpPageFragment : Fragment(R.layout.fragment_help_page) {
    private val binding by viewBinding(FragmentHelpPageBinding::bind)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        requireArgsHelpEntries().forEach { menuItem ->
            val contentRow = ItemHelpEntryBinding.inflate(layoutInflater, binding.pageContent, false).root
            contentRow.apply {
                setText(menuItem.titleResId)
                setCompoundDrawablesRelativeWithIntrinsicBoundsKt(start = menuItem.iconResId)
                compoundDrawablePadding = 16.dp.toPx(requireContext())
                setOnClickListener {
                    Analytics.send(LinkClicked(menuItem.analyticsId))
                    parentFragmentManager.setFragmentResult(
                        REQUEST_HELP_PAGE,
                        Bundle().apply { putParcelable(ARG_SELECTED_MENU_ITEM, menuItem) },
                    )
                }
                binding.pageContent.addView(this)
            }
        }
    }
}

/**
 * The top level menu items being shown in the help menu.
 * The help menu is a two level menu and for simplicity and ease of handling the corresponding menu
 * items are split in two arrays: this one which shows the main items and [childHelpMenuItems] which
 * groups all the children of the menu items from this array.
 */
@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal val mainHelpMenuItems =
    arrayOf(
        HelpItem(
            titleResId = R.string.help_item_anki_manual,
            iconResId = R.drawable.ic_manual_black_24dp,
            analyticsId = LinkAction.OPENED_ANKI_MANUAL,
            id = 1,
            action = OpenUrlResource(R.string.link_anki_manual),
        ),
        HelpItem(
            titleResId = R.string.help_title_get_help,
            iconResId = R.drawable.ic_help_black_24dp,
            analyticsId = LinkAction.OPENED_GET_HELP,
            id = 2,
        ),
        HelpItem(
            titleResId = R.string.help_title_community,
            iconResId = R.drawable.ic_people_black_24dp,
            analyticsId = LinkAction.OPENED_COMMUNITY,
            id = 3,
        ),
        HelpItem(
            titleResId = R.string.help_title_privacy,
            iconResId = R.drawable.ic_baseline_privacy_tip_24,
            analyticsId = LinkAction.OPENED_PRIVACY,
            id = 4,
        ),
    )

/** This array contains all the children of the top level menu items from the help menu. */
@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal val childHelpMenuItems =
    arrayOf(
        HelpItem(
            titleResId = R.string.help_item_mailing_list,
            iconResId = R.drawable.ic_email_black_24dp,
            analyticsId = LinkAction.OPENED_MAILING_LIST,
            id = 200,
            parentId = 2,
            action = OpenUrlResource(R.string.link_forum),
        ),
        HelpItem(
            titleResId = R.string.help_item_anki_forums,
            iconResId = R.drawable.ic_forum_black_24dp,
            analyticsId = LinkAction.OPENED_ANKI_FORUMS,
            id = 300,
            parentId = 3,
            action = OpenUrlResource(R.string.link_anki_forum),
        ),
        HelpItem(
            titleResId = R.string.help_item_mailing_list,
            iconResId = R.drawable.ic_email_black_24dp,
            analyticsId = LinkAction.OPENED_MAILING_LIST,
            id = 301,
            parentId = 3,
            action = OpenUrlResource(R.string.link_forum),
        ),
        HelpItem(
            titleResId = R.string.help_item_reddit,
            iconResId = R.drawable.ic_link,
            analyticsId = LinkAction.OPENED_REDDIT,
            id = 302,
            parentId = 3,
            action = OpenUrlResource(R.string.link_reddit),
        ),
        HelpItem(
            titleResId = R.string.help_item_discord,
            iconResId = R.drawable.ic_link,
            analyticsId = LinkAction.OPENED_DISCORD,
            id = 303,
            parentId = 3,
            action = OpenUrlResource(R.string.link_discord),
        ),
        HelpItem(
            titleResId = R.string.help_item_facebook,
            iconResId = R.drawable.ic_link,
            analyticsId = LinkAction.OPENED_FACEBOOK,
            id = 304,
            parentId = 3,
            action = OpenUrlResource(R.string.link_facebook),
        ),
        HelpItem(
            titleResId = R.string.help_item_twitter,
            iconResId = R.drawable.ic_link,
            analyticsId = LinkAction.OPENED_TWITTER,
            id = 305,
            parentId = 3,
            action = OpenUrlResource(R.string.link_twitter),
        ),
        HelpItem(
            titleResId = R.string.help_item_ankiweb_privacy_policy,
            iconResId = R.drawable.ic_baseline_policy_24,
            analyticsId = LinkAction.OPENED_ANKIWEB_PRIVACY_POLICY,
            id = 401,
            parentId = 4,
            action = OpenUrlResource(R.string.link_ankiweb_privacy_policy),
        ),
        HelpItem(
            titleResId = R.string.help_item_ankiweb_terms_and_conditions,
            iconResId = R.drawable.ic_baseline_description_24,
            analyticsId = LinkAction.OPENED_ANKIWEB_TERMS_AND_CONDITIONS,
            id = 402,
            parentId = 4,
            action = OpenUrlResource(R.string.link_ankiweb_terms_and_conditions),
        ),
    )
