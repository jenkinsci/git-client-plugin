#!groovy

echo "Branch name is ${env.BRANCH_NAME}"

buildPlugin(failFast: false)

if (env.BRANCH_NAME == 'master' || env.BRANCH_NAME.startsWith('gsoc-')) {
    runBenchmarks('jmh-report.json')
}
