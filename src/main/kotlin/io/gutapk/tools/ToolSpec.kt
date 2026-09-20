package io.gutapk.tools

data class ToolSpec(
    val id: String,
    val version: String,
    val url: String,
    val sha256: String,
    val entry: String,
)

object Tools {
    val known: List<ToolSpec> = listOf(
        ToolSpec(
            id = "platform-tools",
            version = "35.0.2",
            url = "https://dl.google.com/android/repository/platform-tools_r35.0.2-linux.zip",
            sha256 = "acfdcccb123a8718c46c46c059b2f621140194e5ec1ac9d81715be3d6ab6cd0a",
            entry = "platform-tools/adb",
        ),
    )

    fun byId(id: String): ToolSpec? = known.firstOrNull { it.id == id }
}
