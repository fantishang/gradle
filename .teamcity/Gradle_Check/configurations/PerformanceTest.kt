package configurations

import jetbrains.buildServer.configs.kotlin.v10.BuildStep
import jetbrains.buildServer.configs.kotlin.v10.BuildType
import jetbrains.buildServer.configs.kotlin.v10.buildSteps.gradle
import jetbrains.buildServer.configs.kotlin.v10.buildSteps.script
import model.PerformanceTestType

class PerformanceTest(type: PerformanceTestType) : BuildType({
    uuid = type.asId()
    extId = uuid
    name = "Performance ${type.name.capitalize()} Coordinator - Linux"

    applyDefaultSettings(this, timeout = 240)
    artifactRules = "subprojects/*/build/performance-tests/** => results"
    detectHangingBuilds = false
    maxRunningBuilds = 1

    params {
        param("performance.baselines", type.defaultBaselinesBranches)
        param("env.GRADLE_OPTS", "-Xmx1536m -XX:MaxPermSize=384m")
        param("env.JAVA_HOME", "/opt/jdk/oracle-jdk-8-latest")
        param("performance.db.url", "jdbc:h2:ssl://dev61.gradle.org:9092")
        param("performance.db.username", "tcagent")
        param("TC_USERNAME", "TeamcityRestBot")
    }

    steps {
        script {
            name = "SELECT_BASELINE"
            executionMode = BuildStep.ExecutionMode.ALWAYS
            scriptContent = """
                branch="%teamcity.build.branch%"
                baseline="%performance.baselines%"
                if [ "${'$'}baseline" = "${type.defaultBaselinesBranches}" ] && [ "${'$'}branch" == "master" ]; then
                  echo "##teamcity[setParameter name='performance.baselines' value='${type.defaultBaselines}']"
                fi
                if [ "${'$'}baseline" = "${type.defaultBaselinesBranches}" ] && [ "${'$'}branch" == "release" ]; then
                  echo "##teamcity[setParameter name='performance.baselines' value='${type.defaultBaselines}']"
                fi
            """.trimIndent()
        }
        gradle {
            name = "GRADLE_RUNNER"
            tasks = ""
            gradleParams = "cleanDistributed${type.taskId} distributed${type.taskId}s -x prepareSamples %performance.baselines% ${type.extraParameters} -PtimestampedVersion -Porg.gradle.performance.branchName=%teamcity.build.branch% -Porg.gradle.performance.db.url=%performance.db.url% -Porg.gradle.performance.db.username=%performance.db.username% -PteamCityUsername=%TC_USERNAME% -PteamCityPassword=%teamcity.password.restbot% -Porg.gradle.performance.buildTypeId=Gradle_Util_IndividualPerformanceScenarioWorkersLinux -Porg.gradle.performance.workerTestTaskName=fullPerformanceTest -Porg.gradle.performance.coordinatorBuildId=%teamcity.build.id% -Porg.gradle.performance.db.password=%performance.db.password.tcagent% " + gradleParameters.joinToString(separator = " ")
            useGradleWrapper = true
        }
        script {
            name = "CHECK_CLEAN_M2"
            executionMode = BuildStep.ExecutionMode.ALWAYS
            scriptContent = m2CleanScriptLinux
        }
        gradle {
            name = "TAG_BUILD"
            executionMode = BuildStep.ExecutionMode.ALWAYS
            tasks = "tagBuild"
            buildFile = "gradle/buildTagging.gradle"
            gradleParams = "-PteamCityUsername=%teamcity.username.restbot% -PteamCityPassword=%teamcity.password.restbot% -PteamCityBuildId=%teamcity.build.id% -PgithubToken=%github.ci.oauth.token% -Djava7.home=%linux.jdk.for.gradle.compile%"
            useGradleWrapper = true
        }
    }

    applyDefaultDependencies(this, true)
})