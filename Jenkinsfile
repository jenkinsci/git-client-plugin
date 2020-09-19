#!groovy

buildPlugin(failFast: false)

// Return true if benchmarks should be run
// Benchmarks run if any of the most recent 3 commits includes the word 'benchmark'
boolean shouldRunBenchmarks(String branchName) {
    // Disable benchmarks on master branch for speed
    // if (branchName.endsWith('master')) { // accept both origin/master and master
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
