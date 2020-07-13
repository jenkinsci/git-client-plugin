#!groovy

buildPlugin(failFast: false)

if (env.BRANCH_NAME == 'master' || env.BRANCH_NAME.startsWith('gsoc-')) {
    println("Benchmarks will be executed")
    runBenchmarks('jmh-report.json')
} else {
    println("Benchmarks will not be executed")
}
