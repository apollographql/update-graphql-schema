#!/usr/bin/env kotlin

@file:Repository("https://repo.repsy.io/mvn/mbonnin/default")
@file:Repository("https://repo.maven.apache.org/maven2")
@file:DependsOn("com.apollographql.apollo3:apollo-gradle-plugin-external:3.3.1-SNAPSHOT")
@file:DependsOn("com.squareup.okio:okio-jvm:3.1.0")

import com.apollographql.apollo3.gradle.internal.SchemaDownloader
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

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
    SchemaDownloader.download(
        endpoint = getOptionalInput("endpoint"),
        graph = getOptionalInput("graph"),
        key = getOptionalInput("key"),
        graphVariant = getInput("graph_variant"),
        registryUrl = getInput("registryUrl"),
        schema = File(getInput("schema")),
        headers = getOptionalInput("headers")?.split(",")?.filter { it.isNotBlank() }?.associate {
            val c = it.split(":")
            check(c.size == 2) {
                "Bad header: $it"
            }
            c[0].trim() to c[1].trim()
        } ?: emptyMap(),
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