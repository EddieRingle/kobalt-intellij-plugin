package com.beust.kobalt.intelli.plugin

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.io.*
import java.net.ConnectException
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.Executors

public class SyncBuildFileAction : AnAction("Sync build file") {

    companion object {
        const val WRAPPER = "kobalt-wrapper.properties"
    }

    override fun actionPerformed(event: AnActionEvent) {
        val executor = Executors.newFixedThreadPool(2)
        val port = 1234
        event.project?.let { project ->
            readVersion(project)?.let { version ->
                val serverFuture = executor.submit { launchServer(port, version, project.basePath!!) }
                sendGetDependencies(port, project)
            }
        }
    }

    private fun log(level: Int, s: String) {
        println("[SyncBuildFileAction] $s")
    }

    private fun error(s: String) {
        println("[SyncBuildFileAction] *** ERROR: $s")
    }

    private fun sendGetDependencies(port: Int, project: Project) {
        var attempts = 0
        var connected = false
        var socket: Socket? = null
        while (attempts < 3 && ! connected) {
            try {
                socket = Socket("localhost", port)
                connected = true
            } catch(ex: ConnectException) {
                log(1, "Server not started yet, sleeping a bit")
                Thread.sleep(2000)
            }
        }
        if (connected) {
            val outgoing = PrintWriter(socket!!.outputStream, true)
            val buildFiles = FilenameIndex.getFilesByName(project, "Build.kt", GlobalSearchScope.allScope(project))
            buildFiles.forEach {
                val buildFile = it.viewProvider.virtualFile.canonicalPath
                val command: String = "{ \"name\":\"GetDependencies\", \"buildFile\": \"$buildFile\"}"

                outgoing.println(command)

                val ins = BufferedReader(InputStreamReader(socket!!.inputStream))
                var line = ins.readLine()
                var done = false
                while (!done && line != null) {
                    log(1, "Received from server:\n" + line)
                    val jo = JsonParser().parse(line) as JsonObject
                    if (jo.has("name") && "Quit" == jo.get("name").asString) {
                        log(1, "Quitting")
                        outgoing.println("{ \"name\": \"Quit\" }")
                        done = true
                    } else {
                        val data = jo.get("data").asString
                        val dd = Gson().fromJson(data, GetDependenciesData::class.java)

                        println("Read GetDependencyData, project count: ${dd.projects.size()}")

                        line = ins.readLine()
                    }
                }
            }

            outgoing.println(QUIT_COMMAND)
        } else {
            error("Couldn't connect to server")
        }

    }

    private val QUIT_COMMAND = "{ \"name\" : \"Quit\" }"

    private fun launchServer(port: Int, version: String, directory: String) {
        val kobaltJar = findKobaltJar(version)
        println("Kobalt jar: $kobaltJar")
        val args = arrayListOf("java", "-jar", kobaltJar.toFile().absolutePath, "--server", "--port", port.toString())
        val pb = ProcessBuilder(args)
        pb.directory(File(directory))
        pb.inheritIO()
        println("Launching " + args.join(" "))
        val process = pb.start()
        val errorCode = process.waitFor()
        if (errorCode == 0) {
            println("Server exiting")
        } else {
            println("Server exiting with error")
        }
    }

    private fun findKobaltJar(version: String) =
//            Paths.get(System.getProperty("user.home"),".kobalt/wrapper/dist/$version/kobalt/wrapper/kobalt-$version.jar")
            Paths.get("/Users/beust/kotlin/kobalt/kobaltBuild/libs/kobalt-0.193.jar")

    private fun readVersion(project: Project): String? {
        val scope = GlobalSearchScope.allScope(project)

        val wrappers = FilenameIndex.getFilesByName(project, WRAPPER, scope)
        if (wrappers.size() != 1) {
            error("Expected to find exactly one $WRAPPER, found ${wrappers.size()}")
            return null
        }

        val wrapper = wrappers[0]
        val content = wrapper.viewProvider.contents
        val properties = Properties()
        val ins = ByteArrayInputStream(content.toString().toByteArray(StandardCharsets.UTF_8))
        properties.load(ins)
        return properties.getProperty("kobalt.version", null)
    }


    private fun addToDependencies(project: Project) {
        val modules = ModuleManager.getInstance(project).modules
        if (modules.size() > 0) {
            val registrar = LibraryTablesRegistrar.getInstance()
            val libraryTable = registrar.getLibraryTable(project)

            ApplicationManager.getApplication().runWriteAction {
                val ltModel = libraryTable.modifiableModel
                val library = ltModel.createLibrary("JCommander")

                val location = "c:\\users\\cbeust\\.kobalt\\repository\\com\\beust\\jcommander\\1.48\\jcommander-1.48.jar"
                val url = VirtualFileManager.constructUrl(JarFileSystem.PROTOCOL, location) +
                        JarFileSystem.JAR_SEPARATOR
                val libModel = library.modifiableModel
                libModel.addRoot(url, OrderRootType.CLASSES)
                libModel.commit()
                ltModel.commit()

                val moduleRootManager = ModuleRootManager.getInstance(modules[0])
                val moduleModel = moduleRootManager.modifiableModel

                moduleModel.addLibraryEntry(library)
                moduleModel.commit()
            }
        }

    }
}