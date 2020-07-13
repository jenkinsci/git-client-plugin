#!groovy

buildPlugin(failFast: false)

if (env.BRANCH_NAME == 'master' || env.BRANCH_NAME.startsWith('PR-')) {
    println("The branch name is: " + env.BRANCH_NAME)
    runBenchmarks('jmh-report.json')
}
