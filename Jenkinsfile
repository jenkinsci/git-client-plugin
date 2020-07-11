#!groovy

buildPlugin(failFast: false)

if (env.BRANCH_NAME == 'master' || env.BRANCH_NAME.startsWith('gsoc-')) {
    runBenchmarks('jmh-report.json')
}
