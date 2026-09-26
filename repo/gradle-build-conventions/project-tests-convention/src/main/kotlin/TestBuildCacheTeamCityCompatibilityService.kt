/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

import org.gradle.api.logging.Logging
import org.gradle.api.provider.MapProperty
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFinishEvent
import org.gradle.tooling.events.task.TaskSuccessResult
import java.io.File

/**
 * Reports the tests of the test tasks served from the Gradle Build Cache to TeamCity.
 *
 * One service for the whole build, holding the `test-executions.json` of every test task in the
 * graph; when it is subscribed to task completions is load bearing, see [configureTestInventory].
 * What is reported about a recording is [TestExecutionsReplay]'s; this is when, and where to.
 */
abstract class TestBuildCacheTeamCityCompatibilityService :
    BuildService<TestBuildCacheTeamCityCompatibilityService.Parameters>,
    OperationCompletionListener {

    interface Parameters : BuildServiceParameters {
        /** Path of a test task to the `test-executions.json` recorded for it. */
        val executionsFiles: MapProperty<String, File>
    }

    private val log = Logging.getLogger(javaClass)

    private companion object {
        /** EXPERIMENT, to be removed with the sleep in [onFinish]. */
        const val SETTLE_MILLIS = 5_000L
    }

    /** Read once: every event wants the same map, and reading it finalizes the parameters. */
    private val executionsFiles: Map<String, File> by lazy { parameters.executionsFiles.get() }

    override fun onFinish(event: FinishEvent) {
        if (event !is TaskFinishEvent) return
        val taskPath = event.descriptor.taskPath
        val executionsFile = executionsFiles[taskPath] ?: return
        val result = event.result as? TaskSuccessResult ?: return
        if (!result.isFromCache && !result.isUpToDate) return

        val replay = TestExecutionsReplay(taskPath) { message, failure -> log.warn(message, failure) }
        var count = 0
        for (message in replay.messagesFor(executionsFile)) {
            println("##teamcity[$message]")
            count++
        }

        // EXPERIMENT, to be removed: TeamCity loses the last 5-75 KB of a large replay, always the
        // tail and always at the moment the build step's process exits. Gradle is not the one
        // dropping it - the same volume through a reader as slow as the agent loses nothing - so
        // this gives the agent quiet time to catch up before the build can end. Both lines below go
        // through the same 'println' the messages do, so the marker is lost with them if the tail is.
        println("TestReplay: $count messages of $taskPath emitted, settling for $SETTLE_MILLIS ms")
        Thread.sleep(SETTLE_MILLIS)
        println("TestReplay: $taskPath settled")
    }
}
