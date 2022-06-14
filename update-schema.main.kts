#!/usr/bin/env kotlin

@file:DependsOn("net.mbonnin.bare-graphql:bare-graphql:0.0.1")
@file:DependsOn("com.apollographql.apollo3:apollo-tooling:3.3.1")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.3")

import com.apollographql.apollo3.annotations.ApolloExperimental
import com.apollographql.apollo3.tooling.SchemaDownloader
import kotlinx.serialization.json.*
import net.mbonnin.bare.graphql.asList
import net.mbonnin.bare.graphql.asMap
import net.mbonnin.bare.graphql.asString
import net.mbonnin.bare.graphql.graphQL
import okio.buffer
import okio.source
import java.io.File
import kotlin.system.exitProcess

val ghRepositoryOwner = System.getenv("GITHUB_REPOSITORY").split("/")[0]
val ghRepositoryName = System.getenv("GITHUB_REPOSITORY").split("/")[1]
val headBranch = getInput("branch")

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

fun getInput(name: String): String {
    return getOptionalInput(name) ?: error("Cannot find an input for $name")
}

fun getOptionalInput(name: String): String? {
    return System.getenv("INPUT_${name.uppercase()}")?.ifBlank {
        null
    }
}


fun run() {
//    println("endpoint " + getInput("endpoint"))
//    println("schema " + getInput("schema"))
//    println("cwd " + File(".").absolutePath)
//    println("files " + File(".").listFiles().map { it.name }.joinToString(","))

    val prTitle = getInput("pr_title")
    val prBody = getInput("pr_body")
    val baseBranch = getOptionalInput("base_branch")

    val headers: Map<String, String> = getOptionalInput("headers")?.let { headerJsonStr ->
        try {
            (Json.parseToJsonElement(headerJsonStr) as JsonObject).mapValues { it.value.jsonPrimitive.content }
        } catch (e: Exception) {
            error("'headers' must be a JSON object of the form {\"header1\": \"value1\", \"header2\": \"value2\"}")
        }
    } ?: emptyMap()

    val schemaFile = File(getInput("schema"))

    @OptIn(ApolloExperimental::class)
    SchemaDownloader.download(
        endpoint = getOptionalInput("endpoint"),
        graph = getOptionalInput("graph"),
        key = getOptionalInput("key"),
        graphVariant = getOptionalInput("graph_variant") ?: "current",
        registryUrl = getOptionalInput("registryUrl") ?: "https://graphql.api.apollographql.com/api/graphql",
        schema = schemaFile,
        headers = headers,
        insecure = getOptionalInput("insecure").toBoolean(),
    )

    if (isRepoClean()) {
        println("The schema did not change, exiting.")
        exitProcess(0)
    }

    val details = ghRepositoryDetails()
    val remote = getInput("remote")

    if (!details.hasPullRequestOpen) {
        println("Opening pull request")
        executeCommand("git", "checkout", "-b", headBranch)
        addAndCommit()
        // Force push because there might be a stale branch from an older PR
        executeCommand("git", "push", remote, headBranch, "--force")
        ghOpenPullRequest(details.id, baseBranch ?: details.defaultBranch, headBranch, prTitle, prBody)
    } else {
        println("Pull request is already open, update branch")
        val tmpSchema = File.createTempFile("schema", "graphqls")
        schemaFile.copyTo(tmpSchema, overwrite = true)
        executeCommand("git", "stash")
        // if the schema is a new file, it might not be stashed and prevent the checkout -> remove it
        executeCommand("git", "clean", "-fd")
        executeCommand("git", "fetch", remote, "--depth", "1", headBranch)
        executeCommand("git", "checkout", headBranch)
        tmpSchema.copyTo(schemaFile, overwrite = true)

        if (isRepoClean()) {
            println("The schema did not change, exiting.")
            exitProcess(0)
        }
        addAndCommit()
        executeCommand("git", "push", remote, headBranch)
    }
    println("Done.")
}

fun addAndCommit() {
    executeCommand("git", "add", getInput("schema"))
    executeCommand(
        "git",
        "-c", "user.name=${getInput("commit_user_name")}",
        "-c", "user.email=${getInput("commit_user_email")}",
        "commit", "-a", "-m", getInput("commit_message"),
        "--author", getInput("commit_author"),
    )
}

class RepositoryDetails(
    val id: String,
    val defaultBranch: String,
    val hasPullRequestOpen: Boolean
)

fun isRepoClean(): Boolean {
    val gitCleanOutput = executeCommand("git", "status")
    return gitCleanOutput.contains("nothing to commit, working tree clean")
}

fun ghRepositoryDetails(): RepositoryDetails {
    val query = """
        {
          repository(owner: "$ghRepositoryOwner", name: "$ghRepositoryName") {
            id
            defaultBranchRef {
              id
              name
            }
            pullRequests(first: 100, headRefName: "$headBranch") {
              nodes {
                state
              }
            }
          }
        }
    """.trimIndent()

    val data = ghGraphQL(query)["data"].asMap

    val repository = data["repository"].asMap
    return RepositoryDetails(
        id = repository["id"].asString,
        defaultBranch = repository["defaultBranchRef"].asMap["name"].asString,
        hasPullRequestOpen = repository["pullRequests"].asMap["nodes"].asList.any { it.asMap["state"] == "OPEN" }
    )
}

fun ghOpenPullRequest(repositoryId: String, baseBranch: String, headBranch: String, title: String, body: String) {
    val operation = """
        mutation {
          createPullRequest(input: {repositoryId: "$repositoryId", baseRefName: "$baseBranch", headRefName: "$headBranch", title: "$title", body: "$body", }) {
            clientMutationId
          }
        }
    """.trimIndent()

    ghGraphQL(operation)["data"].asMap
}

fun ghGraphQL(operation: String): Map<String, Any?> =
    graphQL(operation, emptyMap(), mapOf("Authorization" to "bearer ${getInput("token")}"))


run()