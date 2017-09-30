#!groovy

// build both versions, retry test failures
buildPlugin(jenkinsVersions: [null, '2.60.3'],
            findbugs: [run:true, archive:true, unstableTotalAll: '0'],
            failFast: false)

// No plugin compatibility tests, retry test failures
// buildPlugin(failFast: false)
