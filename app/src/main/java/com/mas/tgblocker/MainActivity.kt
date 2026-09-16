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
    private var currentMode: BlockingMode = BlockingMode.NORMAL

    companion object {
        private const val PENALTY_STEPS = 10
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = BlockedChannelRepository(this)

        setSupportActionBar(binding.toolbar)

        adapter = ChannelAdapter(
            onEdit = { channel -> guardIfStrict { showEditDialog(channel) } },
            onDelete = { channel -> guardIfStrict { deleteChannel(channel) } }
        )
        binding.rvChannels.layoutManager = LinearLayoutManager(this)
        binding.rvChannels.adapter = adapter

        setupModeToggle()

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

    /** Set hint pada input sesuai tipe yang dipilih, dan hubungkan RadioGroup ke perubahan hint. */
    private fun setupChannelTypeRadio(dialogView: android.view.View) {
        val radioGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.radioChannelType)
        val tilUsername = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilUsername)
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            tilUsername.hint = if (checkedId == R.id.radioChannelName) {
                getString(R.string.hint_channel_name)
            } else {
                getString(R.string.hint_channel_username)
            }
        }
    }

    private fun selectedType(dialogView: android.view.View): ChannelType {
        val radioGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.radioChannelType)
        return if (radioGroup.checkedRadioButtonId == R.id.radioChannelName) {
            ChannelType.NAME
        } else {
            ChannelType.USERNAME
        }
    }

    private fun showAddDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_channel, null)
        val etUsername = dialogView.findViewById<TextInputEditText>(R.id.etUsername)
        setupChannelTypeRadio(dialogView)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_add_title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val input = etUsername.text?.toString().orEmpty()
                if (input.trim().removePrefix("@").isBlank()) {
                    Toast.makeText(this, R.string.toast_invalid_username, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val added = repository.addChannel(selectedType(dialogView), input)
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
        val radioGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.radioChannelType)
        setupChannelTypeRadio(dialogView)
        etUsername.setText(channel.value)
        radioGroup.check(if (channel.type == ChannelType.NAME) R.id.radioChannelName else R.id.radioUsername)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_edit_title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val input = etUsername.text?.toString().orEmpty()
                if (input.trim().removePrefix("@").isBlank()) {
                    Toast.makeText(this, R.string.toast_invalid_username, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                repository.updateChannel(channel, selectedType(dialogView), input)
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

    // ----- Mode Mati / Normal / Ketat -----
    // Naik tingkat (Mati->Normal, Normal->Ketat, Mati->Ketat) bebas, tanpa gate.
    // Turun dari Ketat (ke Normal atau Mati) WAJIB lewat gate teks acak dulu.
    // Edit/Hapus channel hanya digate kalau mode saat ini Ketat (lihat guardIfStrict).

    private fun setupModeToggle() {
        currentMode = repository.getMode()
        reflectModeUI(currentMode)
        binding.toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val requestedMode = when (checkedId) {
                R.id.btnModeOff -> BlockingMode.OFF
                R.id.btnModeStrict -> BlockingMode.STRICT
                else -> BlockingMode.NORMAL
            }
            onModeRequested(requestedMode)
        }
    }

    private fun reflectModeUI(mode: BlockingMode) {
        val id = when (mode) {
            BlockingMode.OFF -> R.id.btnModeOff
            BlockingMode.NORMAL -> R.id.btnModeNormal
            BlockingMode.STRICT -> R.id.btnModeStrict
        }
        binding.toggleMode.check(id)
    }

    private fun onModeRequested(requestedMode: BlockingMode) {
        if (requestedMode == currentMode) return

        val downgradingFromStrict = currentMode == BlockingMode.STRICT && requestedMode != BlockingMode.STRICT
        if (downgradingFromStrict) {
            // Tahan dulu: kembalikan tampilan ke mode saat ini (Ketat) sampai
            // pengguna berhasil mengisi teks acak dengan benar.
            reflectModeUI(currentMode)
            showRandomTextGate {
                currentMode = requestedMode
                repository.setMode(requestedMode)
                reflectModeUI(requestedMode)
            }
        } else {
            currentMode = requestedMode
            repository.setMode(requestedMode)
            reflectModeUI(requestedMode)
        }
    }

    /** Edit/Hapus channel hanya digate kalau mode saat ini Ketat. */
    private fun guardIfStrict(action: () -> Unit) {
        if (currentMode == BlockingMode.STRICT) {
            showRandomTextGate { action() }
        } else {
            action()
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
        var currentIndex = 0

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_random_gate, null)
        val tvCurrentChar = dialogView.findViewById<android.widget.TextView>(R.id.tvRandomText)
        val tvProgress = dialogView.findViewById<android.widget.TextView>(R.id.tvGateProgress)
        val tvError = dialogView.findViewById<android.widget.TextView>(R.id.tvGateError)
        val etInput = dialogView.findViewById<TextInputEditText>(R.id.etRandomTextInput)

        fun showChar(index: Int) {
            tvCurrentChar.text = target[index].toString()
            tvProgress.text = getString(R.string.random_gate_progress, index + 1, target.length)
        }
        showChar(currentIndex)

        // Matikan menu copy/paste/select, supaya tidak bisa tempel karakter
        // dari luar (misal hasil bantuan AI lain).
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
                if (typed.isEmpty()) return

                val typedChar = typed[0]
                if (typedChar == target[currentIndex]) {
                    tvError.visibility = android.view.View.GONE
                    currentIndex++
                    etInput.setText("")
                    if (currentIndex == target.length) {
                        dialog.dismiss()
                        onSuccess()
                    } else {
                        showChar(currentIndex)
                    }
                } else {
                    tvError.visibility = android.view.View.VISIBLE
                    etInput.setText("")
                    // Penalti: mundur 10 langkah (tidak sampai minus / reset total ke 0).
                    currentIndex = (currentIndex - PENALTY_STEPS).coerceAtLeast(0)
                    showChar(currentIndex)
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
                repository.deleteChannel(channel)
                refreshList()
                Toast.makeText(this, R.string.toast_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }
}
