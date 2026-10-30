package com.z30lab.core

import java.io.File
import java.util.UUID

data class PhoneRecipe(
    val id: String,
    val file: File,
    val name: String,
    val summary: String,
    val transferIssue: String?
)

class RecipeImportResult {
    val importedIDs = mutableListOf<String>()
    val duplicateIDs = mutableListOf<String>()
    val failures = mutableListOf<String>()

    val summary: String
        get() = "已导入 ${importedIDs.size} 个，重复 ${duplicateIDs.size} 个，失败 ${failures.size} 个。"
}

object RecipeLibraryImporter {
    private val ALLOWED_EXTENSIONS = setOf("np3", "ncp")

    fun importFiles(files: List<File>, folder: File): RecipeImportResult {
        folder.mkdirs()
        val existing = HashMap<ByteArrayKey, String>()
        folder.listFiles()?.forEach { file ->
            if (file.extension.lowercase() in ALLOWED_EXTENSIONS && file.length() <= 65536) {
                try {
                    existing[ByteArrayKey(file.readBytes())] = file.name
                } catch (_: Exception) {
                    // ignore unreadable files while scanning
                }
            }
        }
        val result = RecipeImportResult()
        for (file in files) {
            try {
                require(file.extension.lowercase() in ALLOWED_EXTENSIONS && file.length() <= 65536) {
                    throw CameraError.Invalid("请选择不超过 64 KB 的 NCP 或 NP3 文件")
                }
                val data = file.readBytes()
                val recipe = PhoneRecipeDocument.parse(data)
                val duplicate = existing[ByteArrayKey(data)]
                if (duplicate != null) {
                    result.duplicateIDs.add(duplicate)
                    continue
                }
                val id = "${UUID.randomUUID()}.${recipe.fileExtension}"
                File(folder, id).writeBytes(data)
                existing[ByteArrayKey(data)] = id
                result.importedIDs.add(id)
            } catch (e: Exception) {
                result.failures.add("${file.name}：${e.message}")
            }
        }
        return result
    }

    fun listRecipes(folder: File): List<PhoneRecipe> {
        folder.mkdirs()
        return folder.listFiles()
            ?.filter { it.extension.lowercase() in ALLOWED_EXTENSIONS && it.length() <= 65536 }
            ?.mapNotNull { file ->
                try {
                    val data = file.readBytes()
                    val recipe = PhoneRecipeDocument.parse(data)
                    PhoneRecipe(
                        id = file.name,
                        file = file,
                        name = recipe.name,
                        summary = recipe.summary,
                        transferIssue = recipe.transferIssue
                    )
                } catch (_: Exception) {
                    null
                }
            }
            ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            ?: emptyList()
    }
}
