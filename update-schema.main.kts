#!/usr/bin/env kotlin

@file:Repository("https://repo.repsy.io/mvn/mbonnin/default")
@file:Repository("https://repo.maven.apache.org/maven2")
@file:DependsOn("com.apollographql.apollo3:apollo-gradle-plugin-external:3.3.1-SNAPSHOT")
@file:DependsOn("com.squareup.okio:okio-jvm:3.1.0")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.3")

import com.apollographql.apollo3.gradle.internal.SchemaDownloader
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

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
        error(
            "Command ${command.joinToString(" ")} failed with exitCode '$exitCode'\n" +
                    "output was: $output"
        )
    }
    return output
}

fun authenticateGithubCli() {
    val process = ProcessBuilder()
        .command("gh", "auth", "login", "--with-token")
        .redirectInput(ProcessBuilder.Redirect.PIPE)
        .redirectOutput(ProcessBuilder.Redirect.INHERIT)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()

    process.outputStream.sink().buffer().use {
        it.writeUtf8("${getInput("token")}\n")
    }

    val exitCode = process.waitFor()
    if (exitCode != 0) {
        error("Cannot authenticate Github Cli")
    }
}

fun getInput(name: String): String {
    return getOptionalInput(name) ?: error("Cannot find an input for $name")
}

fun getOptionalInput(name: String): String? {
    return System.getenv("INPUT_${name.uppercase()}").ifBlank {
        null
    }
}


fun run() {
//    println("endpoint " + getInput("endpoint"))
//    println("schema " + getInput("schema"))
//    println("cwd " + File(".").absolutePath)
//    println("files " + File(".").listFiles().map { it.name }.joinToString(","))

    var branch = getOptionalInput("branch")
    if (branch == null) {
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        branch = String.format(
            "update-schema-%02d-%02d_%02d-%02d",
            now.monthValue,
            now.dayOfMonth,
            now.hour,
            now.minute
        )
    }
    val headers: Map<String, String> = getOptionalInput("headers")?.let { headerJsonStr ->
        try {
            (Json.parseToJsonElement(headerJsonStr) as JsonObject).mapValues { it.value.toString() }
        } catch (e: Exception) {
            error("'headers' must be a JSON object of the form {\"header1\": \"value1\", \"header2\": \"value2\"}")
        }
    } ?: emptyMap()

    SchemaDownloader.download(
        endpoint = getOptionalInput("endpoint"),
        graph = getOptionalInput("graph"),
        key = getOptionalInput("key"),
        graphVariant = getInput("graph_variant"),
        registryUrl = getInput("registryUrl"),
        schema = File(getInput("schema")),
        headers = headers,
        insecure = getInput("insecure").toBoolean(),
    )

    val gitCleanOutput = executeCommand("git", "status")
    if (gitCleanOutput.contains("nothing to commit, working tree clean")) {
        println("The schema did not change, exiting.")
        return
    }

    executeCommand("git", "checkout", "-b", branch)
    executeCommand(
        "git",
        "-c", "user.name=${getInput("commit_user_name")}",
        "-c", "user.email=${getInput("commit_user_email")}",
        "commit", "-a", "-m", getInput("commit_message"),
        "--author", getInput("commit_author"),
    )
    executeCommand("git", "push", "origin", branch)

    authenticateGithubCli()
    executeCommand("gh", "pr", "create", "-f")
}

run()
