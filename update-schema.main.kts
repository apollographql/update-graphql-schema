#!/usr/bin/env kotlin

@file:Repository("file:///Users/mbonnin/.m2/repository")
@file:Repository("https://repo.maven.apache.org/maven2")
@file:DependsOn("com.apollographql.apollo3:apollo-gradle-plugin-external:3.3.1-SNAPSHOT")
@file:DependsOn("com.squareup.okio:okio-jvm:3.1.0")

import com.apollographql.apollo3.gradle.internal.SchemaDownloader
import okio.buffer
import okio.source
import java.io.File

/**
 * Executes the given command and returns stdout as a String
 * Throws if the exit code is not 0
 */
fun executeCommand(vararg command: String): String {
    val process = ProcessBuilder()
        .command(*command)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()

    val output = process.inputStream.source().buffer().readUtf8()

    val exitCode = process.waitFor()
    if (exitCode != 0) {
        error("Command ${command.joinToString(" ")} failed with exitCode '$exitCode'\n" +
                "output was: $output")
    }
    return output
}

fun getInput(name: String): String {
    return System.getenv("INPUT_${name.uppercase()}") ?: error("Cannot find an input for $name")
}


fun run() {
    executeCommand("git", "clone", "https://${System.getenv("GITHUB_SERVER_URL")}/${System.getenv("GITHUB_REPOSITORY")}")

    SchemaDownloader.download(
        endpoint = getInput("endpoint"),
        graph = getInput("graph"),
        key = getInput("key"),
        graphVariant = getInput("graphVariant"),
        registryUrl = getInput("registryUrl"),
        schema = File(getInput("schema")),
        headers =  getInput("headers").split(",").map {
            val c = it.split(":")
            check (c.size == 2) {
                "Bad header: $it"
            }
            c[0].trim() to c[1].trim()
        }.toMap(),
        insecure = getInput("insecure").toBoolean(),
    )

    val gitCleanOutput = executeCommand("git", "status")
    if (gitCleanOutput.contains("nothing to commit, working tree clean")) {
        println("The schema did not change, exiting.")
        return
    }

    executeCommand("git", "checkout", "-b", getInput("branch"))
    executeCommand("git", "commit", "-a", "-m", "update schema")
    executeCommand("git", "push", getInput("remote"), getInput("branch"))
    executeCommand("gh", "pr", "create", "-f")
}

