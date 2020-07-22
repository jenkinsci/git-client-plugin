#!groovy

buildPlugin(failFast: false)

// Return true if benchmarks should be run
// Benchmarks run on the master branch always
// Benchmarks run if any of the most recent 3 commits includes the word 'benchmark'
boolean shouldRunBenchmarks(String branchName) {
    if (branchName.endsWith('master')) { // accept both origin/master and master
        return true;
    }
    def gitLog = 'git log -n 3'
    def recentCommitMessages
    node('linux') {
        recentCommitMessages = sh(script: gitLog, returnStdout: true)
    }
    return recentCommitMessages =~ /.*[Bb]enchmark.*/
}

if (shouldRunBenchmarks(env.BRANCH_NAME)) {
    runBenchmarks('jmh-report.json')
}
