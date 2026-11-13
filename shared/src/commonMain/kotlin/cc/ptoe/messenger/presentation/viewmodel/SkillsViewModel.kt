package cc.ptoe.messenger.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.domain.model.SkillPackage
import cc.ptoe.messenger.presentation.platform.SkillImportBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass

@Serializable
private data class SkillList(val items: List<SkillPackage>)

@Serializable
private data class SkillWrite(val files: Map<String, String>, val enabled: Boolean, val binaryFiles: Map<String, String> = emptyMap())

data class SkillsUiState(
    val skills: List<SkillPackage> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val editing: SkillPackage? = null,
    val draftFiles: Map<String, String> = emptyMap(),
    val imported: Boolean = false,
)

class SkillsViewModel(private val core: CoreBridge) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableState = MutableStateFlow(SkillsUiState())
    val state = mutableState.asStateFlow()

    init { refresh() }

    private suspend fun reload() {
        val skills = json.decodeFromString<SkillList>(core.listSkills()).items
        mutableState.value = mutableState.value.copy(skills = skills)
    }

    /** Serialize inventory changes so an older response cannot replace a newer editor. */
    private fun request(block: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportError(failure.message ?: failure.toString())
            } finally {
                mutableState.value = mutableState.value.copy(busy = false, loading = false)
            }
        }
    }

    fun refresh() = request { reload() }

    fun open(name: String) = request {
        val skill = json.decodeFromString<SkillPackage>(core.getSkill(name))
        mutableState.value = mutableState.value.copy(editing = skill, draftFiles = skill.files, imported = false)
    }

    fun create() {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(
            editing = SkillPackage("", "", files = mapOf("SKILL.md" to DEFAULT_SKILL)),
            draftFiles = mapOf("SKILL.md" to DEFAULT_SKILL),
            imported = false, error = null,
        )
    }

    fun closeEditor() {
        if (!mutableState.value.busy) mutableState.value = mutableState.value.copy(editing = null, draftFiles = emptyMap(), imported = false, error = null)
    }

    fun save(
        name: String, files: Map<String, String>, enabled: Boolean,
        binaryFiles: Map<String, String> = mutableState.value.editing?.binaryFiles.orEmpty(),
    ) = request {
        core.saveSkill(name, json.encodeToString(SkillWrite(files, enabled, binaryFiles)))
        mutableState.value = mutableState.value.copy(editing = null, draftFiles = emptyMap(), imported = false)
        reload()
    }

    fun setEnabled(skill: SkillPackage, enabled: Boolean) = request {
        // The list intentionally contains metadata only. Fetch the full bundle before changing it.
        val current = json.decodeFromString<SkillPackage>(core.getSkill(skill.name))
        core.saveSkill(skill.name, json.encodeToString(SkillWrite(current.files, enabled, current.binaryFiles)))
        reload()
    }

    fun delete(name: String) = request {
        core.deleteSkill(name)
        reload()
    }

    fun importFiles(bundle: SkillImportBundle) = request {
        val skill = json.decodeFromString<SkillPackage>(core.validateSkill(json.encodeToString(SkillWrite(bundle.files, true, bundle.binaryFiles))))
        mutableState.value = mutableState.value.copy(editing = skill, draftFiles = skill.files, imported = true)
    }

    /** Keep resource drafts in the ViewModel across Activity recreation, outside saved-state Bundles. */
    fun updateDraftFiles(files: Map<String, String>) {
        if (!mutableState.value.busy) mutableState.value = mutableState.value.copy(draftFiles = files)
    }

    fun reportError(message: String) { mutableState.value = mutableState.value.copy(error = message) }
    fun clearError() { mutableState.value = mutableState.value.copy(error = null) }

    companion object {
        private const val DEFAULT_SKILL = "---\nname: example-skill\ndescription: Describe what this skill does and when to use it.\n---\n\n"

        fun provideFactory(core: CoreBridge): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T = SkillsViewModel(core) as T
        }
    }
}
