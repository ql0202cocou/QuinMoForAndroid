package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jakewharton.processphoenix.ProcessPhoenix
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.Executable
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.databinding.LayoutBackupBinding
import io.nekohasekai.sagernet.databinding.LayoutImportBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressBinding
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.delay
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class BackupFragment : NamedFragment(R.layout.layout_backup) {

    override fun name0() = app.getString(R.string.backup)

    var content = ""
    private val exportSettings =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { data ->
            if (data != null) {
                // Process death with the picker foreground loses content
                runOnDefaultDispatcher { writeToDocument(data, content) }
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutBackupBinding.bind(view)

        binding.resetSettings.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                .setMessage(R.string.reset_settings_message)
                .setNegativeButton(R.string.no, null)
                .setPositiveButton(R.string.yes) { _, _ ->
                    DataStore.configurationStore.reset()
                    triggerFullRestart(requireContext())
                }
                .show()
        }

        binding.actionExport.setOnClickListener {
            runOnDefaultDispatcher {
                content = doBackup(
                    binding.backupConfigurations.isChecked,
                    binding.backupRules.isChecked,
                    binding.backupSettings.isChecked
                )
                onMainDispatcher {
                    startFilesForResult(
                        exportSettings,
                        "nekobox_backup_${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.json"
                    )
                }
            }
        }

        binding.actionShare.setOnClickListener {
            runOnDefaultDispatcher {
                content = doBackup(
                    binding.backupConfigurations.isChecked,
                    binding.backupRules.isChecked,
                    binding.backupSettings.isChecked
                )
                app.cacheDir.mkdirs()
                val cacheFile = File(
                    app.cacheDir,
                    "nekobox_backup_${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.json"
                )
                cacheFile.writeText(content)
                onMainDispatcher {
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("application/json")
                                .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                .putExtra(
                                    Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                                        app, BuildConfig.APPLICATION_ID + ".cache", cacheFile
                                    )
                                ), app.getString(R.string.abc_shareactionprovider_share_with)
                        )
                    )
                }

            }
        }

        binding.actionImportFile.setOnClickListener {
            startFilesForResult(importFile, "*/*")
        }
    }

    fun Parcelable.toBase64Str(): String {
        val parcel = Parcel.obtain()
        writeToParcel(parcel, 0)
        try {
            return Util.b64EncodeUrlSafe(parcel.marshall())
        } finally {
            parcel.recycle()
        }
    }

    // Inverse of toBase64Str(): null when the record cannot be decoded, so a
    // single corrupt entry is skipped instead of failing the whole import.
    private inline fun <T> unmarshal(b64: String, create: (Parcel) -> T): T? = runCatching {
        val data = Util.b64Decode(b64)
        val parcel = Parcel.obtain()
        try {
            parcel.unmarshall(data, 0, data.size)
            parcel.setDataPosition(0)
            create(parcel)
        } finally {
            parcel.recycle()
        }
    }.getOrNull()

    fun doBackup(profile: Boolean, rule: Boolean, setting: Boolean): String {
        val out = JSONObject().apply {
            put("version", 1)
            if (profile) {
                put("profiles", JSONArray().apply {
                    SagerDatabase.proxyDao.getAll().forEach {
                        put(it.toBase64Str())
                    }
                })

                put("groups", JSONArray().apply {
                    SagerDatabase.groupDao.allGroups().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
            if (rule) {
                put("rules", JSONArray().apply {
                    SagerDatabase.rulesDao.allRules().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
            if (setting) {
                put("settings", JSONArray().apply {
                    PublicDatabase.kvPairDao.all().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
        }
        return out.toStringPretty()
    }

    val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        if (file != null) {
            runOnDefaultDispatcher {
                startImport(file)
            }
        }
    }

    suspend fun startImport(file: Uri) {
        // The fragment may already be detached by the time this coroutine
        // runs (user picked a file and left immediately); bail out then.
        val fileName = try {
            requireContext().contentResolver.query(file, null, null, null, null)
                ?.use { cursor ->
                    cursor.moveToFirst()
                    cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME).let(cursor::getString)
                }
                ?.takeIf { it.isNotBlank() } ?: file.pathSegments.last()
                .substringAfterLast('/')
                .substringAfter(':')
        } catch (e: Exception) {
            Logs.w(e)
            return
        }

        if (!fileName.endsWith(".json")) {
            onMainDispatcher {
                snackbar(getString(R.string.backup_not_file, fileName)).show()
            }
            return
        }

        suspend fun invalid() = onMainDispatcher {
            onMainDispatcher {
                snackbar(getString(R.string.invalid_backup_file)).show()
            }
        }

        val content = try {
            JSONObject((requireContext().contentResolver.openInputStream(file) ?: return).use {
                it.bufferedReader().readText()
            })
        } catch (e: Exception) {
            Logs.w(e)
            invalid()
            return
        }
        val version = content.optInt("version", 0)
        if (version < 1 || version > 1) {
            invalid()
            return
        }

        onMainDispatcher {
            // the user may have left while the file was read in the background
            if (!isAdded) return@onMainDispatcher
            val import = LayoutImportBinding.inflate(layoutInflater)
            if (!content.has("profiles")) {
                import.backupConfigurations.isVisible = false
            }
            if (!content.has("rules")) {
                import.backupRules.isVisible = false
            }
            if (!content.has("settings")) {
                import.backupSettings.isVisible = false
            }
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.backup_import)
                .setView(import.root)
                .setPositiveButton(R.string.backup_import) { _, _ ->
                    SagerNet.stopService()

                    val binding = LayoutProgressBinding.inflate(layoutInflater)
                    binding.content.text = getString(R.string.backup_importing)
                    val dialog = AlertDialog.Builder(requireContext())
                        .setView(binding.root)
                        .setCancelable(false)
                        .show()
                    runOnDefaultDispatcher {
                        runCatching {
                            val skipped = finishImport(
                                content,
                                import.backupConfigurations.isChecked,
                                import.backupRules.isChecked,
                                import.backupSettings.isChecked
                            )
                            if (skipped > 0) {
                                Logs.w("Backup import skipped $skipped invalid record(s)")
                                onMainDispatcher {
                                    if (!isAdded) return@onMainDispatcher
                                    snackbar(
                                        getString(R.string.backup_import_skipped, skipped)
                                    ).show()
                                }
                            }
                            triggerFullRestart(requireContext())
                        }.onFailure {
                            Logs.w(it)
                            onMainDispatcher {
                                alert(it.readableMessage).tryToShow()
                            }
                        }

                        onMainDispatcher {
                            dialog.dismiss()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    fun finishImport(
        content: JSONObject, profile: Boolean, rule: Boolean, setting: Boolean
    ): Int {
        var skippedRecords = 0
        if (profile && content.has("profiles")) {
            val profiles = mutableListOf<ProxyEntity>()
            val jsonProfiles = content.getJSONArray("profiles")
            for (i in 0 until jsonProfiles.length()) {
                val entity = unmarshal(jsonProfiles[i] as String) {
                    ProxyEntity.CREATOR.createFromParcel(it).also { entity ->
                        // Unknown types leave every bean null (putByteArray has no
                        // else); the configuration list would crash in requireBean()
                        // when binding such a record.
                        entity.requireBean()
                    }
                }
                if (entity == null) {
                    skippedRecords++
                    continue
                }
                profiles.add(entity)
            }

            val groups = mutableListOf<ProxyGroup>()
            val jsonGroups = content.getJSONArray("groups")
            for (i in 0 until jsonGroups.length()) {
                val group = unmarshal(jsonGroups[i] as String, ProxyGroup.CREATOR::createFromParcel)
                if (group == null) {
                    skippedRecords++
                    continue
                }
                groups.add(group)
            }
            SagerDatabase.instance.runInTransaction {
                SagerDatabase.proxyDao.reset()
                SagerDatabase.proxyDao.insert(profiles)
                SagerDatabase.groupDao.reset()
                SagerDatabase.groupDao.insert(groups)
            }
        }
        if (rule && content.has("rules")) {
            val rules = mutableListOf<RuleEntity>()
            val jsonRules = content.getJSONArray("rules")
            for (i in 0 until jsonRules.length()) {
                val ruleEntity = unmarshal(jsonRules[i] as String, ParcelizeBridge::createRule)
                if (ruleEntity == null) {
                    skippedRecords++
                    continue
                }
                rules.add(ruleEntity)
            }
            SagerDatabase.instance.runInTransaction {
                SagerDatabase.rulesDao.reset()
                SagerDatabase.rulesDao.insert(rules)
            }
        }
        if (setting && content.has("settings")) {
            val settings = mutableListOf<KeyValuePair>()
            val jsonSettings = content.getJSONArray("settings")
            for (i in 0 until jsonSettings.length()) {
                val kvPair = unmarshal(jsonSettings[i] as String, KeyValuePair.CREATOR::createFromParcel)
                if (kvPair == null) {
                    skippedRecords++
                    continue
                }
                settings.add(kvPair)
            }
            PublicDatabase.instance.runInTransaction {
                PublicDatabase.kvPairDao.reset()
                PublicDatabase.kvPairDao.insert(settings)
            }
            // The imported PROFILE_GROUP may reference a group that does not
            // exist here (e.g. a settings-only import); currentGroupId() trusts
            // any positive value and the configuration page would stay blank.
            // Same fallback as GroupManager.resetSelectedGroup().
            if (DataStore.selectedGroup > 0L &&
                SagerDatabase.groupDao.getById(DataStore.selectedGroup) == null
            ) {
                DataStore.selectedGroup =
                    SagerDatabase.groupDao.allGroups().firstOrNull()?.id ?: -1L
            }
        }
        return skippedRecords
    }

}