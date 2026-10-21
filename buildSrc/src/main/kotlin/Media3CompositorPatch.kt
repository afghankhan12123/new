import org.gradle.api.Project
import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Remove only the pinned compositor class replaced by reviewed source in core:media. */
@CacheableTransform
abstract class Media3CompositorPatch : TransformAction<TransformParameters.None> {
    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val input = inputArtifact.get().asFile
        val replacedClasses = when (input.name) {
            "media3-effect-1.5.1.aar" -> setOf("androidx/media3/effect/DefaultVideoCompositor")
            "media3-exoplayer-1.5.1.aar" -> setOf("androidx/media3/exoplayer/source/ClippingMediaPeriod")
            "media3-transformer-1.5.1.aar" -> setOf("androidx/media3/transformer/ExoAssetLoaderVideoRenderer", "androidx/media3/transformer/SequenceAssetLoader")
            else -> emptySet()
        }
        if (replacedClasses.isEmpty()) {
            outputs.file(input)
            return
        }
        val output = outputs.file(input.name.removeSuffix(".aar") + "-project-clock.aar")
        ZipFile(input).use { aar ->
            ZipOutputStream(output.outputStream()).use { out ->
                val entries = aar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    out.putNextEntry(ZipEntry(entry.name).apply { time = 0 })
                    if (entry.name == "classes.jar") {
                        val bytes = ByteArrayOutputStream()
                        var removed = 0
                        ZipInputStream(ByteArrayInputStream(aar.getInputStream(entry).readBytes())).use { classes ->
                            ZipOutputStream(bytes).use { patched ->
                                while (true) {
                                    val item = classes.nextEntry ?: break
                                    val compositor = replacedClasses.any { name ->
                                        item.name == "$name.class" || item.name.startsWith(name + "$" )
                                    }
                                    if (compositor) removed++ else {
                                        patched.putNextEntry(ZipEntry(item.name).apply { time = 0 })
                                        classes.copyTo(patched)
                                        patched.closeEntry()
                                    }
                                }
                            }
                        }
                        check(removed > 0) { "Pinned Media3 compositor classes missing; review the source adapter" }
                        out.write(bytes.toByteArray())
                    } else aar.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
    }
}

fun Project.configureMedia3CompositorPatch() {
    val patched = Attribute.of("com.videoeditor.media3.project-clock-compositor", Boolean::class.javaObjectType)
    dependencies.artifactTypes.maybeCreate("aar").attributes.attribute(patched, false)
    dependencies.registerTransform(Media3CompositorPatch::class.java) {
        from.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "aar").attribute(patched, false)
        to.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "aar").attribute(patched, true)
    }
    configurations.configureEach {
        if (isCanBeResolved) attributes.attribute(patched, true)
    }
}
