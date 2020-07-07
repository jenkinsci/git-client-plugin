#!groovy

buildPlugin(failFast: false)

def branchName = "${env.BRANCH_NAME}"
if (branchName ==~ /master/ || branchName =~ /gsoc-*/) {
	runBenchmarks('jmh-report.json')
}
