package com.andrerinas.headunitrevived.main

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.main.settings.SettingItem
import com.andrerinas.headunitrevived.main.settings.SettingsAdapter
import com.andrerinas.headunitrevived.utils.Settings
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class WirelessConnectionFragment : Fragment(R.layout.fragment_wireless_connection) {

    private lateinit var settings: Settings
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SettingsAdapter
    private lateinit var toolbar: MaterialToolbar

    private var pendingWifiConnectionMode: Int? = null
    private var pendingAutoEnableHotspot: Boolean? = null
    
    private var hasChanges = false
    private val SAVE_ITEM_ID = 1001

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = App.provide(requireContext()).settings

        pendingWifiConnectionMode = settings.wifiConnectionMode
        pendingAutoEnableHotspot = settings.autoEnableHotspot

        toolbar = view.findViewById(R.id.toolbar)
        recyclerView = view.findViewById(R.id.settingsRecyclerView)
        adapter = SettingsAdapter()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        setupToolbar()
        updateSettingsList()

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBack()
            }
        })
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener { handleBack() }
        updateSaveButtonState()
    }

    private fun updateSaveButtonState() {
        toolbar.menu.clear()
        if (hasChanges) {
            val saveItem = toolbar.menu.add(0, SAVE_ITEM_ID, 0, getString(R.string.save))
            saveItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            
            toolbar.setOnMenuItemClickListener { item ->
                if (item.itemId == SAVE_ITEM_ID) {
                    saveSettings()
                    true
                } else false
            }
        }
    }

    private fun updateSettingsList() {
        val items = mutableListOf<SettingItem>()
        val wifiModes = resources.getStringArray(R.array.wireless_connection_modes)

        items.add(SettingItem.CategoryHeader("wireless_mode", R.string.wireless_mode))
        
        items.add(SettingItem.SettingEntry(
            stableId = "wifiConnectionMode",
            nameResId = R.string.wireless_mode,
            value = wifiModes.getOrElse(pendingWifiConnectionMode!!) { "" },
            onClick = { _ ->
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.wireless_mode)
                    .setSingleChoiceItems(wifiModes, pendingWifiConnectionMode!!) { dialog, which ->
                        dialog.dismiss()
                        
                        if (which == 3) {
                            // Run the compatibility check for Native AA Mode
                            if (com.andrerinas.headunitrevived.connection.NativeAaHandshakeManager.checkCompatibility()) {
                                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                                    .setTitle(R.string.supported_nativeaa)
                                    .setMessage(R.string.supported_nativeaa_desc)
                                    .setPositiveButton(android.R.string.ok) { dialog2, _ ->
                                        pendingWifiConnectionMode = which
                                        checkChanges()
                                        updateSettingsList()
                                        dialog2.dismiss()
                                    }
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .show()
                            } else {
                                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                                    .setTitle(R.string.not_supported_nativeaa)
                                    .setMessage(R.string.not_supported_nativeaa_desc)
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show()
                                    
                                // Do not change the pending mode
                                updateSettingsList()
                            }
                        } else {
                            pendingWifiConnectionMode = which
                            checkChanges()
                            updateSettingsList()
                        }
                    }
                    .show()
            }
        ))

        if (pendingWifiConnectionMode == 1 || pendingWifiConnectionMode == 2) {
            items.add(SettingItem.ToggleSettingEntry(
                stableId = "autoEnableHotspot",
                nameResId = R.string.auto_enable_hotspot,
                descriptionResId = R.string.auto_enable_hotspot_description,
                isChecked = pendingAutoEnableHotspot ?: false,
                onCheckedChanged = { isChecked ->
                    if (isChecked) {
                        if (Build.VERSION.SDK_INT >= 23 && !SystemSettings.System.canWrite(requireContext())) {
                            showPermissionDialog()
                        } else {
                            showExperimentalWarning()
                        }
                    } else {
                        pendingAutoEnableHotspot = false
                        checkChanges()
                        updateSettingsList()
                    }
                }
            ))
        }

        // Add bottom save button
        if (hasChanges) {
            items.add(SettingItem.ActionButton(
                stableId = "bottomSaveButton",
                textResId = R.string.save,
                onClick = { saveSettings() }
            ))
        }

        adapter.submitList(items)
    }

    private fun showPermissionDialog() {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.hotspot_permission_title)
            .setMessage(R.string.hotspot_permission_message)
            .setPositiveButton(R.string.open_settings) { dialog, _ ->
                val intent = Intent(SystemSettings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                }
                startActivity(intent)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                pendingAutoEnableHotspot = false
                checkChanges()
                updateSettingsList()
            }
            .show()
    }

    private fun showExperimentalWarning() {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.hotspot_warning_title)
            .setMessage(R.string.hotspot_warning_message)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                pendingAutoEnableHotspot = true
                checkChanges()
                updateSettingsList()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                pendingAutoEnableHotspot = false
                checkChanges()
                updateSettingsList()
            }
            .show()
    }

    private fun checkChanges() {
        val anyChange = pendingWifiConnectionMode != settings.wifiConnectionMode ||
                        pendingAutoEnableHotspot != settings.autoEnableHotspot
        
        if (hasChanges != anyChange) {
            hasChanges = anyChange
            updateSaveButtonState()
            updateSettingsList()
        }
    }

    private fun saveSettings() {
        val oldMode = settings.wifiConnectionMode
        settings.wifiConnectionMode = pendingWifiConnectionMode!!
        settings.autoEnableHotspot = pendingAutoEnableHotspot!!
        settings.commit()

        if (oldMode != settings.wifiConnectionMode) {
            val intent = Intent(requireContext(), AapService::class.java).apply {
                val mode = settings.wifiConnectionMode
                action = if (mode == 1 || mode == 2 || mode == 3) 
                    AapService.ACTION_START_WIRELESS else AapService.ACTION_STOP_WIRELESS
            }
            requireContext().startService(intent)
        }

        hasChanges = false
        updateSaveButtonState()
        updateSettingsList()
        Toast.makeText(context, R.string.settings_saved, Toast.LENGTH_SHORT).show()
    }

    private fun handleBack() {
        if (hasChanges) {
            MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.unsaved_changes)
                .setMessage(R.string.unsaved_changes_message)
                .setPositiveButton(R.string.discard) { _, _ -> findNavController().popBackStack() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            findNavController().popBackStack()
        }
    }
}
