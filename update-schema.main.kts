#!/usr/bin/env kotlin

@file:Repository("https://s01.oss.sonatype.org/content/repositories/snapshots/")
@file:Repository("https://repo.maven.apache.org/maven2")
@file:DependsOn("com.squareup.okio:okio-jvm:3.1.0")
@file:DependsOn("com.squareup.okhttp3:okhttp:4.10.0")
@file:DependsOn("com.apollographql.apollo3:apollo-tooling:3.3.1-SNAPSHOT")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.3")

import com.apollographql.apollo3.annotations.ApolloExperimental
import com.apollographql.apollo3.tooling.SchemaDownloader
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import okio.source
import java.io.File

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

    val gitCleanOutput = executeCommand("git", "status")
    if (gitCleanOutput.contains("nothing to commit, working tree clean")) {
        println("The schema did not change, exiting.")
        return
    }

    executeCommand("git", "checkout", "-b", headBranch)
    executeCommand("git", "add", getInput("schema"))
    executeCommand(
        "git",
        "-c", "user.name=${getInput("commit_user_name")}",
        "-c", "user.email=${getInput("commit_user_email")}",
        "commit", "-a", "-m", getInput("commit_message"),
        "--author", getInput("commit_author"),
    )
    println("Pushing new branch")
    executeCommand("git", "push", getInput("remote"), headBranch, "--force")

    val details = ghRepositoryDetails()

    if (!details.hasPullRequestOpen) {
        println("Opening pull request")
        ghOpenPullRequest(details.id, baseBranch ?: details.defaultBranch, headBranch, prTitle, prBody)
    } else {
        println("Pull request is already open")
    }
    println("Done.")
}

class RepositoryDetails(
    val id: String,
    val defaultBranch: String,
    val hasPullRequestOpen: Boolean
)

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
    graphQL(operation, mapOf("Authorization" to "bearer ${getInput("token")}"))

fun graphQL(operation: String, headers: Map<String, String> = emptyMap()): Map<String, Any?> {
    val response = mapOf(
        "query" to operation,
        "variables" to emptyMap<Nothing, Nothing>()
    ).toJsonElement().toString()
        .let {
            Request.Builder()
                .post(it.toRequestBody("application/graphql+json".toMediaType()))
                .url("https://api.github.com/graphql")
                .build()
        }
        .let {
            OkHttpClient.Builder()
                .addInterceptor {
                    it.proceed(it.request().newBuilder()
                        .apply {
                            headers.forEach {
                                header(it.key, it.value)
                            }
                        }.build()
                    )
                }
                .build()
                .newCall(it).execute()
        }

    if (!response.isSuccessful) {
        error("Cannot execute GraphQL operation '$operation':\n${response.body?.source()?.readUtf8()}")
    }

    val responseText = response.body?.source()?.readUtf8() ?: error("Cannot read response body")
    return Json.parseToJsonElement(responseText).toAny().asMap
}

fun Any?.toJsonElement(): JsonElement = when (this) {
    is Map<*, *> -> JsonObject(this.asMap.mapValues { it.value.toJsonElement() })
    is List<*> -> JsonArray(map { it.toJsonElement() })
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is String -> JsonPrimitive(this)
    null -> JsonNull
    else -> error("cannot convert $this to JsonElement")
}

fun JsonElement.toAny(): Any? = when (this) {
    is JsonObject -> this.mapValues { it.value.toAny() }
    is JsonArray -> this.map { it.toAny() }
    is JsonPrimitive -> {
        when {
            isString -> this.content
            this is JsonNull -> null
            else -> booleanOrNull ?: intOrNull ?: longOrNull ?: doubleOrNull ?: error("cannot decode $this")
        }
    }
    else -> error("cannot convert $this to Any")
}

inline fun <reified T> Any?.cast() = this as T

val Any?.asMap: Map<String, Any?>
    get() = this.cast()
val Any?.asList: List<Any?>
    get() = this.cast()
val Any?.asString: String
    get() = this.cast()
val Any?.asBoolean: String
    get() = this.cast()
val Any?.asNumber: Number
    get() = this.cast()

run()