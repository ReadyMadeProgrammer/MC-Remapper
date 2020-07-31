@file:Suppress("DuplicatedCode")

package io.heartpattern.mcremapper.commandline

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import io.heartpattern.mcremapper.MCRemapper
import io.heartpattern.mcremapper.download
import io.heartpattern.mcremapper.model.LocalVariableFixType
import io.heartpattern.mcremapper.model.PackageMapping
import io.heartpattern.mcremapper.parser.MappingsParser
import io.heartpattern.mcremapper.parser.csrg.MappingsCsrgParser
import io.heartpattern.mcremapper.parser.proguard.MappingsProguardParser
import io.heartpattern.mcremapper.resolver.ClassVisitorSuperTypeResolver
import io.heartpattern.mcremapper.toInternal
import kr.heartpattern.mcversions.MCVersions
import me.tongfei.progressbar.ProgressBarBuilder
import me.tongfei.progressbar.ProgressBarStyle
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class MCRemapperApp : CliktCommand() {
    // Input file or URL or server/client
    private val arg0: String by argument()

    // Mapping file or URL or version id
    private val arg1: String by argument()

    // Options
    private val output: File by option().file(mustExist = false).default(File("deobfuscated.jar"))
    private val reobf: Boolean by option().flag()
    private val thread: Int by option().int().default(8)
    private val fixlocalvar: LocalVariableFixType by option().choice("no", "rename", "delete").convert {
        when (it) {
            "no" -> LocalVariableFixType.NO
            "rename" -> LocalVariableFixType.RENAME
            "delete" -> LocalVariableFixType.DELETE
            else -> error("") // Never happen
        }
    }.default(LocalVariableFixType.NO)
    private val autologger: Boolean by option().flag()
    private val autotoken: Boolean by option().flag()
    private val mappackage: Map<String, String> by option().associate()
    private val mappingsParser: MappingsParser by option("--format").choice("proguard", "csrg").convert {
        when (it) {
            "proguard" -> MappingsProguardParser
            "csrg" -> MappingsCsrgParser
            else -> error("")
        }
    }.default(MappingsProguardParser)


    private val versionInfo by lazy {
        val client = MCVersions()
        val list = client.requestVersionSetAsync().get()
        client.requestVersionAsync(
            list.versions.find { it.id == arg1 }
                ?: throw IllegalArgumentException("$arg1 does not exists")
        ).get()
    }

    override fun run() {
        println("Download input")
        val input = when {
            arg0 == "server" -> versionInfo.downloads.server?.url?.download("input")
                ?: throw IllegalArgumentException("$arg0 does not provide server download url")
            arg0 == "client" -> versionInfo.downloads.client.url.download("input")
            arg0.startsWith("http") -> URL(arg0).download("input")
            else -> File(arg0)
        }

        println("Download mapping")
        val rawMapping = when {
            arg0 == "server" -> versionInfo.downloads.server_mappings?.url?.readText()
                ?: throw IllegalArgumentException("$arg0 does not provide server mapping")
            arg0 == "client" -> versionInfo.downloads.client_mappings?.url?.readText()
                ?: throw IllegalArgumentException("$arg0 does not provide client mapping")
            arg1.startsWith("http") -> URL(arg1).readText()
            else -> File(arg1).readText()
        }

        println("Parse mapping")
        val originalMapping = mappingsParser.parse(rawMapping)
        val mapping = if (reobf) originalMapping else originalMapping.reverse(originalMapping)

        println("Resolve super type")
        val superResolver = ClassVisitorSuperTypeResolver()
        superResolver.resolve(input)

        println("Start mapping")
        val mappingExecutor = Executors.newFixedThreadPool(thread)
        val outputExecutor = Executors.newSingleThreadExecutor()

        val progress = ProgressBarBuilder()
            .setStyle(ProgressBarStyle.COLORFUL_UNICODE_BLOCK)
            .setInitialMax(0)
            .showSpeed()
            .build()

        val applier = MCRemapper(
            mapping.copy(packageMapping = mappackage.asSequence().map { (original, mapped) ->
                original to PackageMapping(original, mapped)
            }.toMap()),
            superResolver,
            fixlocalvar,
            autologger,
            autotoken
        )

        output.delete()

        val zipInput = ZipFile(input)
        val zipOutput = ZipOutputStream(FileOutputStream(output))

        for (entry in zipInput.entries()) {
            progress.maxHint(progress.max + 1)
            val bytes = zipInput.getInputStream(entry).readBytes()
            if (entry.name.endsWith(".class")) {
                mappingExecutor.execute {
                    val reader = ClassReader(bytes)
                    val node = applier.applyMapping(reader)
                    val writer = ClassWriter(0)
                    node.accept(writer)

                    outputExecutor.execute {
                        zipOutput.putNextEntry(ZipEntry("${node.name.toInternal()}.class"))
                        zipOutput.write(writer.toByteArray())
                        zipOutput.closeEntry()
                        progress.step()
                    }
                }
            } else {
                outputExecutor.execute {
                    zipOutput.putNextEntry(ZipEntry(entry.name))
                    ByteArrayInputStream(bytes).copyTo(zipOutput)
                    zipOutput.closeEntry()
                    progress.step()
                }
            }
        }

        mappingExecutor.shutdown()
        mappingExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.HOURS)
        outputExecutor.shutdown()
        outputExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.HOURS)

        zipOutput.close()
        progress.close()
        println("Complete")
    }
}

fun main(args: Array<String>) {
    MCRemapperApp().main(args)
}
