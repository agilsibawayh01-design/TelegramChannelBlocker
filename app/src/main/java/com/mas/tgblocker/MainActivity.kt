package com.mas.tgblocker

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.ActionMode
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.mas.tgblocker.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: BlockedChannelRepository
    private lateinit var adapter: ChannelAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = BlockedChannelRepository(this)

        setSupportActionBar(binding.toolbar)

        adapter = ChannelAdapter(
            onEdit = { channel -> showRandomTextGate { showEditDialog(channel) } },
            onDelete = { channel -> showRandomTextGate { deleteChannel(channel) } }
        )
        binding.rvChannels.layoutManager = LinearLayoutManager(this)
        binding.rvChannels.adapter = adapter

        binding.switchEnabled.isChecked = repository.isBlockingEnabled()
        binding.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            onSwitchToggled(isChecked)
        }

        binding.fabAdd.setOnClickListener { showAddDialog() }

        binding.tvServiceInstructions.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnEnableUninstallProtection.setOnClickListener {
            requestDeviceAdmin()
        }

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
        updateServiceStatus()
        updateUninstallProtectionStatus()
    }

    private fun refreshList() {
        val channels = repository.getChannels()
        adapter.submitList(channels)
        binding.tvEmpty.visibility = if (channels.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun updateServiceStatus() {
        val enabled = isAccessibilityServiceEnabled()
        binding.tvServiceStatus.text = if (enabled) {
            getString(R.string.service_active)
        } else {
            getString(R.string.service_inactive)
        }
        binding.tvServiceStatus.setTextColor(
            resources.getColor(if (enabled) R.color.accent else R.color.danger, theme)
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = "$packageName/${TelegramBlockAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        for (component in splitter) {
            if (component.equals(expectedComponent, ignoreCase = true)) return true
        }
        return false
    }

    private fun showAddDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_channel, null)
        val etUsername = dialogView.findViewById<TextInputEditText>(R.id.etUsername)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_add_title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val input = etUsername.text?.toString().orEmpty()
                if (input.trim().removePrefix("@").isBlank()) {
                    Toast.makeText(this, R.string.toast_invalid_username, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val added = repository.addChannel(input)
                if (!added) {
                    Toast.makeText(this, R.string.toast_duplicate, Toast.LENGTH_SHORT).show()
                }
                refreshList()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showEditDialog(channel: BlockedChannel) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_channel, null)
        val etUsername = dialogView.findViewById<TextInputEditText>(R.id.etUsername)
        etUsername.setText(channel.username)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_edit_title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val input = etUsername.text?.toString().orEmpty()
                if (input.trim().removePrefix("@").isBlank()) {
                    Toast.makeText(this, R.string.toast_invalid_username, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                repository.updateChannel(channel.username, input)
                refreshList()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    // ----- Fitur pencegahan uninstall (Device Admin) -----
    // Bagian ini sengaja berdiri sendiri, tidak memanggil apa pun dari
    // BlockedChannelRepository / AccessibilityService, supaya fitur
    // pemblokiran channel yang sudah berjalan tidak ikut terdampak.

    private fun getDevicePolicyManager(): DevicePolicyManager =
        getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun getAdminComponent(): ComponentName =
        ComponentName(this, TgBlockerDeviceAdminReceiver::class.java)

    private fun isDeviceAdminActive(): Boolean {
        return try {
            getDevicePolicyManager().isAdminActive(getAdminComponent())
        } catch (e: Exception) {
            false
        }
    }

    private fun updateUninstallProtectionStatus() {
        val active = isDeviceAdminActive()
        binding.tvUninstallProtectionStatus.text = if (active) {
            getString(R.string.uninstall_protection_active)
        } else {
            getString(R.string.uninstall_protection_inactive)
        }
        binding.tvUninstallProtectionStatus.setTextColor(
            resources.getColor(if (active) R.color.accent else R.color.danger, theme)
        )
        binding.btnEnableUninstallProtection.visibility =
            if (active) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun requestDeviceAdmin() {
        if (isDeviceAdminActive()) return
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, getAdminComponent())
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.device_admin_explanation)
            )
        }
        startActivity(intent)
    }

    // ----- Random text gate untuk menonaktifkan pemblokiran -----
    // Terpisah dari logic Device Admin dan Accessibility di atas.

    private fun onSwitchToggled(isChecked: Boolean) {
        if (isChecked) {
            repository.setBlockingEnabled(true)
        } else {
            // Tahan dulu: kembalikan switch ke ON secara visual sampai
            // pengguna berhasil mengisi teks acak dengan benar.
            binding.switchEnabled.setOnCheckedChangeListener(null)
            binding.switchEnabled.isChecked = true
            binding.switchEnabled.setOnCheckedChangeListener { _, checked -> onSwitchToggled(checked) }
            showRandomTextGate {
                binding.switchEnabled.setOnCheckedChangeListener(null)
                binding.switchEnabled.isChecked = false
                binding.switchEnabled.setOnCheckedChangeListener { _, checked -> onSwitchToggled(checked) }
                repository.setBlockingEnabled(false)
            }
        }
    }

    /**
     * Gate teks acak generic: dipakai untuk menonaktifkan pemblokiran,
     * mengedit channel, dan menghapus channel. [onSuccess] hanya dipanggil
     * kalau pengguna berhasil mengetik ulang teks acak 512 karakter PERSIS,
     * divalidasi karakter demi karakter (tidak bisa tempel/paste, dan
     * layar tidak bisa di-screenshot selama dialog ini terbuka).
     */
    private fun showRandomTextGate(onSuccess: () -> Unit) {
        val target = RandomTextGate.generate()
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_random_gate, null)
        val tvRandomText = dialogView.findViewById<android.widget.TextView>(R.id.tvRandomText)
        val tvError = dialogView.findViewById<android.widget.TextView>(R.id.tvGateError)
        val etInput = dialogView.findViewById<TextInputEditText>(R.id.etRandomTextInput)
        tvRandomText.text = target

        // Matikan menu copy/paste/select di kolom input, supaya tidak bisa
        // tempel teks yang didapat dari luar (misal hasil bantuan AI lain).
        val disabledActionMode = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?) = false
            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?) = false
            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?) = false
            override fun onDestroyActionMode(mode: ActionMode?) {}
        }
        etInput.customSelectionActionModeCallback = disabledActionMode
        etInput.isLongClickable = false

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setNegativeButton(R.string.btn_cancel, null)
            .setCancelable(false)
            .create()

        // Cegah screenshot & perekaman layar selama dialog ini tampil.
        dialog.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val typed = s?.toString().orEmpty()
                if (typed.isEmpty()) {
                    tvError.visibility = android.view.View.GONE
                    return
                }
                val expectedPrefix = target.substring(0, minOf(typed.length, target.length))
                if (typed != expectedPrefix) {
                    tvError.visibility = android.view.View.VISIBLE
                    etInput.setText("")
                    return
                }
                tvError.visibility = android.view.View.GONE
                if (typed.length == target.length) {
                    dialog.dismiss()
                    onSuccess()
                }
            }
        })

        dialog.show()
    }

    private fun deleteChannel(channel: BlockedChannel) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.btn_delete)
            .setMessage(channel.display())
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                repository.deleteChannel(channel.username)
                refreshList()
                Toast.makeText(this, R.string.toast_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }
}
