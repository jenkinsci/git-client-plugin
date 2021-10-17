#!groovy

buildPlugin(failFast: false,
            configurations: [
                [platform: 'linux', jdk: '11'],
                [platform: 'windows', jdk: '8'],
            ])

// Return true if benchmarks should be run
// Benchmarks run if any of the most recent 3 commits includes the word 'benchmark'
boolean shouldRunBenchmarks(String branchName) {
    // Disable benchmarks on default branch for speed
    // if (branchName.endsWith('master') || branchName.endsWith('main')) { // accept either master or main
    //     return true;
    // }
    def recentCommitMessages
    node('linux') {
        checkout scm
        recentCommitMessages = sh(script: 'git log -n 3', returnStdout: true)
    }
    return recentCommitMessages =~ /.*[Bb]enchmark.*/
}

if (shouldRunBenchmarks(env.BRANCH_NAME)) {
    runBenchmarks('jmh-report.json')
}
